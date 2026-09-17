package io.eezo.sbt

import scala.util.matching.Regex

/** One route written by hand under `app/`, as the generator sees it: text, and nothing else.
  *
  * @param method
  *   the HTTP method the filename implies
  * @param path
  *   the pattern the directory layout implies
  * @param target
  *   the fully qualified `object.def` the row calls
  * @param source
  *   the file it came from, which the emitted row carries as a comment
  * @param owner
  *   the object the `def` lives on, `app.widgets.Index` for `app/widgets/Index.scala`. Carried
  *   rather than recomputed from [[target]], because a target can end in a backticked name such as
  *   `` app.widgets.New.`new` `` and dropping "the last dotted segment" off one of those is a
  *   second spelling of a rule this file already applies once. It is what a handwritten route's
  *   `Guarded` is declared about: there is no model behind such a route, so the page's own object
  *   is the thing that says who may reach it.
  */
final case class HandwrittenRoute(
    method: String,
    path: String,
    target: String,
    source: String,
    owner: String
)

/** A case class the generator will ask the compiler about, by name.
  *
  * "Candidate" rather than "model" on purpose: the generator never decides whether this type has a
  * `Resource`. It emits `Resource.routesOf[fqn]`, which resolves to `Nil` when there is no
  * instance, so a false positive costs one line that mounts nothing and a comment that mentions
  * `derives` is not worth stripping out.
  *
  * @param fqn
  *   the fully qualified name, built from the file's `package` clauses
  * @param source
  *   the file it came from, which the emitted row carries as a comment
  */
final case class ModelCandidate(fqn: String, source: String)

/** Turns an application's `app/` tree into eezo's route table.
  *
  * Everything here is textual on purpose. The generator is an sbt plugin running before the
  * compiler, so the only warning it can raise is the one a text scan can decide, a file that does
  * not define the `def` its name promises. Shadowing and an orphaned form page are properties of
  * the assembled table rather than of any one file, and warn at boot instead.
  *
  * This file is compiled against sbt 1 on Scala 2.12 and against sbt 2 on Scala 3, so it stays
  * inside the subset both accept.
  */
object RouteGenerator {

  /** The filename to verb table: the seven REST names, with one departure. A custom name calls a
    * `def` of its own name rather than `index`, so object name equals def name for it too. A custom
    * name is always a GET; a custom POST is written as `app/health/Create.scala`.
    */
  private val Verbs: Map[String, (String, String, String)] = Map(
    // file name -> (method, def name, path suffix)
    "Index"   -> (("GET", "index", "")),
    "New"     -> (("GET", "new", "/new")),
    "Show"    -> (("GET", "show", "")),
    "Edit"    -> (("GET", "edit", "/edit")),
    "Create"  -> (("POST", "create", "")),
    "Update"  -> (("PUT", "update", "")),
    "Destroy" -> (("DELETE", "destroy", ""))
  )

  /** Scala's own keywords that a `def` name has to be backticked to use. `new` is the one the verb
    * table actually reaches.
    */
  private val Keywords = Set("new", "type", "class", "object", "val", "def", "match")

  /** A file's `package` clauses, in order, so that nested ones join into the prefix the compiler
    * resolves. `package object` is excluded by requiring a plain identifier path.
    */
  private val PackageClause: Regex =
    // Not column-anchored: the same editor that indents a model indents its package clause,
    // and the code mask already keeps `package` in a comment or string from matching.
    new Regex("\\bpackage\\s+((?!object\\b)[A-Za-z_][\\w.]*)")

  private val Derives: Regex = new Regex("\\bderives\\b")

  // ── the model scan's structural half ──────────────────────────────────────────────────────
  //
  // "Top level" used to mean "column zero", which is a heuristic with a failure on each side: an
  // editor-indented top-level model compiled, deployed, and silently mounted nothing (found on the
  // first field deploy), and a column-zero class nested inside braces would have produced a name
  // that does not compile. Both were the same mistake — reading layout where the question is
  // structure — so the scan now tracks structure: a code mask strips comments and strings, brace
  // depth decides nesting, and an enclosing-container stack names what is nested. Column only
  // matters in the one place structure genuinely cannot answer: a depth-zero indented declaration
  // in a file using significant indentation, where the indent *is* the structure and the scan
  // says so instead of guessing.

