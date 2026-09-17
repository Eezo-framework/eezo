package io.eezo

/** The guard lookup the sbt plugin writes into every application's route table, compiled for real.
  *
  * `RouteGeneratorSuite` in `modules/sbt-plugin` can only string match the generated source. A
  * change that stops the strict message from folding to a constant, such as computing `name` from
  * something the compiler cannot see through at the call site, would pass every one of those
  * assertions while turning the application side failure into "A literal string is expected as an
  * argument to compiletime.error", a message that points at generated code the user cannot edit and
  * never names the model. Nothing but a compiler can see that, and `compileErrors` takes a literal
  * source, so the helpers below are copied from `RouteGenerator` rather than produced by calling
  * it.
  *
  * The copy is not left to trust. `RouteGeneratorSuite` reads this very file and fails unless it
  * contains, character for character, the three helper bodies the generator emits, so a helper that
  * changes without this file changing is a red build in the plugin's own suite. That is the whole
  * mechanism: the plugin is cross built for sbt 1 on Scala 2.12 (ADR 0002), so `modules/eezo` can
  * never depend on it and call `render` directly, and one file read is smaller than publishing a
  * shared artifact for three helper strings.
  *
  * This lives in the umbrella rather than in `modules/sbt-plugin` itself because the helpers only
  * name `io.eezo.http`, and `sbt-plugin` never depends on `http`. `PasswordStorageSuite` sits
  * beside this test for the same reason: the umbrella is the one place a macro assertion about
  * generated application code can compile.
  *
  * `// format: off` around each copy is what keeps the pin honest. scalafmt formats this file and
  * does not format the file the generator writes, so an alignment scalafmt prefers here would be a
  * drift the plugin's suite then reports against a generator that never changed.
  */
class GeneratedGuardForSuite extends munit.FunSuite {

  test("the strict helper names the offending type when nothing guards it") {
    val failure =
      compileErrors("""io.eezo.StrictTable.forRoute[io.eezo.Unguarded]("app.Unguarded")""")
    assert(clue(failure).contains("no Guarded given for"), failure)
    assert(clue(failure).contains("app.Unguarded"), failure)
  }

  test("the strict helper hands back the declaration a page wrote") {
    val guarded = StrictTable.forRoute[DeclaredPage.type]("app.DeclaredPage")
    assertEquals(guarded.actions, Set[io.eezo.http.Action](io.eezo.http.Action.Index))
    assertEquals(guarded.carries, Seq(DeclaredPage.login))
  }

  test("a model that mounts nothing is not asked to declare anything") {
    // `examples/blog`'s `User` derives `Table` alone, so it has no `Resource` and mounts no route.
    val guarded = StrictTable.forModel[Unguarded]("models.Unguarded")
    assertEquals(guarded.actions, Set.empty[io.eezo.http.Action])
    assertEquals(guarded.carries, Seq.empty[io.eezo.http.Route])
  }

  test("the lenient helper is public when nothing guards it, so an app with no users compiles") {
    val guarded = PublicTable.forRoute[Unguarded]
    assertEquals(guarded.actions, Set.empty[io.eezo.http.Action])
    assertEquals(guarded.carries, Seq.empty[io.eezo.http.Route])
  }

  test("the lenient helper still hands back a declaration that was written") {
    // The defect this pins: an application on the umbrella, or one reaching auth through a
    // `dependsOn` module, declares no `eezo-auth` of its own. Its table used to mount every route
    // bare, so `given Guarded[Post] = User.guard.required` beside the model was read by nobody and
    // the admin pages were public with nothing anywhere to say so.
    val guarded = PublicTable.forRoute[DeclaredPage.type]
    assertEquals(guarded.actions, Set[io.eezo.http.Action](io.eezo.http.Action.Index))
    assertEquals(guarded.carries, Seq(DeclaredPage.login))
  }
}

/** A type with no `Guarded` and no `Resource` beside it: the silence both readings answer. */
class Unguarded

/** A page that declares a guard in its own companion, the way `examples/blog`'s `Index` does.
  *
  * The declaration is not `Guarded.public`, so a table that read it and a table that ignored it
  * cannot be mistaken for one another.
  */
object DeclaredPage {

  val login: io.eezo.http.Route =
    io.eezo.http.Route.Http(
      io.eezo.http.Method.GET,
      io.eezo.http.PathPattern.parse("/login"),
      _ => throw new UnsupportedOperationException("never dispatched")
    )

  given io.eezo.http.Guarded[DeclaredPage.type] =
    io.eezo.http.Guarded(Set[io.eezo.http.Action](io.eezo.http.Action.Index), identity, Seq(login))
}

/** The helpers `RouteGenerator` emits for a build that declares `eezo-auth`, verbatim.
  *
  * Everything between the `format: off` markers is the generator's text and is edited only by
  * copying it again. The two forwarders after it are this suite's own: the generator writes these
  * helpers `private`, so a test outside the generated object reaches them through a call of its
  * own.
  */
object StrictTable {

  // format: off
  /** Who may reach this route. Every mounted route has to say, because this application's
    * build declares eezo-auth and so the application has a way of signing in.
    */
  private inline def guardFor[A](inline name: String): io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case g: io.eezo.http.Guarded[A] => g
      case _                          =>
        scala.compiletime.error(
          "no Guarded given for " + name + ", and this application's build declares " +
            "eezo-auth, so every mounted route has to say who may reach it. In its " +
            "companion, one of:\n" +
            "  given io.eezo.http.Guarded[T] = <yourGuard>.required\n" +
            "  given io.eezo.http.Guarded[T] = io.eezo.http.Guarded.public"
        )
    }

  /** Who may reach a derived model's routes. A model with no `Resource` mounts nothing, so it
    * is not asked to declare anything.
    */
  private inline def guardForModel[A](inline name: String): io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case _: io.eezo.http.Resource[A] => guardFor[A](name)
      case _                           => io.eezo.http.Guarded.public[A]
    }
  // format: on

  inline def forRoute[A](inline name: String): io.eezo.http.Guarded[A] = guardFor[A](name)

  inline def forModel[A](inline name: String): io.eezo.http.Guarded[A] = guardForModel[A](name)
}

/** The helper `RouteGenerator` emits for a build that declares no `eezo-auth`, verbatim.
  *
  * There is no model helper here, because sparing a model from an error there is no error to raise
  * would be a second spelling of this one.
  */
object PublicTable {

  // format: off
  /** Who may reach this route. A `given Guarded` beside the model or the page is what the
    * table mounts it behind; this application's build declares no eezo-auth, so saying
    * nothing means anyone may reach it.
    */
  private inline def guardFor[A]: io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case g: io.eezo.http.Guarded[A] => g
      case _                          => io.eezo.http.Guarded.public[A]
    }
  // format: on

  inline def forRoute[A]: io.eezo.http.Guarded[A] = guardFor[A]
}
