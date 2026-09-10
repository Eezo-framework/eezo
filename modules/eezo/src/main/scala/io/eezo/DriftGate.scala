package io.eezo

import io.eezo.db.cli.{Commands, Render, StatusResult}
import io.eezo.core.html.{Attrs, Html, Mod}
import io.eezo.core.html.Tags.*
import io.eezo.db.Schema
import io.eezo.db.Scopes.{read, transact}
import io.eezo.db.migrate.Decision
import io.eezo.db.schema.Change
import io.eezo.http.{Eezo, Handler, Method, PathPattern, Response, Route, RouteTable}

import scala.util.control.NonFatal

/** The database edge's contribution to `dev` (design/cli.md §5): the drift check at boot, and when
  * the drift is dangerous an *interactive* refusal page in place of the app. It answers a route
  * table or nothing; `EezoApp.devServer` is what serves either, so the server's overrides (`port`,
  * `maxBodySize`, `problems`) reach the drift page and the app the same way.
  *
  * The middle position §5 argues for: **refuse on destructive or risky drift, banner on additive.**
  * Destructive drift serves a page saying what is out of step, because a browser is where a dev
  * loop user is looking; additive drift is a console warning and the app serves, because a new
  * column the database does not have yet breaks nothing until the code touches it. A database that
  * cannot be reached at all is a warning too, not a refusal: `eezo dev` with Postgres down still
  * serves whatever does not need it.
  *
  * **The decisions live on the page, not in the terminal.** Under `eezoDev` the forked app shares
  * one stdin with sbt's watch ("press enter to interrupt"), so a boot time console prompt would
  * race the watch for every keystroke, the reason sbt-revolver never did interactive forked apps
  * either. The drift page instead carries the same choices the CLI's freeze prompt offers, per
  * destructive change, posted back to two reserved routes. Both handlers feed the *same* layer 1
  * functions the terminal does (`Commands.sync`, `Commands.freeze`'s `decide` parameter), so the
  * form is a third front end over one mechanism.
  *
  * The drift is checked again on **every GET**, so resolving it, from the page or from a second
  * terminal, turns the refusal into "resolved ✓ — save a file to restart" on the next refresh
  * rather than a stale refusal. What a refresh cannot do is serve the app: the route table and the
  * app's state are minted at process boot, and restart is eezo's reload model: the next file save
  * is the restart.
  *
  * An app with no declared schema skips the check entirely, even against a reachable database with
  * tables in it: `status` answers that question honestly (drop everything), but refusing to *serve*
  * over tables the app never declared would block every app with no schema that happens to share a
  * Postgres with something else.
  *
  * No CSRF token on the forms, deliberately: this server exists only while refusing to route in
  * development, and its actions are the ones the developer's own terminal already offers.
  */
