package io.eezo.db.demo

import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.db.engine.Installed
import io.eezo.db.support.{Library, Pg, PublishingHouse}
import io.eezo.core.Id

/** A runnable walk through phase 2: CRUD by primary key.
  *
  * sbt "db/Test/runMain io.eezo.db.demo.crud"
  *
  * Starts its own Postgres through testcontainers. Like `ScopesDemo`, it lives here because
  * installing a `Database` is `private[eezo]` until `EezoApp` exists.
  */
object CrudDemo {

  private val schema = "demo_crud"

  private def step(n: String)(body: => Unit): Unit = {
    println(s"\n── $n ${"─" * math.max(0, 58 - n.length)}")
    try body
    catch { case e: Throwable => println(e.getMessage.linesIterator.map("   " + _).mkString("\n")) }
  }

  def main(args: Array[String]): Unit = {
    val c  = Pg.connect()
    val st = c.createStatement()
    st.execute(s"""drop schema if exists "$schema" cascade"""): Unit
    st.execute(s"""create schema "$schema""""): Unit
    st.execute(s"""set search_path to "$schema""""): Unit
    Library.ddl.foreach(sql => st.execute(sql): Unit)
    st.close()
    c.close()

    Installed.install(Pg.database(schema))

    val t = Table[PublishingHouse]

    println("── the SQL one `derives Table` produces ────────────────────")
    println(s"   insert:   ${t.insertSql}")
    println(s"   findById: ${t.selectByIdSql}")
    println(s"   update:   ${t.updateByIdSql}")
    println(s"   delete:   ${t.deleteByIdSql}")

    val faber = PublishingHouse(Id.gen[PublishingHouse](), "Faber", "London")

    step("1. insert, then read it back") {
      transact { t.insert(faber) }
      println(s"   findById: ${read { t.findById(faber.id) }}")
    }

    step("2. update writes every column, matched by the key") {
      val moved = faber.copy(name = "Faber & Faber", location = "Bloomsbury")
      transact { t.update(moved) }
      println(s"   findById: ${read { t.findById(faber.id) }}")
    }

    step("3. all() returns every row") {
      transact {
        t.insert(PublishingHouse(Id.gen[PublishingHouse](), "Verso", "Soho"))
        t.insert(PublishingHouse(Id.gen[PublishingHouse](), "Fitzcarraldo", "Deptford"))
      }
      read { t.all() }.foreach(h => println(s"   ${h.name} (${h.location})"))
    }

    step("4. delete removes it") {
      val matched = transact { t.delete(faber.id) }
      println(s"   rows matched: $matched")
      println(s"   findById: ${read { t.findById(faber.id) }}")
    }

    step("5. updating a row that is gone matches no row") {
      val matched = transact { t.update(faber.copy(name = "back from the dead")) }
      println(s"   rows matched: $matched")
    }

    step("6. a write and the read that checks it share one transaction") {
      val h    = PublishingHouse(Id.gen[PublishingHouse](), "Peninsula", "Hackney")
      val seen = transact {
        t.insert(h)
        t.findById(h.id) // Tx <: DB, so this joins rather than opening a second scope
      }
      println(s"   seen inside the transaction: $seen")
    }

    println("""
── what the compiler refused before any of this ran ────────
   t.insert(h)                        outside a scope: requires a transaction
   read { t.insert(h) }               a `DB` is not enough: writes need a `Tx`
   Table[Untabled]                    No Table instance: add `derives Table`
   case class Bad(id: String) derives Table
                                      `id` must be `Id[Bad]`""")

    Installed.uninstall()
  }
}

@main def crud(): Unit = CrudDemo.main(Array.empty)
