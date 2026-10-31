package io.eezo.sbt.dev

import Ansi._

/** How the console divides the terminal, from its size alone.
  *
  * Full: a header row, a tab row, a rule, the pane, a rule, a footer. Compact (under twelve rows):
  * header and tabs share one row, the rules go, the footer goes. Tiny (under six rows): one status
  * row and nothing else. Under forty columns the footer goes whatever the height, since no key hint
  * fits beside another. A prompt always takes the last row, whichever row that is.
  */
private[sbt] final case class Layout(width: Int, height: Int) {
  val tiny: Boolean    = height < 6
  val compact: Boolean = height < 12
  val narrow: Boolean  = width < 60
  val headerRows: Int  = if (compact) 1 else 2
  val ruleRows: Int    = if (compact) 0 else 2
  val footerRows: Int  = if (compact || width < 40) 0 else 1
  val paneHeight: Int  = if (tiny) 0 else math.max(0, height - headerRows - ruleRows - footerRows)
}

/** The lines of each pane, before scrolling: the part of the view `Update` needs as well, to clamp
  * a scroll offset to what the pane holds.
  */
private[sbt] object Panes {

  /** The columns a pane line may take: the row less its one column margin on the left. The scroll
    * clamp measures with this and the view wraps with it, so the two agree on the count.
    */
  def width(m: Model): Int = math.max(1, m.width - 2)

  def lines(m: Model, tab: Tab, width: Int)(implicit theme: Theme): Vector[String] =
    if (m.help) help(m)
    else
      tab match {
        case Tab.Log    => log(m, width)
        case Tab.Errors => errors(m, width)
        case Tab.Routes => routes(m, width)
        case Tab.Schema => schema(m, width)
      }

  private def log(m: Model, width: Int)(implicit theme: Theme): Vector[String] =
    if (m.log.isEmpty) Vector(Dim("nothing yet"))
    else
      m.log.flatMap { line =>
        val style = line.kind match {
          case LogLine.App  => Plain
          case LogLine.Err  => Plain
          case LogLine.Note => Dim
          case LogLine.Ok   => Green
          case LogLine.Fail => Red
        }
        val mark = line.kind match {
          case LogLine.Ok   => s"${theme.ok} "
          case LogLine.Fail => s"${theme.fail} "
          case LogLine.Note => s"${theme.bullet} "
          case _            => ""
        }
        wrap(mark + line.text, width).map(style(_))
      }

  private def errors(m: Model, width: Int)(implicit theme: Theme): Vector[String] = {
    val errors   = m.errors
    val warnings = m.warnings
    if (errors.isEmpty && warnings.isEmpty)
      Vector(m.build match {
        case BuildState.Idle            => Dim("no build yet")
        case BuildState.Compiling(_, _) => Dim("compiling")
        case _                          => Green(s"${theme.ok} no errors, no warnings")
      })
    else {
      def block(d: Diagnostic): Vector[String] = {
        val (style, mark) = if (d.isError) (Red, theme.fail) else (Yellow, theme.warn)
        val header        = style(s"$mark ${d.where}")
        val body          = d.rendered match {
          case Some(text) =>
            text.split('\n').toVector.map(l => if (theme.color) l else stripAnsi(l))
          case None => d.message.split('\n').toVector
        }
        (header +: body.flatMap(l => wrap(l, width))) :+ ""
      }
      errors.flatMap(block) ++ warnings.flatMap(block)
    }
  }

  private def routes(m: Model, width: Int)(implicit theme: Theme): Vector[String] =
    m.routes match {
      case None          => Vector(Dim("no route table yet: it arrives when the application boots"))
      case Some(listing) =>
        val rows = listing.routes
        if (rows.isEmpty) Vector(Dim("no routes"))
        else {
          val methodWidth = rows.map(_.method.length).max
          val pathWidth   =
            math.min(rows.map(_.path.length).max, math.max(10, width - methodWidth - 20))
          val table = rows.map { r =>
            val method = Cyan(padRight(r.method, methodWidth))
            val path   = padRight(shorten(r.path, pathWidth, theme.ellipsis), pathWidth)
            val tail   = Dim((if (r.eezo) "eezo" else r.provenance) + (if (r.api) "  api" else ""))
            truncate(s"$method  $path  $tail", width)
          }
          val warnings =
            listing.overridden.map(r =>
              Yellow(
                s"${theme.warn} derived ${r.method} ${r.path} is replaced by a handwritten route"
              )
            ) ++
              listing.shadowed.map { case (earlier, later) =>
                Yellow(
                  s"${theme.warn} ${earlier.method} ${earlier.path} shadows ${later.method} ${later.path}"
                )
              } ++
              listing.orphans.map(o =>
                Yellow(
                  s"${theme.warn} ${o.page} at ${o.pageRoute} submits to ${o.target} at ${o.targetRoute}, which is not mounted"
                )
              )
          val title = Bold(s"${rows.size} route${if (rows.size == 1) "" else "s"}")
          (title +: table) ++ (if (warnings.isEmpty) Vector.empty
                               else "" +: warnings.flatMap(w => wrap(w, width)))
        }
    }

  private def schema(m: Model, width: Int)(implicit theme: Theme): Vector[String] = {
    val state: Vector[String] = m.schema match {
      case SchemaState.NoDatabase => Vector(Dim("this application has no database layer"))
      case SchemaState.Unknown    =>
        Vector(
          Dim("no drift report yet: it arrives when the application boots, or press S to check")
        )
      case SchemaState.Known(drift) if drift.inSync =>
        Vector(Green(s"${theme.ok} in sync: the database matches the model"))
      case SchemaState.Known(drift) =>
        val title =
          if (drift.blocking)
            Red(
              s"${theme.fail} ${drift.changes.size} difference(s); the drift page is served instead of the app"
            )
          else Yellow(s"${theme.warn} ${drift.changes.size} additive difference(s); serving anyway")
        val changes = drift.changes.flatMap(c => wrap(change(c), width))
        val advice  =
          if (drift.blocking)
            Vector(
              "",
              Dim("s  apply to the dev database (--force; data is lost on the destructive ones)"),
              Dim("f  freeze as a named migration, then migrate")
            )
          else
            Vector(
              "",
              Dim("s  apply to the dev database"),
              Dim("f  freeze as a named migration, then migrate")
            )
        (title +: changes) ++ advice
    }
    // The last command's answer is one line here, in the colour of its exit code: green for done,
    // yellow for the command's own answer (drift found, a refusal), red for a failure. What it
    // printed is in the log.
    val outcome: Vector[String] = m.outcome match {
      case None    => Vector.empty
      case Some(o) =>
        val (style, mark) =
          if (o.ok) (Green, theme.ok)
          else if (o.code == 1 && o.json.isDefined) (Yellow, theme.warn)
          else (Red, theme.fail)
        val line =
          style(s"$mark eezo ${o.action.label}: ${Describe.summary(o)}") +
            Dim(s"  ${theme.bullet} output in ${Tab.Log.number} ${Tab.Log.name}")
        val detail = o.action match {
          case Action.Migrate(false) if o.json.exists(_("outcome").strOr("") == "pending") =>
            o.json.toVector.flatMap(_("pending").arr).map(p => Dim(s"  ${p("file").strOr("?")}"))
          case _ => Vector.empty
        }
        ("" +: truncate(line, width) +: detail)
    }
    val running = m.busy
      .map(a => Vector("", Cyan(s"${theme.spinner(m.spinner)} eezo ${a.label}")))
      .getOrElse(Vector.empty)
    state ++ outcome ++ running
  }

  /** The change as `db status` describes it, which already carries its `+`, `-` or `~`, in the
    * colour of its class, with the SQL dimmed after it.
    */
  def change(c: Change)(implicit theme: Theme): String = {
    val style = if (c.destructive) Red else if (c.risky) Yellow else Green
    val flag  = if (c.destructive) " [destructive]" else if (c.risky) " [risky]" else ""
    // The SQL of a `create table` spans lines; one row holds it on one.
    style(
      s"${c.describe}$flag"
    ) + (if (c.sql.isEmpty) "" else Dim("  " + c.sql.replaceAll("\\s+", " ")))
  }

  private def help(m: Model)(implicit theme: Theme): Vector[String] = {
    val db =
      if (m.hasDatabase)
        Vector(
          ("s", "sync: apply the drift to the dev database (asks before --force)"),
          ("f", "freeze: write the drift as a named migration, then migrate"),
          ("m", "migrate: list pending migrations, then apply"),
          ("S", "status: diff the model against the database now")
        )
      else Vector.empty
    val keys = Vector(
      ("1-4", "the panes: errors, routes, schema, log"),
      ("tab", "next pane"),
      ("j k", "scroll (also arrows, PgUp, PgDn, g, G)"),
      ("r", "rebuild and restart now"),
      ("c", "clear the log")
    ) ++ db ++ Vector(("?", "this help"), ("q", "quit (also Ctrl-C)"))
    val w = keys.map(_._1.length).max
    Bold("keys") +: keys.map { case (k, what) => s"  ${Bold(padRight(k, w))}  $what" } :+ "" :+ Dim(
      "any key closes this"
    )
  }

  private def stripAnsi(s: String): String = s.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "")
}

