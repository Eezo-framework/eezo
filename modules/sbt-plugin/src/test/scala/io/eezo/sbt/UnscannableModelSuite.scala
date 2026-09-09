package io.eezo.sbt

import munit.FunSuite

/** The warning for a `derives`-carrying case class the scan cannot mount — found the hard way on
  * the first field deploy, where an editor-indented model compiled, deployed, and mounted nothing.
  */
class UnscannableModelSuite extends FunSuite {

  test("an indented case class with a derives clause warns, naming the class") {
    val content =
      """package models
        |
        |  case class Book(
        |      id: Long
        |  ) derives Table
        |""".stripMargin
    val warnings = RouteGenerator.unscannableModels("models/Book.scala", content)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("case class Book"))
    assert(warnings.head.contains("column zero"))
    // and the scan itself finds nothing, which is the failure being warned about
    assertEquals(RouteGenerator.modelsIn("models/Book.scala", content), Seq.empty[ModelCandidate])
  }

  test("a column-zero model does not warn, and an indented class without derives does not either") {
    val mounted = "package models\n\ncase class Book(id: Long) derives Table\n"
    assertEquals(RouteGenerator.unscannableModels("models/Book.scala", mounted), Seq.empty[String])

    val plain = "object Outer {\n  case class Inner(id: Long)\n}\n"
    assertEquals(RouteGenerator.unscannableModels("models/Outer.scala", plain), Seq.empty[String])
  }
}
