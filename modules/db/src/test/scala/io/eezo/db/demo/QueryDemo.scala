package io.eezo.db.demo

import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.db.engine.Installed
import io.eezo.db.support.{Library, Pg, PublishingHouse}

/** A runnable walk through phase 4: predicates, ordering and paging.
  *
  * sbt "db/Test/runMain io.eezo.db.demo.query"
  *
  * Every step prints the SQL the query rendered before showing what it returned.
  */
object QueryDemo {

  private val schema = "demo_query"
  private val house  = Table[PublishingHouse]

  private def step(n: String)(q: Query[PublishingHouse])(show: => Unit): Unit = {
    println(s"\n── $n ${"─" * math.max(0, 56 - n.length)}")
    val (sql, binds) = q.selectSql
    println(s"   $sql")
    println(s"   ${binds.size} bind(s)")
    show
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

    transact {
      house.insert(PublishingHouse(Id.gen(), "Faber", "London"))
      house.insert(PublishingHouse(Id.gen(), "Verso", "London"))
      house.insert(PublishingHouse(Id.gen(), "Fitzcarraldo", "Deptford"))
      house.insert(PublishingHouse(Id.gen(), "Peninsula", "Hackney"))
    }

    step("1. a predicate")(house.where(_.location === "London")) {
      read { house.where(_.location === "London").list() }.foreach(h => println(s"   → ${h.name}"))
    }

    step("2. two predicates compose with `and`, no `true and`")(
      house.where(_.location === "London").where(_.name === "Verso")
    ) {
      println(
        s"   → ${read { house.where(_.location === "London").where(_.name === "Verso").first() }}"
      )
    }

    step("3. ordering and paging happen in the database")(
      house.orderBy(_.name.asc).limit(2).offset(1)
    ) {
      read { house.orderBy(_.name.asc).limit(2).offset(1).list() }.foreach(h =>
        println(s"   → ${h.name}")
      )
    }

    step("4. `in`, with one hole per value")(house.where(_.name in Seq("Faber", "Peninsula"))) {
      println(s"   → count ${read { house.where(_.name in Seq("Faber", "Peninsula")).count() }}")
    }

    step("5. an empty `in` matches nothing instead of being a syntax error")(
      house.where(_.name in Nil)
    ) {
      println(s"   → count ${read { house.where(_.name in Nil).count() }}")
    }

    step("6. negation and disjunction")(
      house.where(c => !(c.location === "London") or c.name === "Verso")
    ) {
      read { house.where(c => !(c.location === "London") or c.name === "Verso").list() }
        .foreach(h => println(s"   → ${h.name}"))
    }

    println("\n── 7. a query is a value ───────────────────────────────────")
    val londoners = house.where(_.location === "London").orderBy(_.name.asc)
    println("   built outside any scope, holding no capability, run twice:")
    println(s"   → list  ${read { londoners.list() }.map(_.name).mkString(", ")}")
    println(s"   → count ${read { londoners.count() }}")

    println("\n── 8. deleting takes a predicate, always ───────────────────")
    println(s"   ${house.tableDef.deleteWhere(""""location" = ?""")}")
    println(s"   → removed ${transact { house.deleteWhere(_.location === "London") }}")
    println(s"   → left    ${read { house.query.count() }}")

    println(
      """
── what the compiler refuses ───────────────────────────────
   house.where(_.locatoin === "London")   value locatoin is not a member of ColsOf[PublishingHouse]
   house.where(_.name === 42)             Found: Int, Required: String
   house.query.delete()                   no such method: deleting takes a predicate, or deleteAll()"""
    )

    Installed.uninstall()
  }
}

@main def query(): Unit = QueryDemo.main(Array.empty)
