package io.eezo.config

import io.eezo.core.Redacted

/** The compile time check against a file. The macro opens the path through the compiler's JVM,
  * which sbt starts at the checkout, so a path relative to the checkout reaches the fixture. A path
  * that reaches nothing counts as an empty file, so every refusal asserted here quotes something
  * only a parsed file can produce.
  */
class CheckedSuite extends munit.FunSuite {

  test("a file that states and feeds every required value is accepted") {
    assertNoDiff(
      compileErrors(
        """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-good.conf")"""
      ),
      ""
    )
  }

  test("a required value the file neither states nor feeds is refused at compile time") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-wrong.conf")"""
    )
    assert(
      errors.contains(
        "application.conf neither states `name` nor feeds it from a variable, and `name` has no " +
          "default. Write `name = ${?NAME}` in the file, or give the field a default."
      ),
      errors
    )
    assert(
      errors.contains("App cannot be read from application.conf, for the reasons above."),
      errors
    )
  }

  test("a required value the file sets to null is refused, as the read counts null as absent") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-null.conf")"""
    )
    assertNoDiff(
      errors.linesIterator.filter(_.contains("application.conf neither")).mkString("\n").trim,
      "error: application.conf neither states `name` nor feeds it from a variable, and `name` " +
        "has no default. Write `name = ${?NAME}` in the file, or give the field a default."
    )
  }

  test("a Redacted stated as a literal is refused with its line, never its text") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-wrong.conf")"""
    )
    assert(
      errors.contains(
        "application.conf states `keys.secret` as a literal at line 3, and `secret` is a " +
          "Redacted. A secret reaches the file only through a variable: " +
          "`secret = ${?KEYS_SECRET}`."
      ),
      errors
    )
    assert(!errors.contains("sk_live_committed"), errors)
  }

  test("a section stated as one value is refused, and the fix is a block") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-section.conf")"""
    )
    assert(
      errors.contains(
        "application.conf states `keys` as a value at line 3, and `keys` is a section: write " +
          "`keys { ... }`."
      ),
      errors
    )
    assert(!errors.contains("`keys.secret`"), errors)
  }

  test("a section that inherits through a reference and feeds its secret is accepted") {
    assertNoDiff(
      compileErrors(
        """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-inherited.conf")"""
      ),
      ""
    )
  }

  test("a section stated as a reference to a block is no value, and is accepted") {
    assertNoDiff(
      compileErrors(
        """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-reference.conf")"""
      ),
      ""
    )
  }

  test("text a section joins to an inherited block is no literal of its secret") {
    assertNoDiff(
      compileErrors(
        """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-concatenated.conf")"""
      ),
      ""
    )
  }

  test("a secret committed once and copied to the Redacted path by a reference is refused") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-literal-scalar.conf")"""
    )
    assert(
      errors.contains(
        "application.conf states `keys.secret` as a literal at line 3, and `secret` is a " +
          "Redacted. A secret reaches the file only through a variable: " +
          "`secret = ${?KEYS_SECRET}`."
      ),
      errors
    )
    assert(!errors.contains("sk_live_committed"), errors)
  }

  test("a secret committed in a block that a section inherits is refused, at the block's line") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-literal-section.conf")"""
    )
    assert(
      errors.contains(
        "application.conf states `keys.secret` as a literal at line 4, and `secret` is a " +
          "Redacted. A secret reaches the file only through a variable: " +
          "`secret = ${?KEYS_SECRET}`."
      ),
      errors
    )
    assert(!errors.contains("sk_live_committed"), errors)
  }

  test("a section that inherits a block with no secret leaves the required secret unfed") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-reference-missing.conf")"""
    )
    assert(
      errors.contains(
        "application.conf neither states `keys.secret` nor feeds it from a variable, and " +
          "`secret` has no default. Write `secret = ${?KEYS_SECRET}` under `keys` in the file, " +
          "or give the field a default."
      ),
      errors
    )
  }

  test("a section type at two paths is refused, by the file check and by the bare root alike") {
    val message =
      "`Keys` is the section at `a` and again at `b`. A section is installed and read by its " +
        "type, so each type appears once in the tree. Give `b` a case class of its own."
    val checked = compileErrors(
      """Config.checked[CheckedSuite.Twice]("modules/config/src/test/resources/checked-good.conf")"""
    )
    assert(checked.contains(message), checked)
    val bare = compileErrors("Config.root[CheckedSuite.Twice]")
    assert(bare.contains(message), bare)
  }

  test("the unit the plugin writes compiles against a good file, as ConfigFileSuite pins it") {
    assertNoDiff(
      compileErrors(
        """
object ConfigFile extends io.eezo.config.Config.Root.Generated {
  val root: io.eezo.config.Config.Root[io.eezo.config.CheckedSuite.App] =
    io.eezo.config.Config.checked[io.eezo.config.CheckedSuite.App]("modules/config/src/test/resources/checked-good.conf")
}
"""
      ),
      ""
    )
  }

  test("a file that does not parse is refused with its line, never its text") {
    val errors = compileErrors(
      """Config.checked[CheckedSuite.App]("modules/config/src/test/resources/checked-unparsable.conf")"""
    )
    assert(
      errors.contains("application.conf does not parse at line 4: fix its syntax there."),
      errors
    )
    assert(!errors.contains("ConfigException"), errors)
    assert(!errors.contains("sk_live_committed"), errors)
    assert(!errors.contains("secret"), errors)
  }

  test("the root with no file compiles for a class the file check would refuse") {
    assertNoDiff(compileErrors("Config.root[CheckedSuite.App]"), "")
    assertEquals(Config.root[CheckedSuite.App].config, Config[CheckedSuite.App])
  }

  test("the root accepts a field whose Parser only the class's own scope imports") {
    assertNoDiff(compileErrors("Config.root[CheckedSuite.Scoped.Timed]"), "")
  }

  test("the root refuses a case class field whose Config lives outside its companion") {
    val errors = compileErrors("Config.root[CheckedSuite.Imported.Two]")
    assert(
      errors.contains(
        "`Sub` at field `x` is a case class, and the root cannot tell a section from a value: " +
          "neither Config[Sub] nor Parser[Sub] is in scope here. Put the given in the companion " +
          "of `Sub`, or write `derives Config` on it."
      ),
      errors
    )
  }
}

object CheckedSuite {

  final case class Keys(secret: Redacted, region: String = "eu") derives Config

  final case class App(name: String, keys: Keys, banner: Option[String]) derives Config

  final case class Twice(a: Keys, b: Keys) derives Config

  object Givens {
    given io.eezo.core.Parser[java.time.Duration] = text =>
      scala.util.Try(java.time.Duration.parse(text)).toOption.toRight("is not a duration")
  }

  object Scoped {
    import Givens.given
    final case class Timed(timeout: java.time.Duration) derives Config
  }

  final case class Sub(a: Keys)

  object SubGivens {
    given Config[Sub] = Config.derived
  }

  object Imported {
    import SubGivens.given
    final case class Two(x: Sub, y: Keys) derives Config
  }
}
