package io.eezo.http

import io.eezo.core.Parser

/** A Field is composed over core's Parser and adds only what a browser input needs.
  *
  * Every case here is a way the split could go wrong in silence: the strict core Boolean leaking
  * into a checkbox and refusing `on`, the lenient checkbox reading leaking out as a given, or a
  * Field passing for a Parser where a later Config summons one.
  */
class FieldSuite extends munit.FunSuite {

  test("a Field reads and is absent through the Parser it is composed over") {
    assert(Field[Int].parser eq summon[Parser[Int]])
    assertEquals(Field[Int].read(" 42 "), summon[Parser[Int]].read(" 42 "))
    assertEquals(Field[Int].absent, summon[Parser[Int]].absent)
  }

  test("a Boolean asked for as a Parser in http is core's strict one, never the checkbox") {
    assert(summon[Parser[Boolean]] ne Field.Checkbox)
    assertEquals(summon[Parser[Boolean]].read("on"), Left("is not true or false"))
  }

  test("importing every Field given still brings no Parser for a Boolean but core's") {
    import Field.given
    assert(summon[Field[Boolean]].parser eq Field.Checkbox)
    assert(summon[Parser[Boolean]] ne Field.Checkbox)
  }

  test("a checkbox reads on and any text but false as true, and false when absent") {
    val field = Field[Boolean]
    assert(field.parser eq Field.Checkbox)
    assertEquals(field.inputType, "checkbox")
    List("on", "ON", "yes", "2", "maybe", " x ").foreach(t =>
      assertEquals(field.read(t), Right(true), t)
    )
    List("false", "FALSE", "", "   ").foreach(t => assertEquals(field.read(t), Right(false), t))
    assertEquals(field.absent, Some(false))
    assert(field.checked(true))
    assert(!field.checked(false))
    assertEquals(field.show(true), "true")
    assertEquals(field.show(false), "false")
  }

  test("a Field is not a Parser, so it cannot stand where one is asked for") {
    val errors = compileErrors("val p: Parser[Int] = Field[Int]")
    assert(errors.contains("Found:    io.eezo.http.Field[Int]"), errors)
    assert(errors.contains("Required: io.eezo.core.Parser[Int]"), errors)
  }

  test("a Field cannot be extended, only built") {
    val errors = compileErrors(
      """new Field[Int]("number", _.toString) { override def read(text: String) = Right(0) }"""
    )
    assert(errors.contains("final"), errors)
  }

  test("an optional checkbox reads on through the inner checkbox, and keeps its input") {
    val field = Field[Option[Boolean]]
    assertEquals(field.read("on"), Right(Some(true)))
    assertEquals(field.read(""), Right(None))
    assertEquals(field.read(" "), Right(None))
    assertEquals(field.absent, Some(None))
    assertEquals(field.inputType, "checkbox")
    assert(field.checked(Some(true)))
    assert(!field.checked(Some(false)))
    assert(!field.checked(None))
    assertEquals(field.show(Some(true)), "true")
    assertEquals(field.show(None), "")
  }

  test("an optional field over a Field.of type needs no given Parser for it") {
    val field = Field[Option[FieldSuite.Isbn]]
    assertEquals(field.read(" 978 "), Right(Some(FieldSuite.Isbn("978"))))
    assertEquals(field.read("x"), Left("is not an isbn"))
    assertEquals(field.read(""), Right(None))
    assertEquals(field.inputType, "search")
    assertEquals(field.show(Some(FieldSuite.Isbn("978"))), "978")
  }

  test("Field.of reads with its function, has no absent value, and never ticks") {
    val field = Field[FieldSuite.Isbn]
    assertEquals(field.inputType, "search")
    assertEquals(field.read("978"), Right(FieldSuite.Isbn("978")))
    assertEquals(field.absent, None)
    assert(!field.checked(FieldSuite.Isbn("978")))
  }

  test("a type with no Field names itself and the Field.of line that gives it one") {
    val errors = compileErrors("Field[FieldSuite.Unknown]")
    assert(errors.contains("No Field instance for io.eezo.http.FieldSuite.Unknown"), errors)
    assert(errors.contains("Field.of(\"text\")(_.toString)(s => ...)"), errors)
  }
}

object FieldSuite {

  final case class Isbn(value: String)

  object Isbn {
    given Field[Isbn] = Field.of[Isbn]("search")(_.value) { text =>
      if (text.trim.forall(_.isDigit) && text.trim.nonEmpty) Right(Isbn(text.trim))
      else Left("is not an isbn")
    }
  }

  final case class Unknown(value: String)
}