/** The text of a schema command's answer, in the words the terminal front end uses. */
private[sbt] object Describe {

  def status(d: Drift): String =
    if (d.inSync) "in sync" else s"${d.changes.size} difference(s) between model and database"

  /** One line for the answer: what the pane shows beside the glyph. */
  def summary(o: Outcome): String =
    if (o.unknownCommand) "no database layer"
    else
      o.json match {
        case None    => if (o.ok) "done" else s"exited ${o.code}"
        case Some(j) =>
          o.action match {
            case Action.Status  => status(Drift.fromJson(j))
            case Action.Sync(_) =>
              if (j("applied").boolOr(false)) "applied to the dev database"
              else if (j("refused").boolOr(false))
                s"refused: ${j("blocked").arr.size} change(s) need review"
              else if (j("changes").arr.isEmpty) "in sync"
              else s"exited ${o.code}"
            case Action.Freeze(_, _) =>
              j("migration").str.map(path => s"wrote $path").getOrElse("nothing to freeze")
            case Action.Migrate(_) =>
              j("outcome").strOr("") match {
                case "tampered" => "migration integrity check failed"
                case "upToDate" => "no pending migrations"
                case "pending"  => s"${j("pending").arr.size} pending; Enter applies"
                case "applied"  => s"${j("applied").arr.size} migration(s) applied"
                case other      => s"exited ${o.code} ($other)"
              }
          }
      }

  /** What the command said, line by line, for the log: its stdout as text, or the JSON it printed
    * read back into the words the terminal front end uses. Its stderr is not here; the caller adds
    * that, filtered.
    */
  def outcome(o: Outcome): Vector[String] = o.json match {
    case None    => o.out
    case Some(j) =>
      val body = o.action match {
        case Action.Status =>
          val d = Drift.fromJson(j)
          status(d) +: d.changes.map(c => s"  ${c.describe}")
        case Action.Sync(_) =>
          val changes = j("changes").arr.map(Change.fromJson)
          val tail    =
            if (j("applied").boolOr(false)) "applied"
            else if (j("refused").boolOr(false))
              s"refusing: ${j("blocked").arr.size} change(s) need review"
            else if (changes.isEmpty) "in sync"
            else ""
          changes.map(c => s"  ${c.describe}") :+ tail
        case Action.Freeze(_, _) =>
          j("migration").str match {
            case None       => Vector("nothing to freeze")
            case Some(path) =>
              j("resolutions").arr.map { r =>
                val note = r("decision").strOr("") match {
                  case "skip"   => "  (skipped)"
                  case "manual" => "  (manual sql)"
                  case _        => ""
                }
                s"  ${r("change")("describe").strOr("?")}$note"
              } :+ s"wrote $path"
          }
        case Action.Migrate(_) =>
          j("outcome").strOr("") match {
            case "tampered" =>
              j("problems").arr
                .flatMap(_.str)
                .map(p => s"  $p") :+ "migration integrity check failed"
            case "upToDate" => Vector("no pending migrations") ++ verified(j("drift").arr)
            case "pending"  => pending(j("pending").arr) :+ "Enter applies"
            case "applied"  => (pending(j("applied").arr) :+ "applied") ++ verified(j("drift").arr)
            case other      => Vector(s"unexpected outcome: $other")
          }
      }
      body.filter(_.nonEmpty)
  }

  private def pending(ps: Vector[JValue]): Vector[String] =
    if (ps.isEmpty) Vector("no pending migrations")
    else s"${ps.size} pending migration(s):" +: ps.map(p => s"  ${p("file").strOr("?")}")

  private def verified(drift: Vector[JValue]): Vector[String] =
    if (drift.isEmpty) Vector("database matches model")
    else s"${drift.size} difference(s) remain:" +: drift.map(c => s"  ${c("describe").strOr("?")}")
}