  private val CaseClassAnywhere: Regex =
    new Regex("\\bcase\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)")

  /** Containers that can enclose a model. `case class` matches too (at its `class` token), which is
    * wanted: a class body is a real enclosure.
    */
  private val ContainerDecl: Regex =
    new Regex("\\b(object|class|trait|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)")

  /** Anything that means "the next `{` is not the pending container's body": a member keyword or an
    * `=`. Over-clearing is the safe direction — an unattributed brace pushes an anonymous frame,
    * which can only demote a candidate to a warning, never mint a wrong name.
    */
  private val PendingClear: Regex =
    new Regex("\\b(?:val|var|def|given|type|import|package|new)\\b|=")

  /** A container opened with a colon: the file nests by indentation, and depth stops meaning
    * nesting for what follows.
    */
  private val ContainerColon: Regex =
    new Regex("(?m)\\b(?:object|class|trait|enum)\\s+[A-Za-z_][A-Za-z0-9_]*[^\\n{}]*:[ \\t]*$")

  /** Which characters are code — not comments (line, nested block) and not string or character
    * literals. Interpolated `$${...}` blocks inside strings are masked with the string; their
    * braces are balanced within it, so depth is unaffected either way.
    */
  private def codeMask(content: String): Array[Boolean] = {
    val n    = content.length
    val mask = new Array[Boolean](n)
    var i    = 0
    while (i < n) {
      val c = content.charAt(i)
      if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
        while (i < n && content.charAt(i) != '\n') i += 1
      } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
        var depth = 1
        i += 2
        while (i < n && depth > 0) {
          if (content.charAt(i) == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
            depth += 1; i += 2
          } else if (content.charAt(i) == '*' && i + 1 < n && content.charAt(i + 1) == '/') {
            depth -= 1; i += 2
          } else i += 1
        }
      } else if (
        c == '"' && i + 2 < n && content.charAt(i + 1) == '"' && content.charAt(i + 2) == '"'
      ) {
        i += 3
        var open = true
        while (i < n && open) {
          if (
            content.charAt(i) == '"' && i + 2 < n &&
            content.charAt(i + 1) == '"' && content.charAt(i + 2) == '"'
          ) {
            i += 3
            while (i < n && content.charAt(i) == '"') i += 1
            open = false
          } else i += 1
        }
      } else if (c == '"') {
        i += 1
        var open = true
        while (i < n && open) {
          val s = content.charAt(i)
          if (s == '\\' && i + 1 < n) i += 2
          else if (s == '"' || s == '\n') { i += 1; open = false }
          else i += 1
        }
      } else if (c == '\'') {
        if (i + 1 < n && content.charAt(i + 1) == '\\') {
          i += 2
          while (i < n && content.charAt(i) != '\'') i += 1
          i += 1
        } else if (i + 2 < n && content.charAt(i + 2) == '\'') i += 3
        else { mask(i) = true; i += 1 }
      } else {
        mask(i) = true
        i += 1
      }
    }
    mask
  }

  /** One `case class` as the walk saw it: where, how deep, inside what, and whether its declaration
    * carries a `derives` clause.
    */
  private final case class ScannedClass(
      name: String,
      column: Int,
      enclosing: List[Option[(String, String)]], // innermost first; None = anonymous block
      derives: Boolean
  )

  private def maskedIn(regex: Regex, content: String, mask: Array[Boolean]) =
    regex.findAllMatchIn(content).filter(m => mask(m.start)).toVector

  private def scanClasses(content: String): (Vector[ScannedClass], Boolean) = {
    val mask = codeMask(content)

    // The event stream: braces, container declarations, case classes, and pending-clearers, in
    // document order. Sorting merges four regex passes into one walk.
    sealed trait Event { def pos: Int }
    case class Open(pos: Int)                                  extends Event
    case class Close(pos: Int)                                 extends Event
    case class Container(pos: Int, kind: String, name: String) extends Event
    case class Clazz(pos: Int, name: String)                   extends Event
    case class Clear(pos: Int)                                 extends Event

    val braces = (0 until content.length).iterator
      .filter(mask)
      .collect {
        case i if content.charAt(i) == '{' => Open(i)
        case i if content.charAt(i) == '}' => Close(i)
      }
      .toVector
    // `ContainerDecl` also matches the `class` token inside every `case class`. As an enclosure
    // (the pending container for a following body brace) that reading is wanted; as a declaration
    // *boundary* it would end the case class's own derives window five characters in, so those
    // positions are marked and skipped when boundaries are recorded below.
    val classTokens: Set[Int] =
      maskedIn(CaseClassAnywhere, content, mask)
        .map(m => m.start + m.matched.indexOf("class"))
        .toSet

    val events: Vector[Event] =
      (braces ++
        maskedIn(ContainerDecl, content, mask).map(m =>
          Container(m.start, m.group(1), m.group(2))
        ) ++
        maskedIn(CaseClassAnywhere, content, mask).map(m => Clazz(m.start, m.group(1))) ++
        maskedIn(PendingClear, content, mask).map(m => Clear(m.start))).sortBy(_.pos)

    var stack   = List.empty[Option[(String, String)]]
    var pending = Option.empty[(String, String)]
    // (position, depth) of every declaration and container-close, for the derives windows below.
    val boundaries = Vector.newBuilder[(Int, Int)]
    val closes     = Vector.newBuilder[(Int, Int)]
    val found      = Vector.newBuilder[(Int, String, Int, List[Option[(String, String)]])]

    events.foreach {
      case Open(_)  => stack = pending :: stack; pending = None
      case Close(p) => if (stack.nonEmpty) { stack = stack.tail; closes += ((p, stack.length)) }
      case Container(p, kind, name) =>
        if (!classTokens.contains(p)) boundaries += ((p, stack.length))
        pending = Some((kind, name))
      case Clear(_)       => pending = None
      case Clazz(p, name) =>
        boundaries += ((p, stack.length))
        val column = p - (content.lastIndexOf('\n', p - 1) + 1)
        found += ((p, name, column, stack))
    }

    val allBoundaries = boundaries.result()
    val allCloses     = closes.result()
    val derivesAt     = maskedIn(Derives, content, mask).map(_.start)

    val classes = found.result().map { case (pos, name, column, enclosing) =>
      val depth = enclosing.length
      // The declaration's text ends at the next declaration at the same or an enclosing depth, or
      // where its own container closes — whichever comes first.
      val windowEnd = (
        allBoundaries.collect { case (p, d) if p > pos && d <= depth => p } ++
          allCloses.collect { case (p, d) if p > pos && d < depth => p } :+ content.length
      ).min
      ScannedClass(name, column, enclosing, derivesAt.exists(d => d > pos && d < windowEnd))
    }

    (classes, maskedIn(ContainerColon, content, mask).nonEmpty)
  }

  /** The route a file under `app/` mounts, or nothing when the file is not Scala source.
    *
    * `relative` is the path below `app/`, such as `widgets/_id/Show.scala`. Directory names carry
    * the pattern: `_seg` becomes `:seg` and `__seg` becomes `*seg`, which are the three segment
    * kinds `PathPattern` keeps.
    */
  def routeFor(relative: String): Option[HandwrittenRoute] =
    if (!relative.endsWith(".scala")) None
    else {
      val parts                     = relative.split('/').toVector
      val fileName                  = parts.last.dropRight(".scala".length)
      val dirs                      = parts.dropRight(1)
      val (method, defName, suffix) =
        Verbs.getOrElse(fileName, ("GET", decapitalise(fileName), "/" + fileName.toLowerCase))

      val dirPath = dirs.map(segment)
      val path    = ("/" + (dirPath :+ suffix.stripPrefix("/")).filter(_.nonEmpty).mkString("/"))
      val owner   = (Vector("app") ++ dirs ++ Vector(fileName)).mkString(".")
      val target  = owner + "." + quoted(defName)

      Some(
        HandwrittenRoute(
          method = method,
          path = path,
          target = target,
          source = "src/main/scala/app/" + relative,
          owner = owner
        )
      )
    }

  /** Emit order, because dispatch is linear first match and the table never sorts.
    *
    * Most static segments first, comparing segment by segment with a literal beating `:name`
    * beating `*rest`, and ties broken by the path and then by the method so that two runs of the
    * generator produce the same file. Sorting here rather than at boot is what makes that safe:
    * under filesystem order `GET /widgets/new` would be unreachable whenever `/widgets/:id` happens
    * to be listed first.
    */
  def sortRoutes(routes: Seq[HandwrittenRoute]): Seq[HandwrittenRoute] =
    routes.sortWith { (left, right) =>
      val comparison = compare(left, right)
      comparison < 0
    }

  /** Every mountable `case class` in one file that carries a `derives` clause.
    *
    * Textual, and deliberately permissive rather than accurate: a false positive costs one line
    * whose `Resource.routesOf[X]` resolves to `Nil`, a false *name* would break the generated
    * file's compile, and a false negative mounts nothing in silence. So the rules mint a name only
    * where the scan is sure of it, and everything else warns (see [[unscannableModels]]):
    *
    *   - **depth zero, any column** — top level. Column stopped being the test the day an
    *     editor-indented model deployed and mounted nothing; brace depth is the structure. The one
    *     exception: in a file whose containers open with `:` (significant indentation), depth
    *     cannot see nesting, so there an indented depth-zero class is ambiguous and warns rather
    *     than guesses.
    *   - **nested, every enclosing frame a named `object`** — mounted as `pkg.Outer.Inner`, the
    *     stable path the compiler resolves.
    *   - **nested in a class, trait, enum, or anonymous block** — no stable path exists; warns.
    */
  def modelsIn(relative: String, content: String): Seq[ModelCandidate] =
    if (!relative.endsWith(".scala")) Nil
    else {
      val (classes, colonStyle) = scanClasses(content)
      val prefix                = packagePrefix(content)
      val source                = "src/main/scala/" + relative

      classes.collect {
        case c if c.derives && mountablePath(c, colonStyle).isDefined =>
          val path = mountablePath(c, colonStyle).get
          ModelCandidate(((prefix ++ path) :+ c.name).mkString("."), source)
      }
    }

  /** The dotted path in front of a mountable class's name, or `None` when it cannot be mounted. */
  private def mountablePath(c: ScannedClass, colonStyle: Boolean): Option[Seq[String]] =
    if (c.enclosing.isEmpty) {
      if (c.column == 0 || !colonStyle) Some(Seq.empty) else None
    } else if (c.enclosing.forall(_.exists(_._1 == "object")))
      Some(c.enclosing.reverse.map(_.get._2))
    else None

  private def packagePrefix(content: String): Seq[String] = {
    val mask = codeMask(content)
    maskedIn(PackageClause, content, mask).map(_.group(1)).flatMap(_.split('.').toSeq)
  }

  /** The generated file: one object, the handwritten rows under `app/` and then one
    * `Resource.routesOf` line per candidate model.
    *
    * Handwritten first is what makes decision 18's precedence visible in the emitted text rather
    * than only in a merge step somebody can get wrong, since dispatch is first match in table
    * order. Order settles the routes that merely overlap, such as a handwritten `/posts/latest`
    * ahead of a derived `/posts/:id`; the exact same method and path is settled by the route's
    * provenance instead, so the derived twin is dropped rather than left behind the winner.
    *
    * `table` is a `def` that mints the stores, so no user ever writes `InMemoryStore`, and each
    * call gets stores of its own, which is what makes a test that calls `Routes.table()` start from
    * an empty world. One per model rather than one for the application: `core`'s `Store[A]` is
    * typed by the model it holds, so the name that used to pick a bucket is now the type argument
    * the compiler checks.
    *
    * Which store a model gets is the compiler's decision rather than the generator's: no row names
    * an implementation, every one of them calls the emitted `storeFor[A]`, and only the body of
    * that one helper differs between the two arms. `dbOnClasspath` is what picks the arm, and it is
    * a flag rather than an always-emitted branch because the branch names `io.eezo.db.Table`, a
    * type that exists only when `eezo-db` is a dependency of the application being generated into.
    * Emitting it unconditionally would put an unresolvable name in every application that has no
    * database, so the generator would break the builds it exists to serve. A generator cannot ask
    * the compiler what resolves, since it runs before it, so it asks the build instead; see
    * `EezoPlugin.generate` for where the answer comes from.
    *
    * For the same reason nothing in the emitted file is ever `import`ed from `io.eezo.db`. Every db
    * name inside the picked branch is written out in full, because an import is a declaration the
    * whole file pays for: in an application that has `eezo-db` on its classpath but no model
    * deriving `Table`, the `summonFrom` never selects its first case, and an import nothing used
    * would fail that application's build under `-Werror`.
    *
    * The helper follows the derived rows it serves: with no candidate models there are no rows, so
    * there is no helper either. A `private inline def` nobody calls is exactly the unused
    * declaration `-Wunused:all` reports, and a generated file has to compile clean under the
    * strictest options a user is entitled to turn on.
    *
    * @param dbOnClasspath
    *   whether `eezo-db` resolves for the project being generated into, and so whether the emitted
    *   `storeFor` may name `io.eezo.db` at all
    * @param authDeclared
    *   whether the build being generated into named `eezo-auth` among its own dependencies, and so
    *   whether a route that declares no `Guarded` is a compile error rather than a public route
    */
  def render(
      routes: Seq[HandwrittenRoute],
      models: Seq[ModelCandidate],
      dbOnClasspath: Boolean,
      authDeclared: Boolean = false
  ): String = {
    def built(route: HandwrittenRoute, indent: String): String =
      s"""io.eezo.http.Route.Http(
         |$indent  io.eezo.http.Method.${route.method},
         |$indent  io.eezo.http.PathPattern.parse("${route.path}"),
         |$indent  req => ${route.target}(req)
         |$indent)""".stripMargin

    // The name a helper is called with, which only the strict helper takes: it is the one thing
    // that has to reach `compiletime.error`, and the lenient helper has no error to raise. An
    // argument nothing reads would be the `unused explicit parameter` that `-Wunused:all` reports
    // in the application's own build.
    def named(fqn: String): String = if (authDeclared) s"""("$fqn")""" else ""

    // Every row goes through the guard lookup, whatever the flag says. `Guarded` lives in `http`,
    // so it resolves wherever a route is mounted at all, and a declaration that was written is
    // therefore always the one the table uses. The flag decides what *silence* means, and that
    // decision lives in the helper's own fallback arm rather than out here.
    //
    // Each row is a `Seq` rather than one route, because `mounting` answers with the guard's own
    // pages beside the route it wrapped, so the rows are concatenated instead of listed.
    val handwritten =
      if (routes.isEmpty) "    // no files under src/main/scala/app/"
      else
        sortRoutes(routes)
          .map { route =>
            s"""    // from ${route.source}
               |    guardFor[${route.owner}.type]${named(route.owner)}.mounting(
               |      ${built(route, "      ")}
               |    )""".stripMargin
          }
          .mkString(" ++\n")

    val handwrittenBlock =
      if (routes.isEmpty)
        s"""  private val handwritten: Seq[io.eezo.http.Route] = Seq(
           |$handwritten
           |  )""".stripMargin
      else
        s"""  private val handwritten: Seq[io.eezo.http.Route] =
           |$handwritten""".stripMargin

    // A model's lookup goes through `guardForModel` only where silence is an error, because that
    // helper exists to spare a model mounting no route from the error. Where silence is public the
    // two helpers would have the same body, so the rows call `guardFor` directly.
    def modelGuard(fqn: String): String =
      if (authDeclared) s"""guardForModel[$fqn]("$fqn")""" else s"guardFor[$fqn]"

    val body =
      if (models.isEmpty)
        if (routes.isEmpty) "    io.eezo.http.RouteTable(handwritten)"
        else "    io.eezo.http.RouteTable(handwritten.distinct)"
      else {
        val derived = models
          .sortBy(_.fqn)
          .map { model =>
            s"""      // from ${model.source}
               |      io.eezo.http.Resource.routesOf[${model.fqn}](storeFor[${model.fqn}], ${modelGuard(
                model.fqn
              )})""".stripMargin
          }
          .mkString(" ++\n")

        // `distinct` wherever there is a row, and it is not a tidy-up. Every declaration one guard
        // makes carries that guard's login and logout routes, so a table with two guarded things
        // holds them twice, and `RouteTable` throws on the same method and path twice rather than
        // picking a winner. It is unconditional because a guard can now be declared in an
        // application whose build never named `eezo-auth`, and an application that carries nothing
        // loses nothing to it: `Guarded.public` carries no route, and two separately built routes
        // are never equal, so `distinct` removes nothing that was not the same instance twice.
        val rows = s"handwritten ++\n$derived"
        s"""    io.eezo.http.RouteTable(
           |      ($rows).distinct
           |    )""".stripMargin
      }

    val storeHelper = if (models.isEmpty) "" else storeFor(dbOnClasspath) + "\n"

    // Each helper follows the rows that call it, for the reason `storeFor` does: an uncalled
    // `private inline def` is what `-Wunused:all` reports, and a generated file has to compile
    // clean under the strictest options a user is entitled to turn on. `guardFor` is called by a
    // handwritten row directly, and by a derived row either directly or through `guardForModel`,
    // so any row at all is enough to emit it; `guardForModel` needs a derived row and the strict
    // reading both.
    val guardHelpers =
      if (routes.isEmpty && models.isEmpty) ""
      else {
        val perModel = if (authDeclared && models.nonEmpty) guardForModel + "\n" else ""
        guardFor(authDeclared) + "\n" + perModel
      }

    val helper = storeHelper + guardHelpers

    s"""// AUTO-GENERATED by eezo. Do not edit.
       |package io.eezo.generated
       |
       |object Routes {
       |
       |  /** Routes written by hand under src/main/scala/app/. */
       |$handwrittenBlock
       |
       |$helper  /** The table this application serves. One line per candidate model below: the compiler,
       |    * not the generator, decides which of them has a `Resource` and mounts the seven.
       |    */
       |  def table(): io.eezo.http.RouteTable = {
       |$body
       |  }
       |}
       |""".stripMargin
  }

  /** The one declaration the two arms differ by: an `inline def` that hands each derived row its
    * store.
    *
    * `inline` rather than a plain `def` because `summonFrom` is a compile-time construct: it has to
    * be expanded at the call site, where `A` is a concrete model and its companion is in scope, for
    * implicit search to have anything to find. The in-memory arm has no such need and stays
    * `inline` anyway, so that the two arms present the same signature to the rows that call them
    * and nothing but the body moves when a project gains a database.
    *
    * The `case _` in the db arm is not a formality. `eezo-db` on the classpath says only that the
    * `Table` type resolves, never that any one model derives an instance of it, so an application
    * with a database still mounts its `Table`-less models in memory. That is the same per-model
    * decision the generator could not make, made by the only party that can.
    */
  private def storeFor(dbOnClasspath: Boolean): String =
    if (dbOnClasspath)
      """  /** The store each derived model gets, picked by the compiler: a model whose companion
        |    * carries a `Table` is persisted through it, and every other one lives in memory for as
        |    * long as the process does.
        |    */
        |  private inline def storeFor[A]: io.eezo.core.Store[A] =
        |    scala.compiletime.summonFrom {
        |      case t: io.eezo.db.Table[A] => io.eezo.db.JdbcStore[A]()(using t)
        |      case _                      => io.eezo.http.InMemoryStore[A]()
        |    }
        |""".stripMargin
    else
      """  /** The store each derived model gets. Nothing on this application's classpath persists a
        |    * model, so every one of them lives in memory for as long as the process does.
        |    */
        |  private inline def storeFor[A]: io.eezo.core.Store[A] =
        |    io.eezo.http.InMemoryStore[A]()
        |""".stripMargin

  /** The lookup every mounted route goes through, in both readings of silence.
    *
    * The first arm is the same in both, and it is the whole point of emitting this unconditionally:
    * a `given Guarded[A]` that an application wrote is the declaration the table uses, whether or
    * not that application's build named `eezo-auth`. The alternative, reading the flag out here and
    * mounting the route bare when it is off, made `given Guarded[Post] = User.guard.required`
    * silently do nothing in every application that reaches auth through the umbrella or through a
    * `dependsOn` module, which is an admin page served to the public with nothing to say so.
    *
    * The fallback arm is the flag's only job. Strict: no `Guarded` in scope is a compile error
    * naming the type, which is the rule "in an application that has a guard, every route mounted
    * has said whether it is guarded; silence is an error". `scala.compiletime.error` rather than
    * letting implicit search fail on its own, because the message has to name the file the user
    * edits, and the type's own name is the only thing in the emitted file that points there.
    * Lenient: silence is [[io.eezo.http.Guarded.public]], which is what an application with nobody
    * to sign in has always been served.
    *
    * The name arrives as an `inline` parameter rather than being read off `A`, because
    * `compiletime.error` takes a constant and an inline `String` argument folds into one where a
    * type name would need a macro. It is the fully qualified name the generator already wrote into
    * the row above it, so the two cannot disagree. Only the strict helper takes it: a parameter the
    * lenient body never reads is the `unused explicit parameter` that `-Wunused:all` reports, in a
    * file the application's author cannot edit.
    *
    * `private[sbt]` for the reason `EezoPlugin.witness` is: `RouteGeneratorSuite` pins this text
    * against the copy the umbrella's `GeneratedGuardForSuite` compiles, and nothing a consuming
    * build sees can reach it.
    */
  private[sbt] def guardFor(authDeclared: Boolean): String =
    if (authDeclared)
      """  /** Who may reach this route. Every mounted route has to say, because this application's
        |    * build declares eezo-auth and so the application has a way of signing in.
        |    */
        |  private inline def guardFor[A](inline name: String): io.eezo.http.Guarded[A] =
        |    scala.compiletime.summonFrom {
        |      case g: io.eezo.http.Guarded[A] => g
        |      case _                          =>
        |        scala.compiletime.error(
        |          "no Guarded given for " + name + ", and this application's build declares " +
        |            "eezo-auth, so every mounted route has to say who may reach it. In its " +
        |            "companion, one of:\n" +
        |            "  given io.eezo.http.Guarded[T] = <yourGuard>.required\n" +
        |            "  given io.eezo.http.Guarded[T] = io.eezo.http.Guarded.public"
        |        )
        |    }
        |""".stripMargin
    else
      """  /** Who may reach this route. A `given Guarded` beside the model or the page is what the
        |    * table mounts it behind; this application's build declares no eezo-auth, so saying
        |    * nothing means anyone may reach it.
        |    */
        |  private inline def guardFor[A]: io.eezo.http.Guarded[A] =
        |    scala.compiletime.summonFrom {
        |      case g: io.eezo.http.Guarded[A] => g
        |      case _                          => io.eezo.http.Guarded.public[A]
        |    }
        |""".stripMargin

  /** The same lookup for a candidate model under the strict reading, which differs in one way that
    * matters. Under the lenient reading there is nothing to spare a model from, so the rows call
    * `guardFor` directly and this is not emitted at all.
    *
    * A candidate is any `case class` with a `derives` clause, and most of them mount nothing:
    * `examples/blog`'s `User` derives `Table` alone, so `Resource.routesOf` answers `Nil` for it.
    * Demanding a `Guarded` of a type that mounts no route would make the rule about the `derives`
    * keyword rather than about routes, and would put a declaration in the companion of every model
    * an application stores.
    *
    * The gate cannot live at the call site. `Resource.routesOf` already asks the compiler whether
    * there is a `Resource`, but an inline argument is expanded before the body it is passed to, so
    * `guardFor` would raise its error while `routesOf` was still deciding to throw the value away.
    * Asking the same question one level earlier, here, is what makes the answer reach the error.
    */
  private[sbt] val guardForModel: String =
    """  /** Who may reach a derived model's routes. A model with no `Resource` mounts nothing, so it
      |    * is not asked to declare anything.
      |    */
      |  private inline def guardForModel[A](inline name: String): io.eezo.http.Guarded[A] =
      |    scala.compiletime.summonFrom {
      |      case _: io.eezo.http.Resource[A] => guardFor[A](name)
      |      case _                           => io.eezo.http.Guarded.public[A]
      |    }
      |""".stripMargin

  /** The warnings for a `derives`-carrying `case class` that [[modelsIn]] cannot mount — the
    * silent-failure cases, each named for what it actually is. Found necessary on the first field
    * deploy, where an editor-indented model compiled, deployed, and mounted nothing.
    */
  def unscannableModels(relative: String, content: String): Seq[String] =
    if (!relative.endsWith(".scala")) Nil
    else {
      val (classes, colonStyle) = scanClasses(content)
      val at                    = "src/main/scala/" + relative

      classes.collect {
        case c if c.derives && mountablePath(c, colonStyle).isEmpty =>
          if (c.enclosing.isEmpty)
            s"$at: `case class ${c.name}` has a derives clause but is indented in a file that " +
              "nests by significant indentation, so the route generator cannot tell whether it " +
              "is top level. Unindent it, or give its enclosing scopes braces, to mount it."
          else {
            val culprit = c.enclosing
              .find(!_.exists(_._1 == "object"))
              .map(_.map { case (kind, name) => s"$kind $name" }.getOrElse("a block"))
              .getOrElse("a block")
            s"$at: `case class ${c.name}` has a derives clause but is nested inside $culprit, " +
              "which is not a stable path the route table can name. Move it to the top level or " +
              "into an object, or mount it explicitly with Resource.routesOf."
          }
      }
    }

  /** The one warning a text scan can decide: a file under `app/` that does not define the `def` its
    * name promises. Everything the compiler would say better is left to the compiler.
    */
  def missingDef(route: HandwrittenRoute, content: String): Option[String] = {
    val defName = route.target.split('.').last.replace("`", "")
    val defined = new Regex("def\\s+`?" + Regex.quote(defName) + "`?\\b").findFirstIn(content)
    if (defined.isDefined) None
    else
      Some(
        s"${route.source} defines no `def $defName`, so the route ${route.method} ${route.path} " +
          "will not compile"
      )
  }

  private def segment(directory: String): String =
    if (directory.startsWith("__")) "*" + directory.drop(2)
    else if (directory.startsWith("_")) ":" + directory.drop(1)
    else directory

  private def decapitalise(name: String): String =
    if (name.isEmpty) name else name.substring(0, 1).toLowerCase + name.substring(1)

  private def quoted(defName: String): String =
    if (Keywords.contains(defName)) "`" + defName + "`" else defName

  private def rank(segment: String): Int =
    if (segment.startsWith("*")) 2 else if (segment.startsWith(":")) 1 else 0

  private def compare(left: HandwrittenRoute, right: HandwrittenRoute): Int = {
    val leftSegments  = left.path.split('/').filter(_.nonEmpty).toVector
    val rightSegments = right.path.split('/').filter(_.nonEmpty).toVector

    val ranked = leftSegments
      .zip(rightSegments)
      .map { case (l, r) => rank(l) - rank(r) }
      .find(_ != 0)
      .getOrElse(0)

    if (ranked != 0) ranked
    else if (leftSegments.size != rightSegments.size) rightSegments.size - leftSegments.size
    else {
      val byPath = left.path.compareTo(right.path)
      if (byPath != 0) byPath else left.method.compareTo(right.method)
    }
  }
}