private[eezo] object DriftGate {

  /** The drift page's two reserved routes, under the prefix `Eezo` keeps for the framework. Each is
    * spelled once, so the form that posts and the route that answers cannot drift apart.
    */
  private val SyncPath: String   = s"${Eezo.ReservedPrefix}/sync"
  private val FreezePath: String = s"${Eezo.ReservedPrefix}/freeze"

  /** The refusal table when the drift blocks, `None` when the app may serve. Prints the banners
    * either way. Runs under the installed `Database`.
    */
  def apply(schema: Schema, databaseSchema: String): Option[RouteTable] = {
    val drift = currentDrift(schema, databaseSchema)
    if (blockers(drift).nonEmpty) {
      println(Render.status(StatusResult(drift)))
      println("""
        |serving the drift page instead of the app; resolve it there, or here:
        |  sync --apply     apply this to your dev database
        |  freeze <name>    write it as a migration""".stripMargin)
      Some(driftTable(schema, databaseSchema))
    } else {
      if (drift.nonEmpty) {
        println("[eezo] ⚠ the database does not match your models; additive only, serving anyway")
        println(Render.status(StatusResult(drift)))
      }
      None
    }
  }

  private def blockers(drift: List[Change]): List[Change] =
    drift.filter(c => c.destructive || c.risky)

  private def currentDrift(schema: Schema, databaseSchema: String): List[Change] =
    try {
      if (schema.snapshot.tables.isEmpty) Nil
      else read { Commands.status(schema, databaseSchema).changes }
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[eezo] ⚠ drift check skipped, database unreachable: ${e.getMessage}")
        Nil
    }

  /** The refusal table: the page on every GET, and the two actions it posts to. */
  private def driftTable(schema: Schema, databaseSchema: String): RouteTable = {

    val page: Handler = _ => {
      val drift = currentDrift(schema, databaseSchema)
      if (blockers(drift).isEmpty) resolved(drift) else refusal(drift, error = None)
    }

    // The prototyping path: the terminal's `sync --apply --force`, one button. Force, because
    // this page only exists when the drift is destructive or risky; an unforced sync would
    // refuse by construction, and the button *is* the review.
    val syncAction: Handler = _ =>
      attempt(schema, databaseSchema) {
        transact { Commands.sync(schema, apply = true, force = true, databaseSchema) }: Unit
        println("[eezo] drift applied to the dev database from the drift page")
      }

    // The keeping path: `freeze <name>` with the page's per change decisions standing in for the
    // terminal prompt, then `migrate --apply` so the database converges in the same submit.
    val freezeAction: Handler = request => {
      request.form.get("name").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty) match {
        case None =>
          refusal(currentDrift(schema, databaseSchema), error = Some("the migration needs a name"))
        case Some(name) =>
          attempt(schema, databaseSchema) {
            freezeAndMigrate(schema, databaseSchema, name, request)
          }
      }
    }

    RouteTable(
      Seq(
        Route.Http(Method.GET, PathPattern.parse("/"), page),
        Route.Http(Method.GET, PathPattern.parse("/*rest"), page),
        Route.Http(Method.POST, PathPattern.parse(SyncPath), syncAction),
        Route.Http(Method.POST, PathPattern.parse(FreezePath), freezeAction)
      )
    )
  }

  /** Runs one page action; a failure renders the refusal again with the database's own words on it.
    *
    * This is not decoration: the first live run of this page hit exactly it: a `[risky]`
    * `set not null` that Postgres refused over existing rows. That failure is the `risky` flag
    * doing its job, and it belongs on the page the user is looking at, not in a stack trace behind
    * a 500. The transaction has already rolled back by the time it is caught, so rendering again
    * over a half applied state is not a possibility.
    */
  private def attempt(schema: Schema, databaseSchema: String)(action: => Unit): Response =
    try {
      action
      Response.Redirect("/")
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[eezo] drift page action failed: ${e.getMessage}")
        refusal(
          currentDrift(schema, databaseSchema),
          error = Some(s"that did not work: ${e.getMessage}")
        )
    }

  private def freezeAndMigrate(
      schema: Schema,
      databaseSchema: String,
      name: String,
      request: io.eezo.http.Request
  ): Unit = {
    val decide: Change => Decision = change =>
      if (!change.destructive) Decision.Accept
      else
        request.form.get(decisionField(change)).flatMap(_.headOption) match {
          case Some("drop") => Decision.Accept
          // Missing or "keep": skip loses nothing, so it is the default for a change the
          // form never showed as well as for one left on "keep".
          case _ => Decision.Skip
        }

    Commands.freeze(schema, name, decide).migration match {
      case Some(path) => println(s"[eezo] wrote $path from the drift page")
      case None       =>
        println(
          "[eezo] nothing to freeze: the committed snapshot already matches the model. " +
            "This drift came from the database side; sync is the tool for that."
        )
    }
    val migrated = transact {
      Commands.migrate(schema, apply = true, dbSchema = databaseSchema)
    }
    println(Render.migrate(migrated))
  }

  /** The form field carrying one destructive change's decision. Keyed by `describe` rather than by
    * position, because the freeze diff at submit time need not be the drift list the page rendered;
    * `describe` is the one name both sides share.
    */
  private def decisionField(change: Change): String = s"decision:${change.describe}"

  private def shell(pageTitle: String, contents: Mod*): Response =
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title(pageTitle)),
        body(contents*)
      )
    )

  /** 503 rather than 200, so an agent polling the app sees "not serving" instead of mistaking this
    * page for content.
    */
  private def refusal(drift: List[Change], error: Option[String]): Response = {
    val decisions = drift.filter(_.destructive).map { change =>
      p(
        code(change.describe),
        label(
          input(
            Attrs.tpe     := "radio",
            Attrs.name    := decisionField(change),
            Attrs.value   := "keep",
            Attrs.checked := true
          ),
          " keep, skip"
        ),
        label(
          input(Attrs.tpe := "radio", Attrs.name := decisionField(change), Attrs.value := "drop"),
          " drop (data lost)"
        )
      )
    }

    shell(
      "eezo — schema drift",
      h1("⚠ the database does not match your models"),
      pre(code(drift.map(Render.change).mkString("\n"))),
      error.map(message => p(strong(message))).toSeq,
      form(
        Attrs.method := "post",
        Attrs.action := SyncPath,
        button("apply to the dev database"),
        small(" — no migration written; dev only")
      ),
      form(
        Attrs.method := "post",
        Attrs.action := FreezePath,
        if (decisions.isEmpty) Seq.empty[Html]
        else Seq(fieldset(legend("destructive changes — decide each one"), decisions)),
        p(
          label(
            "migration name ",
            input(
              Attrs.tpe         := "text",
              Attrs.name        := "name",
              Attrs.placeholder := "add isbn to book",
              Attrs.required    := true
            )
          ),
          button("freeze as a migration and apply it")
        )
      ),
      p("The app is not served while the changes above are unresolved.")
    ).copy(status = 503)
  }

  private def resolved(drift: List[Change]): Response =
    shell(
      "eezo — resolved",
      h1("resolved ✓"),
      if (drift.isEmpty) Seq(p("in sync"))
      else
        Seq(
          p("only additive drift remains:"),
          pre(code(drift.map(Render.change).mkString("\n")))
        ),
      p("Save any file to restart the dev server and serve the app.")
    )
}
