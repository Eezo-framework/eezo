# reminders: the database edge alone

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo-db"` |
| entry trait | `io.eezo.db.DbApp` |
| edges | database only |

A nightly job over rows and no server. `models/Reminder.scala` derives `Table`, `AppSchema.scala`
registers it with an index on `dueOn`, and `Main.scala` names that schema and says what `boot` does:
deliver every reminder that is due and not yet sent, and mark it sent, in one `transact`. Delivery
is a line on stdout. `DbApp` brings `main`, the `Database` it installs around `boot`, and the eight
schema commands. There is no `EezoPlugin` on this project, because there are no routes to generate.

The job keeps its table in a Postgres schema named `reminders` (`databaseSchema` plus the
`databaseInit` hook; `Main.scala` says why both halves exist), so it shares the dev database with
the other examples without their drift bleeding into each other.

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17   # skip if it already runs
sbt publishLocalForExample      # once, at the repository root
cd examples
```

```
$ sbt "reminders/run status"
2 difference(s) between model and database:

  create table reminder
  + index idx_reminder_due_on on reminder

$ sbt "reminders/run sync --apply"
  create table reminder
  + index idx_reminder_due_on on reminder

applied ✓

$ sbt reminders/run
empty table: seeded three reminders
  2026-09-07  renew the domain
  2026-09-10  rotate the database password
2 reminder(s) delivered

$ sbt reminders/run
0 reminder(s) delivered
```

The first run seeds three reminders, two of them due, and delivers those two. The second finds
nothing due that is not already sent. `freeze`, `migrate`, `reset`, `drop`, `dump` and `ddl` work as
they do on `../todo`; `sbt "reminders/run help"` lists them.

## What does not exist here

The http edge. `eezo-db` does not depend on `eezo-http`, so nothing under `io.eezo.http` is on the
classpath, and the http edge's commands are not commands:

```
$ sbt "reminders/run dev"
[eezo] unknown command: dev. Run `help` for the list.
$ sbt "reminders/run routes"
[eezo] unknown command: routes. Run `help` for the list.
```

A rerun on save loop for a job is sbt's own `~reminders/run`.

A model deriving `Form` does not compile. The line, tried on `Reminder` and removed again:

```scala
import io.eezo.http.Form

case class Reminder(...) derives Table, Form
```

```
[error] -- [E008] Not Found Error: reminders/src/main/scala/models/Reminder.scala:7:15
[error] 7 |import io.eezo.http.Form
[error]   |       ^^^^^^^^^^^^
[error]   |       value http is not a member of io.eezo
```

The cure is one changed line in `build.sbt`: depend on `"io.eezo" %% "eezo"` instead, extend
`EezoApp`, and name `routes`, as `../blog` and `../todo` do.
