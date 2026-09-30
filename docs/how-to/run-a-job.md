<!-- draft -->
# Run a job instead of a server

An application on the database edge alone whose `boot` is a unit of work over rows, run once or on a schedule.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `eezo-db` alone, `extends DbApp`, no plugin
- `boot` as the job: one `transact`, deliver what is due, mark it done
- the schema commands are still there; the http commands are not
- running it once, on a schedule, and re-running on save with sbt's own watch
- seeding on an empty table

## Where the material is

- `examples/reminders` and its README
- `modules/db/src/main/scala/io/eezo/db/DbApp.scala`
