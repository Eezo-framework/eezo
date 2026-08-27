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
  * not define the `def` its name promises. Shadowing and orphaned actions need typed instances and
  * warn at boot instead.
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
    * Handwritten first is what makes decision 18's precedence a property of the emitted text rather
    * than of a merge step somebody can get wrong, since dispatch is first match in table order.
    *
    * `table` is a `def` that mints the store, so no user ever writes the word `Store`: the type is
    * a throwaway that a real query runtime replaces within weeks, and a `given Store` line in every
    * example is a line eezo would teach and then have to un-teach. Each call gets a store of its
    * own, which is what makes a test that calls `Routes.table()` start from an empty world.
    */
  def render(routes: Seq[HandwrittenRoute], models: Seq[ModelCandidate]): String = {
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
               |      io.eezo.http.Resource.routesOf[${model.fqn}](store)""".stripMargin
          }
          .mkString(" ++\n")

        s"""    val store = io.eezo.http.Store.inMemory()
           |    io.eezo.http.RouteTable(
           |      handwritten ++
           |$derived
           |    )""".stripMargin
      }

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
       |  /** The table this application serves. One line per candidate model below: the compiler,
       |    * not the generator, decides which of them has a `Resource` and mounts the seven.
       |    */
       |  def table(): io.eezo.http.RouteTable = {
       |$body
       |  }
       |}
       |""".stripMargin
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
