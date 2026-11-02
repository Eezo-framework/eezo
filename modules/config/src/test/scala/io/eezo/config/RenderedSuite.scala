package io.eezo.config

import com.typesafe.config.ConfigFactory

/** The substitutions of a file are found by scanning how the library renders an unresolved value,
  * so each shape a value can take in HOCON is pinned here against what the scan reads from it.
  */
class RenderedSuite extends munit.FunSuite {

  private def names(hocon: String): List[(String, String)] =
    Config[RenderedSuite.Empty]
      .names(ConfigFactory.parseString(hocon))
      .map(n => n.path -> n.variable)
      .toList

  private def stated(hocon: String, path: String): Boolean =
    Config.literal(ConfigFactory.parseString(hocon).root(), path.split('.').toList).isDefined

  test("a merge of a literal and two substitutions lists the variables in file order") {
    assertEquals(
      names("""
        a = "x"
        a = ${?FIRST}
        a = ${?SECOND}
      """),
      List("a" -> "FIRST", "a" -> "SECOND")
    )
  }

  test("a concatenation lists every substitution in it and counts its text as stated") {
    val hocon = """a = "https://"${?HOST}":"${?PORT}"""
    assertEquals(names(hocon), List("a" -> "HOST", "a" -> "PORT"))
    assert(stated(hocon, "a"))
  }

  test("text that holds a dollar and a brace inside quotes is text, not a substitution") {
    val hocon = "a = \"${NOT_A_VARIABLE}\""
    assertEquals(names(hocon), Nil)
    assert(stated(hocon, "a"))
  }

  test(
    "a substitution of a dotted path the file does not state is one variable, as the resolver sees it"
  ) {
    assertEquals(names("a = ${?shop.url}"), List("a" -> "shop.url"))
  }

  test("a quoted key in a substituted path is handed over as the library renders it") {
    assertEquals(names("""a = ${?"odd}key"}"""), List("a" -> "\"odd}key\""))
  }

  test("a substitution alone is not stated, and neither is null") {
    assert(!stated("a = ${?A}", "a"))
    assert(!stated("a = null", "a"))
    assert(stated("a = 1", "a"))
  }

  test("null merged with a substitution is no text either") {
    assert(!stated("a = null\na = ${?A}", "a"))
    assert(stated("a = 1\na = ${?A}", "a"))
  }

  test("a leaf of a section that inherits through a reference is found by the walk checked uses") {
    val root = ConfigFactory
      .parseString("""
      base { region = "eu" }
      keys = ${base}
      keys { secret = ${?KEYS_SECRET} }
    """)
      .root()
    assert(Config.at(root, List("keys", "secret")).isDefined)
  }

  test("text that a section inherits beside a reference is no literal of the section's leaves") {
    val hocon = """
      base { secret = ${?KEYS_SECRET} }
      keys = ${base} { region = "us" }
    """
    assert(!stated(hocon, "keys.secret"))
  }

  test("a substitution of a path the file states is a reference inside the file, not a variable") {
    assertEquals(names("b { c = 1 }\na = ${b.c}"), Nil)
  }

  test("paths are listed depth first, siblings by the line they are written on") {
    assertEquals(
      names("""
        z = ${?Z}
        s {
          y = ${?Y}
          x = ${?X}
        }
        a = ${?A}
      """),
      List("z" -> "Z", "s.y" -> "Y", "s.x" -> "X", "a" -> "A")
    )
  }
}

object RenderedSuite {

  /** A class with no fields, so every substitution of the file feeds a path no field reads. */
  final case class Empty() derives Config
}
