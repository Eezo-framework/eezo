import java.sql.Connection
import java.time.LocalDate

import io.eezo.db.*
import io.eezo.db.Scopes.*

import models.Reminder

/** The entry point of an application with the database edge only: name the schema, and say what
  * `boot` does.
  *
  * This application depends on `eezo-db`, not on the umbrella, so `DbApp` is the trait it extends
  * and there is no server: `sbt run` runs [[boot]] once with a `Database` installed around it and
  * exits, and `sbt "run status|sync|freeze|migrate|reset|drop|dump|ddl"` are the schema commands.
  * `sbt "run dev"` and `sbt "run routes"` are unknown commands here, because they are the http
  * edge's; a rerun on save loop for a job is sbt's own `~run`.
  *
  * The program is a nightly job over rows: deliver every reminder that is due and not yet sent, and
  * mark it sent. Delivery is a line on stdout; a real one would be an email. Both halves of the job
  * sit in one `transact`, so a reminder is never marked sent without having been printed, and the
  * table is seeded on the first run so there is something to deliver.
  */
object Main extends DbApp {

  override def schema: Schema = AppSchema

  /** This app keeps its table in a Postgres schema of its own, so it can share the dev database
    * with the other examples without their drift bleeding into each other. Two halves, told once
    * each: the init hook runs on **every** pooled connection (search_path is per connection), and
    * `databaseSchema` points the drift commands' catalog reads at the same name.
    */
  override def databaseSchema: String = "reminders"

  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try {
      st.execute("""create schema if not exists "reminders"""")
      st.execute("""set search_path to "reminders"""")
    } finally st.close()
  }

  override def boot(): Unit = {
    val reminders = Table[Reminder]
    val today     = LocalDate.now()

    val delivered = transact {
      if (reminders.query.count() == 0L) {
        Reminder.seed(today).foreach(reminders.insert)
        println("empty table: seeded three reminders")
      }

      val due = reminders
        .where(_.sent === false)
        .where(_.dueOn <= today)
        .orderBy(_.dueOn.asc)
        .list()

      due.foreach { reminder =>
        println(s"  ${reminder.dueOn}  ${reminder.text}")
        reminders.update(reminder.copy(sent = true))
      }
      due.size
    }

    println(s"$delivered reminder(s) delivered")
  }
}
