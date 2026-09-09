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
  */
final case class HandwrittenRoute(method: String, path: String, target: String, source: String)

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
  private val PackageClause: Regex = new Regex("(?m)^package\\s+((?!object\\b)[A-Za-z_][\\w.]*)")

  /** A top level case class, at column zero. */
  private val CaseClass: Regex =
    new Regex("(?m)^(?:final\\s+)?case class\\s+([A-Za-z_][A-Za-z0-9_]*)")

  /** Where one declaration's text stops: the next top level declaration of any kind. */
  private val Declaration: Regex =
    new Regex(
      "(?m)^(?:final\\s+|sealed\\s+|abstract\\s+|open\\s+)*(?:case\\s+)?(?:class|object|trait|enum|type|given|val|def)\\b"
    )

  private val Derives: Regex = new Regex("\\bderives\\b")

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
      val target  = (Vector("app") ++ dirs ++ Vector(fileName, quoted(defName))).mkString(".")

      Some(
        HandwrittenRoute(
          method = method,
          path = path,
          target = target,
          source = "src/main/scala/app/" + relative
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

  /** Every top level `case class` in one file that carries a `derives` clause.
    *
    * Textual, and deliberately permissive rather than accurate. A false positive is free: the
    * emitted `Resource.routesOf[X]` resolves to `Nil` when `X` has no instance, so a `derives`
    * inside a comment costs a dead line rather than a wrong route, which is why none of skiff's
    * comment stripping, paren matching or fixed-width lookahead is here. A false negative is the
    * only real failure, so the window a `derives` may appear in runs to the next top level
    * declaration rather than to a character count, which is what lets a multi-line constructor
    * carry its clause on a line of its own.
    *
    * Only declarations at column zero are candidates. A nested one is skipped because its name is
    * `Outer.Inner`, which this scan cannot see and which `package.Inner` would name wrongly: the
    * generated file would then fail to compile, and a generator that can break a build it was meant
    * to serve is worse than one that misses a shape nobody writes.
    */
  def modelsIn(relative: String, content: String): Seq[ModelCandidate] =
    if (!relative.endsWith(".scala")) Nil
    else {
      val prefix = PackageClause.findAllMatchIn(content).map(_.group(1)).mkString(".")
      val source = "src/main/scala/" + relative

      val starts = Declaration.findAllMatchIn(content).map(_.start).toVector
      CaseClass
        .findAllMatchIn(content)
        .map { declaration =>
          val next = starts.find(_ > declaration.start).getOrElse(content.length)
          val body = content.substring(declaration.start, next)
          val name = declaration.group(1)
          (name, Derives.findFirstIn(body).isDefined)
        }
        .collect { case (name, true) =>
          ModelCandidate(if (prefix.isEmpty) name else s"$prefix.$name", source)
        }
        .toVector
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
    */
  def render(
      routes: Seq[HandwrittenRoute],
      models: Seq[ModelCandidate],
      dbOnClasspath: Boolean
  ): String = {
    val handwritten =
      if (routes.isEmpty) "    // no files under src/main/scala/app/"
      else
        sortRoutes(routes)
          .map { route =>
            s"""    // from ${route.source}
               |    io.eezo.http.Route.Http(
               |      io.eezo.http.Method.${route.method},
               |      io.eezo.http.PathPattern.parse("${route.path}"),
               |      req => ${route.target}(req)
               |    )""".stripMargin
          }
          .mkString(",\n")

    val body =
      if (models.isEmpty)
        "    io.eezo.http.RouteTable(handwritten)"
      else {
        val derived = models
          .sortBy(_.fqn)
          .map { model =>
            s"""      // from ${model.source}
               |      io.eezo.http.Resource.routesOf[${model.fqn}](storeFor[${model.fqn}])""".stripMargin
          }
          .mkString(" ++\n")

        s"""    io.eezo.http.RouteTable(
           |      handwritten ++
           |$derived
           |    )""".stripMargin
      }

    val helper = if (models.isEmpty) "" else storeFor(dbOnClasspath) + "\n"

    s"""// AUTO-GENERATED by eezo. Do not edit.
       |package io.eezo.generated
       |
       |object Routes {
       |
       |  /** Routes written by hand under src/main/scala/app/. */
       |  private val handwritten: Seq[io.eezo.http.Route] = Seq(
       |$handwritten
       |  )
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

  /** An indented `case class` at column > 0. */
  private val IndentedCaseClass: Regex =
    new Regex("(?m)^[ \\t]+(?:final\\s+)?case class\\s+([A-Za-z_][A-Za-z0-9_]*)")

  /** The mounting failure `modelsIn`'s column-zero rule produces in silence: a `case class`
    * carrying a `derives` clause at a column the scan does not read. An indented top-level model is
    * legal Scala that mounts nothing, and a nested one cannot be mounted by name at all — either
    * way the user wrote `derives` and got no routes, and the first field deploy of `eezo deploy`
    * spent its debugging time on exactly this. The scan cannot tell the two cases apart, so the
    * warning names them both.
    */
  def unscannableModels(relative: String, content: String): Seq[String] =
    if (!relative.endsWith(".scala")) Nil
    else {
      val starts = Declaration.findAllMatchIn(content).map(_.start).toVector
      IndentedCaseClass
        .findAllMatchIn(content)
        .toVector
        .flatMap { declaration =>
          val next = starts.find(_ > declaration.start).getOrElse(content.length)
          val body = content.substring(declaration.start, next)
          if (Derives.findFirstIn(body).isDefined)
            Some(
              s"src/main/scala/$relative: `case class ${declaration.group(1)}` has a derives " +
                "clause but does not start at column zero, so the route generator cannot mount " +
                "it. A top-level model must be unindented; a nested model is never mounted."
            )
          else None
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
