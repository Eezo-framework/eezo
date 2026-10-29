package io.eezo.core

import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

import io.eezo.core.html.Url

/** The core readings every type with a Parser shares, pinned where a form never sees them.
  *
  * A Parser is what a later Config reads the environment with, so its messages and its blanks have
  * to be right without a browser in the loop. The form side of the same readings is FormSuite's.
  */
class ParserSuite extends munit.FunSuite {

  test("a String reads as itself, the empty text included, and has no absent value") {
    val p = summon[Parser[String]]
    assertEquals(p.read(" hex "), Right(" hex "))
    assertEquals(p.read(""), Right(""))
    assertEquals(p.absent, None)
  }

  test("Int, Long and Double trim, read a number, and refuse anything else as not a number") {
    assertEquals(summon[Parser[Int]].read(" 42 "), Right(42))
    assertEquals(summon[Parser[Long]].read("9000000000"), Right(9000000000L))
    assertEquals(summon[Parser[Double]].read(" 2.5"), Right(2.5))
    assertEquals(summon[Parser[Int]].read("4x"), Left("is not a number"))
    assertEquals(summon[Parser[Long]].read(""), Left("is not a number"))
    assertEquals(summon[Parser[Double]].read("half"), Left("is not a number"))
    assertEquals(summon[Parser[Int]].absent, None)
    assertEquals(summon[Parser[Long]].absent, None)
    assertEquals(summon[Parser[Double]].absent, None)
  }

  private val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")

  test("a UUID trims and reads, and anything else is not an id") {
    assertEquals(summon[Parser[UUID]].read(s" $uuid "), Right(uuid))
    assertEquals(summon[Parser[UUID]].read("nope"), Left("is not an id"))
    assertEquals(summon[Parser[UUID]].absent, None)
  }

  test("dates and times trim and read, and say which of the two they are not") {
    assertEquals(summon[Parser[LocalDate]].read(" 2026-10-27 "), Right(LocalDate.of(2026, 10, 27)))
    assertEquals(
      summon[Parser[LocalDateTime]].read("2026-10-27T10:15"),
      Right(LocalDateTime.of(2026, 10, 27, 10, 15))
    )
    assertEquals(
      summon[Parser[Instant]].read(" 2026-10-27T10:15:00Z"),
      Right(Instant.parse("2026-10-27T10:15:00Z"))
    )
    assertEquals(summon[Parser[LocalDate]].read("tomorrow"), Left("is not a date"))
    assertEquals(summon[Parser[LocalDateTime]].read("2026-10-27"), Left("is not a date and time"))
    assertEquals(summon[Parser[Instant]].read("now"), Left("is not a date and time"))
    assertEquals(summon[Parser[LocalDate]].absent, None)
    assertEquals(summon[Parser[LocalDateTime]].absent, None)
    assertEquals(summon[Parser[Instant]].absent, None)
  }

  case class Widget()

  test("an Id reads the text a UUID reads, and is not an id where a UUID is not") {
    assertEquals(summon[Parser[Id[Widget]]].read(s" $uuid"), Right(Id[Widget](uuid)))
    assertEquals(summon[Parser[Id[Widget]]].read("nope"), Left("is not an id"))
    assertEquals(summon[Parser[Id[Widget]]].absent, None)
  }

  test("an Option reads blank text as None, delegates the rest, and is None when absent") {
    assertEquals(summon[Parser[Option[Int]]].read(""), Right(None))
    assertEquals(summon[Parser[Option[Int]]].read("  "), Right(None))
    assertEquals(summon[Parser[Option[Int]]].read(" 7"), Right(Some(7)))
    assertEquals(summon[Parser[Option[Int]]].read("seven"), Left("is not a number"))
    assertEquals(summon[Parser[Option[Int]]].absent, Some(None))
  }

  test("a Boolean reads its six spellings in any case, and false when absent") {
    val p = summon[Parser[Boolean]]
    List("true", "TRUE", "1", "yes", "Yes", " yes ").foreach(t =>
      assertEquals(p.read(t), Right(true), t)
    )
    List("false", "False", "0", "no", "nO").foreach(t => assertEquals(p.read(t), Right(false), t))
    assertEquals(p.absent, Some(false))
  }

  test("a Boolean refuses on, blank, 2 and maybe with a predicate about the value") {
    val p = summon[Parser[Boolean]]
    List("on", "", "2", "maybe").foreach(t =>
      assertEquals(p.read(t), Left("is not true or false"), t)
    )
  }

  test("a Url reads as an Absolute kept verbatim inside its surrounding blanks") {
    val p = summon[Parser[Url]]
    assertEquals(p.read(" http://localhost:8080 "), Right(Url.Absolute("http://localhost:8080")))
    assertEquals(
      p.read("https://eezo.io//a?b=c#d"),
      Right(Url.Absolute("https://eezo.io//a?b=c#d"))
    )
    assertEquals(p.absent, None)
  }

  test("a Url refuses blank text and text that is not a URI") {
    val p = summon[Parser[Url]]
    List("", "   ", "http://local host", "http://[oops").foreach(t =>
      assertEquals(p.read(t), Left("is not a url"), t)
    )
  }

  test("a type with no Parser names itself and the one line that gives it one") {
    val errors = compileErrors("summon[Parser[ParserSuite.Unknown]]")
    assert(errors.contains("No Parser for io.eezo.core.ParserSuite.Unknown"), errors)
    assert(
      errors.contains(
        """given Parser[io.eezo.core.ParserSuite.Unknown] = text => ... // Right(value), or Left("is not a ...")"""
      ),
      errors
    )
  }
}

object ParserSuite {
  final case class Unknown(value: String)
}
