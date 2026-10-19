package site

import munit.FunSuite

/** The module cards on the API page, held against the API reference itself. The reference is what
  * `sbt unidoc` generated from the published modules, so neither side here is a list spelled a
  * second time: a module added to the build and left out of `Docs.Modules` fails the site build,
  * once the docs are present. Without them the suite is skipped, as every suite that needs them is,
  * and `ApiDocs` refuses the absence under CI.
  */
class ModulesSuite extends FunSuite {

  /** The packages the reference's `io.eezo` page lists as its members: the anchors scaladoc marks
    * `documentableName`, lowercase, so a class at that level such as `EezoApp` is not one. A
    * package with no members of its own is collapsed into its child, `eezo/json/upickle.html` for
    * `io.eezo.json.upickle`, which is why the path may hold a slash.
    */
  private def referenced: Set[String] = {
    val page = Assets
      .readUnder("api", "io/eezo.html")
      .map(new String(_, "UTF-8"))
      .getOrElse(fail("the API docs hold no io/eezo.html"))
    """<a href="eezo/([a-z0-9/]+)\.html"[^>]*class="documentableName""".r
      .findAllMatchIn(page)
      .map(m => "io.eezo." + m.group(1).replace('/', '.'))
      .toSet
  }

  private val skip = "run `sbt unidoc` at the repository root to test the module cards"

  test("every package under io.eezo in the API reference has a card at or under it") {
    assume(ApiDocs.present, skip)
    val packages = referenced
    assert(packages.nonEmpty, "the io.eezo page links no package: has scaladoc changed shape?")
    val cards     = Docs.Modules.map(_._1)
    val uncovered =
      packages.filterNot(pkg => cards.exists(c => c == pkg || c.startsWith(pkg + ".")))
    assert(
      uncovered.isEmpty,
      s"packages in the API reference with no card in Docs.Modules: ${uncovered.toVector.sorted
          .mkString(", ")}. Add a row there, and check the tables in README.md and docs/explanation/layers.md too"
    )
  }

  test("every card links a page the API reference wrote") {
    assume(ApiDocs.present, skip)
    Docs.Modules.foreach { case (pkg, artifact, _) =>
      val file = pkg.replace('.', '/') + ".html"
      assert(
        Assets.readUnder("api", file).isDefined,
        s"the $artifact card points at /api/$file, which the reference did not write"
      )
    }
  }
}