/** The frame: exactly `height` rows of at most `width` columns, from the model alone. */
private[sbt] object View {

  /** [[frame]] with the theme named, for a caller that holds another theme implicitly. */
  def render(m: Model, theme: Theme): Vector[String] = {
    implicit val named: Theme = theme
    frame(m)
  }

  def frame(m: Model)(implicit theme: Theme): Vector[String] = {
    val layout = Layout(m.width, m.height)
    val w      = m.width
    if (w <= 0 || m.height <= 0) Vector.empty
    else if (layout.tiny)
      Vector.fill(m.height)("").updated(0, m.prompt.map(promptRow(m, _)).getOrElse(statusRow(m, w)))
    else
      {
        val header: Vector[String] =
          if (layout.compact) Vector(spread(tabsRow(m, layout), brief(m), w))
          else Vector(spread(title(m), appStatus(m) + " ", w), tabsRow(m, layout))
        val rule   = Dim(theme.rule * w)
        val pane   = paneRows(m, layout)
        val footer = if (layout.footerRows == 0) Vector.empty else Vector(footerRow(m, layout))
        val rows   =
          if (layout.compact) header ++ pane ++ footer
          else (header :+ rule) ++ pane ++ (rule +: footer)
        val sized = rows.take(m.height).padTo(m.height, "")
        m.prompt match {
          case Some(p) => sized.updated(m.height - 1, promptRow(m, p))
          case None    => sized
        }
      }.map(r => truncate(sanitize(r), w))
  }

  /** A row must stay on its row: a newline or a carriage return in an application's line would move
    * the cursor, and a tab would move it by an amount the width arithmetic cannot see.
    */
  private def sanitize(row: String): String =
    if (row.indexOf('\n') < 0 && row.indexOf('\r') < 0 && row.indexOf('\t') < 0) row
    else row.replace('\t', ' ').replace("\r", "").replace("\n", " ")

  private def title(m: Model)(implicit theme: Theme): String =
    Bold(s" eezo dev") + Dim(s" ${theme.bullet} ") + Bold(m.project)

  /** The build, as the footer's right end shows it: the spinner and the trigger while compiling,
    * then the glyph and the time, or the error count.
    */
  private def buildStatus(m: Model)(implicit theme: Theme): String =
    m.build match {
      case BuildState.Idle                      => Dim("idle")
      case BuildState.Compiling(trigger, since) =>
        val elapsed = math.max(0L, m.now - since)
        Cyan(s"${theme.spinner(m.spinner)} compiling${trigger.map(t => s" ($t)").getOrElse("")}") +
          Dim(if (elapsed >= 1000) s" ${Update.seconds(elapsed)}" else "")
      case BuildState.Built(millis, warnings) =>
        Green(s"${theme.ok} built in ${Update.seconds(millis)}") +
          (if (warnings.isEmpty) "" else Yellow(s" ${theme.warn}${warnings.size}"))
      case BuildState.Failed(ds, _) =>
        Red(
          s"${theme.fail} ${ds.count(_.isError)} error${if (ds.count(_.isError) == 1) "" else "s"}"
        )
    }

  /** The application, as the header's right end shows it: the address it serves on, or why not. */
  private def appStatus(m: Model)(implicit theme: Theme): String =
    m.app match {
      case AppState.Stopped          => Dim("stopped")
      case AppState.Starting(_)      => Dim("starting")
      case AppState.Running(Some(u)) => Bold(u)
      case AppState.Running(None)    => Green("serving")
      case AppState.Exited(code)     => Red(s"${theme.warn} exited $code")
    }

  /** Build and application together, for the one row of the tiny layout. */
  private def status(m: Model)(implicit theme: Theme): String =
    s"${buildStatus(m)} ${Dim(theme.bullet)} ${appStatus(m)} "

  /** The status with the words left out, for a row shared with the tabs: the build's glyph and
    * time, and the application only when it is not simply running.
    */
  private def brief(m: Model)(implicit theme: Theme): String = {
    val build = m.build match {
      case BuildState.Idle             => Dim("idle")
      case BuildState.Compiling(_, _)  => Cyan(theme.spinner(m.spinner))
      case BuildState.Built(millis, _) => Green(s"${theme.ok} ${Update.seconds(millis)}")
      case BuildState.Failed(ds, _)    => Red(s"${theme.fail} ${ds.count(_.isError)}")
    }
    val app = m.app match {
      case AppState.Exited(code) => " " + Red(s"${theme.warn} $code")
      case AppState.Stopped      => " " + Dim("stopped")
      case _                     => ""
    }
    s"$build$app "
  }

  private def statusRow(m: Model, w: Int)(implicit theme: Theme): String =
    spread(title(m), status(m), w)

  private def tabsRow(m: Model, layout: Layout)(implicit theme: Theme): String = {
    val tabs = Tab.all.map { t =>
      val count: String = t match {
        case Tab.Errors =>
          val n = m.errors.size
          if (n > 0) " " + Red(s"${theme.fail}$n")
          else if (m.warnings.nonEmpty) " " + Yellow(s"${theme.warn}${m.warnings.size}")
          else ""
        case Tab.Routes => m.routes.map(r => " " + Dim(s"${r.routes.size}")).getOrElse("")
        case Tab.Schema =>
          m.schema match {
            case SchemaState.Known(d) if d.blocking => " " + Red(s"${theme.fail}${d.changes.size}")
            case SchemaState.Known(d) if !d.inSync  =>
              " " + Yellow(s"${theme.warn}${d.changes.size}")
            case SchemaState.Known(_) => " " + Green(theme.ok)
            case _                    => ""
          }
        case Tab.Log => ""
      }
      val name  = if (layout.narrow) t.short else t.name
      val label = s"${t.number} $name$count"
      if (t == m.tab) Reverse(Bold(s" $label "))
      else if (layout.narrow) label
      else Dim(" ") + label + Dim(" ")
    }
    // Narrow, the idle tabs lose their own padding, so that four tabs with their counts and the
    // brief status after them fit in forty columns.
    " " + tabs.mkString(if (layout.narrow) "  " else " ")
  }

  private def paneRows(m: Model, layout: Layout)(implicit theme: Theme): Vector[String] = {
    val all    = Panes.lines(m, m.tab, Panes.width(m))
    val offset = Update.effectiveOffset(m)
    all.slice(offset, offset + layout.paneHeight).map(l => " " + l).padTo(layout.paneHeight, "")
  }

  private def footerRow(m: Model, layout: Layout)(implicit theme: Theme): String = {
    def key(k: String, what: String): String = Bold(k) + Dim(s" $what")
    val db                                   =
      if (m.hasDatabase && m.busy.isEmpty)
        Vector(key("s", "sync"), key("f", "freeze"), key("m", "migrate"))
      else Vector.empty
    val always = Vector(key("r", "restart")) ++ db ++ Vector(key("?", "help"), key("q", "quit"))
    val keys   = if (layout.narrow) always.take(1) ++ always.takeRight(2) else always
    val busy = m.busy.map(a => Cyan(s"${theme.spinner(m.spinner)} ${a.label}") + "  ").getOrElse("")
    val all  = Panes.lines(m, m.tab, Panes.width(m)).size
    val offset = Update.effectiveOffset(m)
    val pos    =
      if (all <= layout.paneHeight) ""
      else {
        val last = math.min(all, offset + layout.paneHeight)
        Dim(s"$last/$all ${if (m.tab == Tab.Log && m.follow) theme.down else theme.up}") + "  "
      }
    // The build's state ends the row, bottom right, where the eye returns between saves.
    val build = if (layout.narrow) brief(m) else buildStatus(m) + " "
    spread(" " + keys.mkString("  "), pos + busy + build, m.width)
  }

  private def promptRow(m: Model, p: Prompt)(implicit theme: Theme): String = {
    val narrow = Layout(m.width, m.height).narrow
    val text   = p match {
      case Prompt.ConfirmForce(n) =>
        if (narrow)
          Yellow(s" ${theme.warn} $n lose data. ") + Bold("Enter") + " --force " + Bold(
            "Esc"
          ) + " cancel"
        else
          Yellow(s" ${theme.warn} $n change(s) lose data or can fail. ") + Bold(
            "Enter"
          ) + " applies with --force, " + Bold("Esc") + " cancels"
      case Prompt.FreezeName(buffer) =>
        if (narrow) " name: " + Bold(buffer) + Reverse(" ")
        else
          " migration name: " + Bold(buffer) + Reverse(" ") + Dim("   Enter writes it, Esc cancels")
      case Prompt.FreezePolicy(name, n) =>
        if (narrow)
          Yellow(s" ${theme.warn} $n destructive: ") + Bold("k") + " keep " + Bold(
            "d"
          ) + " drop " + Bold("Esc")
        else
          Yellow(s" ${theme.warn} $n destructive change(s) in $name: ") + Bold(
            "k"
          ) + " keep the data (skip them)  " + Bold("d") + " drop  " + Bold("Esc") + " cancels"
      case Prompt.ConfirmMigrate(n) =>
        if (narrow) s" $n pending. " + Bold("Enter") + " apply " + Bold("Esc") + " cancel"
        else
          s" $n pending migration(s). " + Bold("Enter") + " applies them, " + Bold(
            "Esc"
          ) + " cancels"
    }
    Ansi.padRight(text, m.width)
  }
}
