package io.eezo.sbt

import munit.FunSuite

/** The structural model scan: depth decides top-level, the object stack names the nested, and only
  * what structure genuinely cannot answer warns. The first two cases are the two failures of the
  * column-zero rule this scan replaced — an editor-indented top-level model that deployed and
  * mounted nothing (found in the field), and a column-zero class nested in braces that would have
  * minted a name that does not compile.
  */
class UnscannableModelSuite extends FunSuite {

  private def candidates(content: String): Seq[String] =
    RouteGenerator.modelsIn("models/M.scala", content).map(_.fqn)

  private def warnings(content: String): Seq[String] =
    RouteGenerator.unscannableModels("models/M.scala", content)

  test("an indented top-level case class in a brace-style file mounts — depth, not column") {
    val content =
      """package models
        |
        |  case class Book(
        |      id: Long
        |  ) derives Table
        |""".stripMargin
    assertEquals(candidates(content), Seq("models.Book"))
    assertEquals(warnings(content), Seq.empty[String])
  }

  test("a column-zero case class nested in braces is not top level — the other column failure") {
    val content =
      """package models
        |
        |object Registry {
        |case class Widget(id: Long) derives Form
        |}
        |""".stripMargin
    // Nested in an object: mounted through the path, never as the wrong bare name.
    assertEquals(candidates(content), Seq("models.Registry.Widget"))
  }

  test("objects nest arbitrarily deep, and the path follows") {
    val content =
      """package models
        |
        |object A { object B {
        |  case class C(id: Long) derives Form
        |} }
        |""".stripMargin
    assertEquals(candidates(content), Seq("models.A.B.C"))
  }

  test("nested inside a class there is no stable path: no candidate, one warning naming it") {
    val content =
      """package models
        |
        |class Holder {
        |  case class Widget(id: Long) derives Form
        |}
        |""".stripMargin
    assertEquals(candidates(content), Seq.empty[String])
    assertEquals(warnings(content).size, 1)
    assert(warnings(content).head.contains("class Holder"))
    assert(warnings(content).head.contains("Resource.routesOf"))
  }

  test("an indented depth-zero class in a significant-indentation file is ambiguous: warns") {
    val content =
      """package models
        |
        |object Container:
        |  case class Widget(id: Long) derives Form
        |""".stripMargin
    assertEquals(candidates(content), Seq.empty[String])
    assertEquals(warnings(content).size, 1)
    assert(warnings(content).head.contains("significant indentation"))
  }

  test("braces inside strings and comments do not shift the depth") {
    val content =
      """package models
        |
        |object Registry {
        |  val decoration = "{{{"
        |  // a comment with a stray }
        |  /* and a block one { */
        |  case class Widget(id: Long) derives Form
        |}
        |
        |case class Book(id: Long) derives Table
        |""".stripMargin
    assertEquals(candidates(content), Seq("models.Registry.Widget", "models.Book"))
    assertEquals(warnings(content), Seq.empty[String])
  }

  test("a derives in a comment or on an unrelated block does not mint a candidate") {
    val content =
      """package models
        |
        |// case class Ghost(id: Long) derives Form
        |case class Plain(id: Long)
        |
        |object Config {
        |  val note = "derives"
        |}
        |""".stripMargin
    assertEquals(candidates(content), Seq.empty[String])
    assertEquals(warnings(content), Seq.empty[String])
  }

  test("a whole file indented by an editor — package line included — still mounts") {
    val content =
      """  package models
        |
        |  import io.eezo.core.Id
        |
        |  case class Book(
        |      id: Id[Book],
        |      title: String
        |  ) derives Table,
        |        Form,
        |        Resource
        |""".stripMargin
    assertEquals(candidates(content), Seq("models.Book"))
    assertEquals(warnings(content), Seq.empty[String])
  }
}
