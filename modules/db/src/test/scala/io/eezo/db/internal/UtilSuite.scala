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
    // where Postgres itself folds freely, should be erased. Was half of backlog item 17: a
    // literal's case. The quoting half is still open in BacklogSuite.
    assertEquals(
      canonicalCheck("{} IN ('Draft','Published')"),
      "{} in ('Draft','Published')"
    )
  }

  test("canonicalCheck keeps the spacing of a string literal's own content") {
    // A check against two literal spaces is not a check against one, so the whitespace that
    // folds freely outside a literal must stay exactly as written inside one.
    assertEquals(canonicalCheck("status <> '  '"), "status <> '  '")
  }

  test("canonicalCheck keeps a doubled quote inside a literal, and its case") {
    // The literal is It's, spelled the way Postgres spells an embedded quote: a doubled
    // quote, not a backslash. Closing and reopening on the pair happens to land right, and
    // this is what pins that against a scanner that reads '' as the end of the literal.
    assertEquals(canonicalCheck("{} = 'It''S'"), "{} = 'It''S'")
  }
}
