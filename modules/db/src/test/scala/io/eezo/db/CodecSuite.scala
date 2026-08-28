package io.eezo.db

import io.eezo.core.Id

import io.eezo.db.support.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import java.util.UUID

/** Row codecs against a real driver. Was `Step1`/`Step2`: the hand-written `TableDef` and then the
  * derived one, both proving the same round-trip. Only the derived one remains — the hand-written
  * version was scaffolding for a macro that now exists.
  */
class CodecSuite extends PgSuite {

  private def widget(
      note: Option[String] = Some("note"),
      when: Option[LocalDate] = Some(LocalDate.of(2020, 1, 1)),
      tally: Option[Int] = Some(7),
      flagged: Option[Boolean] = Some(true)
  ) = Widget(
    id = Id.gen(),
    name = "a widget",
    count = 42,
    size = 9000000000L,
    active = true,
    price = BigDecimal("19.99"),
    external = UUID.randomUUID(),
    day = LocalDate.of(2026, 8, 20),
    // timestamptz is microsecond-precision; Instant is nanosecond. Truncating here keeps the
    // assertion about the codec rather than about the column's resolution.
    at = Instant.now().truncatedTo(ChronoUnit.MICROS),
    note = note,
    when = when,
    tally = tally,
    flagged = flagged
  )

  test("every scalar type survives insert and select") {
    create(Widgets)
    val w = widget()
    insert(Table[Widget], w)
    assertEquals(selectAll(Table[Widget]), List(w))
  }

  test("None round-trips through SQL NULL") {
    create(Widgets)
    val w = widget(note = None, when = None, tally = None, flagged = None)
    insert(Table[Widget], w)
    assertEquals(selectAll(Table[Widget]), List(w))
  }

  test("a false Boolean and a zero Int are not mistaken for NULL") {
    // `Column[Option[A]]` reads the value then asks `wasNull`. For Int and Boolean the
    // driver returns 0/false for NULL, so these two are the cases where that can go wrong.
    create(Widgets)
    val w = widget(tally = Some(0), flagged = Some(false))
    insert(Table[Widget], w)
    assertEquals(selectAll(Table[Widget]).head.tally, Some(0))
    assertEquals(selectAll(Table[Widget]).head.flagged, Some(false))
  }

  test("ids are minted app-side, before the row exists") {
    // DESIGN §3.8: no RETURNING, so a batch insert needs no round-trip per row.
    create(Widgets)
    val ws = List.fill(3)(widget())
    insert(Table[Widget], ws*)
    assertEquals(selectAll(Table[Widget]).map(_.id).toSet, ws.map(_.id).toSet)
    assertEquals(ws.map(_.id).distinct.size, 3)
  }

  test("bytea round-trips") {
    create(Blobs)
    val b = Blob(Id.gen(), Array[Byte](0, 1, 2, -1, 127))
    insert(Table[Blob], b)
    val out = selectAll(Table[Blob])
    assertEquals(out.size, 1)
    assert(out.head.payload.sameElements(b.payload), out.head.payload.toList.toString)
  }

  test("encode writes columns in the order insertSql names them") {
    // `offset` is a 1-based JDBC index and both sides derive it from the same column list;
    // a mismatch here shows up as a type error from the driver, not a wrong value.
    create(Widgets)
    val w = widget()
    insert(Table[Widget], w)
    assertEquals(selectAll(Table[Widget]).head.name, w.name)
    assertEquals(selectAll(Table[Widget]).head.count, w.count)
  }
}
