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
}
