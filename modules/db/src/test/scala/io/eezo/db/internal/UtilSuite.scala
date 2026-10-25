package io.eezo.db.internal

import munit.FunSuite

class UtilSuite extends FunSuite {
  import util.*

  test("canonicalCheck erases the differences Postgres is entitled to introduce") {
    // DESIGN §3.3: Postgres quotes only identifiers that need it and reformats freely, so
    // the comparison key drops quoting, case and spacing.
    assertEquals(canonicalCheck("""length("title") <= 200"""), "length(title) <= 200")
    assertEquals(canonicalCheck("LENGTH(title)   <=  200"), "length(title) <= 200")
    assertEquals(canonicalCheck("  length(title) <= 200  "), "length(title) <= 200")
  }

  test("canonicalCheck keeps the case of a string literal's own content") {
    // status in ('Draft','Published') and status in ('draft','published') are different
    // constraints, not two spellings of the same one. Only the case outside the literals,
    // where Postgres itself folds freely, should be erased. Closes BACKLOG #17.
    assertEquals(
      canonicalCheck("{} IN ('Draft','Published')"),
      "{} in ('Draft','Published')"
    )
  }
}
