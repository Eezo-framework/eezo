package io.eezo.config

/** What `derives Config` refuses, and the words it refuses with: each message names the class or
  * the field, since the author reads it at the line of the derivation.
  */
class DerivationSuite extends munit.FunSuite {

  test("a field that is neither a Parser type nor a section does not compile, naming the field") {
    val errors = compileErrors(
      "case class Unreadable(port: Int, widget: DerivationSuite.Widget) derives Config"
    )
    assert(
      errors.contains(
        """|No Parser for io.eezo.config.DerivationSuite.Widget at field `widget`, and io.eezo.config.DerivationSuite.Widget does not derive Config either.
           |A field is a value with a Parser in scope:
           |  given Parser[io.eezo.config.DerivationSuite.Widget] = text => ... // Right(value), or Left("is not a ...")
           |or a section, a case class that derives Config.
           |""".stripMargin
      ),
      errors
    )
    assert(errors.contains("Unreadable cannot derive Config, for the reasons above."), errors)
    assert(!errors.contains("`port`"), errors)
  }

  test("an Option field whose inner type has no Parser asks for the inner Parser") {
    val errors =
      compileErrors(
        "case class MaybeUnreadable(widget: Option[DerivationSuite.Widget]) derives Config"
      )
    assert(
      errors.contains("No Parser for io.eezo.config.DerivationSuite.Widget at field `widget`"),
      errors
    )
    assert(!errors.contains("Parser[scala.Option"), errors)
  }

  test("a section with a default, or an Option of a section, does not compile") {
    val defaulted = compileErrors(
      "case class WithDefault(s: DerivationSuite.Part = DerivationSuite.Part(1)) derives Config"
    )
    assert(
      defaulted.contains(
        "Field `s` is a section, and a section has no default: its fields have theirs."
      ),
      defaulted
    )
    val optional =
      compileErrors("case class WithOption(s: Option[DerivationSuite.Part]) derives Config")
    assert(
      optional.contains(
        "Field `s` is an Option of a section, and a section is never absent: its fields have " +
          "their own defaults and Options."
      ),
      optional
    )
  }

  test("a class that cannot be read field by field is refused instead of miscompiled") {
    val generic = compileErrors("case class Two[T](t: T) derives Config")
    assert(
      generic.contains("Two has type parameters, and a Config is read from one fixed file."),
      generic
    )
    val local = compileErrors("case class Here(a: Int = 1) derives Config")
    assert(
      local.contains(
        "Here is defined inside a method and has a default, which a Config cannot read " +
          "there. Move it to an object or the top level."
      ),
      local
    )
  }

  test("a case object is refused, since only a case class has fields to read") {
    val errors = compileErrors("case object Solo; Config.derived[Solo.type]")
    assert(errors.contains("Solo is not a case class, and only one can derive Config."), errors)
  }

  test("a field spelled with the framework's prefix compiles, since the file names the variables") {
    assertNoDiff(compileErrors("case class Named(eezoThing: String) derives Config"), "")
  }
}

object DerivationSuite {

  final case class Widget(name: String)

  final case class Part(n: Int) derives Config
}
