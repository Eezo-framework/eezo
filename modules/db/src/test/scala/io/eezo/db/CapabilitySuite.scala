package io.eezo.db

import io.eezo.db.capability.TxCap
import io.eezo.db.Scopes.*
import munit.FunSuite

/** What the compiler refuses, and what it says.
  *
  * Every assertion here is on the **message**, not on "did not compile". An unrelated implicit
  * error in the same compilation unit suppresses `summonFrom`'s output, so a test that checked only
  * for failure could pass for the wrong reason — DESIGN §8.6.
  */
class CapabilitySuite extends FunSuite {

  // referenced from the snippets below, which are compiled in this scope
  def needsTx()(using Tx): Unit = ()
  def needsDb()(using DB): Unit = ()

  /** A model with no `derives Table`. */
  case class Untabled(id: Id[Untabled], name: String)

  test("a write with no scope says what to do") {
    val e = compileErrors("needsTx()")
    assert(e.contains("this writes to the database, which requires a transaction"), e)
    assert(e.contains("run inside `transact { ... }`"), e)
  }

  test("a write inside a read says a DB is not enough") {
    val e = compileErrors("read { needsTx() }")
    assert(e.contains("A `DB` is not enough"), e)
  }

  test("a read outside any scope says where to get one") {
    val e = compileErrors("needsDb()")
    assert(e.contains("no database scope here"), e)
  }

  test("a nested transact says to remove it") {
    val e = compileErrors("transact { transact { needsTx() } }")
    assert(e.contains("already inside a transaction: remove this `transact`"), e)
    assert(e.contains("use `attempt`"), e)
  }

  test("a transact inside a read says to move it outward") {
    val e = compileErrors("read { transact { needsTx() } }")
    assert(e.contains("cannot open a transaction inside a `read` scope"), e)
  }

  test("a nested read says to remove it") {
    val e = compileErrors("read { read { needsDb() } }")
    assert(e.contains("already inside a database scope"), e)
  }

  test("a transaction cannot be built by hand") {
    val e = compileErrors("new TxCap { def connection = ??? }")
    assert(e.contains("Cannot extend sealed trait TxCap"), e)
  }

  test("a CRUD write outside a scope reports the missing transaction") {
    val e = compileErrors(
      "Table[io.eezo.db.support.PublishingHouse].delete(Id.gen[io.eezo.db.support.PublishingHouse]())"
    )
    assert(e.contains("this writes to the database, which requires a transaction"), e)
  }

  test("a CRUD write inside a read scope reports the same thing") {
    val e = compileErrors(
      "read { Table[io.eezo.db.support.PublishingHouse].delete(Id.gen[io.eezo.db.support.PublishingHouse]()) }"
    )
    assert(e.contains("A `DB` is not enough"), e)
  }

  test("a model with no `derives Table` says how to add one") {
    val e = compileErrors("Table[Untabled]")
    assert(e.contains("No Table instance for"), e)
    assert(e.contains("derives Table"), e)
  }

  test("an `id` of the wrong type is refused where it is declared") {
    val e = compileErrors("case class Bad(id: String, name: String) derives Table")
    assert(e.contains("`id` must be `Id[Bad]`"), e)
  }

  test("a model with no `id` at all is refused") {
    val e = compileErrors("case class Keyless(name: String) derives Table")
    assert(e.contains("has no `id` field"), e)
  }
}

/* The capture-level guarantees — a `Tx` or a cursor escaping its block — are NOT here, and cannot
 * be. `compileErrors` runs the typer only, and capture checking is a later phase, so a capture
 * violation type-checks clean and `compileErrors` returns an empty string. Verified: the same
 * expression placed in a real source file fails to compile with
 *
 *   object EscapeProbe needs to extend Capability since it has a field `stolen` with `any` in its type
 *
 * so the guarantee is real and only the tool is blind. Those cases live in
 * research/harnesses/tx-capture, which compiles each one as its own unit. This is the fourth way
 * this guarantee can appear absent while being present — see DESIGN §8.6.
 */
