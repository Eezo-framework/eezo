# Run a job instead of a server

An application on the database edge alone, whose `boot` is a unit of work over rows: run once,
on a schedule, or by a cron.

## The build

Depend on `eezo-db` instead of the umbrella, and skip the plugin: there are no routes to
generate.

```scala
libraryDependencies += "io.eezo" %% "eezo-db" % eezoVersion
```

With only `eezo-db` on the classpath, `derives Form` and `derives Resource` don't compile, and
`sbt "run dev"` is an unknown command. That's the point of opting into one edge.

## `boot` is the job

```scala
import java.time.LocalDate
import io.eezo.db.*
import io.eezo.db.Scopes.*
import models.Reminder

object Main extends DbApp {

  override def schema: Schema = AppSchema

  override def boot(): Unit = {
    val reminders = Table[Reminder]
    val today     = LocalDate.now()
    val delivered = transact {
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
```

`DbApp` installs the database around `boot` and takes it down after, so `sbt run` does the work
and exits. The query is the typed layer: `where` on a field, `orderBy`, `list()`, inside one
`transact` so the reads and the updates are one transaction.

## The schema commands come along

`status`, `sync`, `freeze`, `migrate`, `reset`, `drop`, `dump` and `ddl` work exactly as on a web
application, so the table is created the same way:

```bash
sbt "run freeze initial schema"
sbt "run migrate --apply"
sbt run
```

## Running it repeatedly

- On a schedule: whatever runs commands on your platform, cron or a Fly scheduled machine,
  running the same `java -cp lib/* Main` the staged Dockerfile does.
- While developing, re-run on save with sbt's own watch: `sbt ~run`.

## Seeding

A job that wants data on its first run can check the table and insert:

```scala
if (reminders.query.count() == 0L) Reminder.seed(today).foreach(reminders.insert)
```

The reminders example under `examples/reminders` is this whole page as a running project.
