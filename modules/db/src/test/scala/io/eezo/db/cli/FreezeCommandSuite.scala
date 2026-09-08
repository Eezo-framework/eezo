package io.eezo.db.cli

import io.eezo.db.migrate.{Decision, Migration}
import io.eezo.db.support.{Library, Widgets}

import munit.FunSuite

import java.nio.file.Files

/** `freeze` needs no database: it diffs the committed snapshot against the code and writes files. A
  * plain suite, so the property stays pinned — if a capability creeps into its signature, this file
  * stops compiling.
  */
class FreezeCommandSuite extends FunSuite {

  test("freeze: writes a numbered, fingerprinted migration and advances the snapshot") {
    val dir = Files.createTempDirectory("eezo-cli-freeze")

    val decided = List.newBuilder[String]
    val first   = Commands.freeze(
      Library,
      "initial schema",
      c => { decided += c.describe; Decision.Accept },
      dir
    )

    val out = first.migration.getOrElse(fail("expected a migration to be written"))
    assertEquals(out.getFileName.toString, "0001_initial_schema.sql")
    assert(Migration.verify(Files.readString(out)).isRight, "fingerprint header must verify")
    // Every change went through `decide`, not only the destructive ones.
    assertEquals(decided.result().size, first.resolutions.size)

    // The snapshot advanced: the same model has nothing left to freeze.
    assertEquals(Commands.freeze(Library, "again", _ => Decision.Accept, dir).migration, None)
  }

  test("freeze: a Skip keeps the change out of the written statements") {
    val dir = Files.createTempDirectory("eezo-cli-freeze-skip")
    assert(Commands.freeze(Library, "init", _ => Decision.Accept, dir).migration.isDefined)

    // Library -> Widgets drops three tables (destructive) and creates one.
    val second = Commands.freeze(
      Widgets,
      "widgets only",
      c => if (c.destructive) Decision.Skip else Decision.Accept,
      dir
    )

    val out     = second.migration.getOrElse(fail("expected a migration to be written"))
    val written = Files.readString(out)
    assert(!written.contains("drop table"), s"skipped drops must not be written:\n$written")
    assert(written.contains("create table"), s"accepted creates must be written:\n$written")
  }
}
