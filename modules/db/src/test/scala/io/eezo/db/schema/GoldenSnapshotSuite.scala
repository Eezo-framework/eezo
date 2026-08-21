package io.eezo.db.schema

import io.eezo.db.internal.SnapshotJson
import io.eezo.db.support.Library

import munit.FunSuite

import scala.io.Source

/** The regression net for every future change to derivation (BACKLOG item 14).
  *
  * `golden-library.json` is committed and asserted against. It is **not** regenerated when it
  * fails. A failure means either derivation changed — in which case the diff below is exactly the
  * review you want — or it broke, in which case regenerating would erase the only evidence.
  */
class GoldenSnapshotSuite extends FunSuite {

  private def golden: String = {
    val s = Source.fromInputStream(getClass.getResourceAsStream("/golden-library.json"), "UTF-8")
    try s.mkString.trim
    finally s.close()
  }

  test("the derived snapshot matches the committed golden file") {
    assertNoDiff(Library.snapshot.render, golden)
  }

  test("the snapshot round-trips through its own JSON") {
    // SnapshotJson.parse is the third producer of a SchemaSnap (DESIGN §2); if it disagrees
    // with the emitter, `freeze` diffs against a schema nobody has.
    assertEquals(SnapshotJson.parse(Library.snapshot.render), Library.snapshot)
  }

  test("the golden file parses to the same schema the code derives") {
    assertEquals(SnapshotJson.parse(golden), Library.snapshot)
  }

  test("rendering is deterministic") {
    assertEquals(Library.snapshot.render, Library.snapshot.render)
  }
}
