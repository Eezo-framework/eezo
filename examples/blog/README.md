# blog: both edges

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo"` (the umbrella) |
| entry trait | `io.eezo.EezoApp` |
| edges | database and http |

One case class with all three derivations. `models/Post.scala` carries `derives Table, Form,
Resource`: the table, the HTML form, and the seven CRUD routes over it. `AppSchema.scala` registers
the table with a unique title, and `Main.scala` names that schema and the generated route table.
Because `Post` carries a `Table`, the generated table hands its routes a `JdbcStore`, so the posts
live in Postgres and survive a restart.

The derived half of the table is then mounted under `/admin`: `Main.scala` splits the routes on
where they came from and wraps only the derived ones in `Route.under("/admin")`, so the handwritten
`app/Index.scala` keeps answering `GET /` and the posts live at `/admin/posts`. The mount moves the
routes and the URLs the pages emit together; `app/Index.scala` explains the one link that has to
name `/admin` by hand.

The blog keeps its table in a Postgres schema named `blog` (`databaseSchema` plus the `databaseInit`
hook), so it shares the dev database with the other examples.

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17   # skip if it already runs
sbt publishLocalForExample      # once, at the repository root
cd examples
sbt "blog/run sync --apply"     # the table has to exist before the first request
sbt blog/run                    # http://localhost:8080, posts at /admin/posts
sbt "blog/run routes"           # 8 routes: GET / and the seven under /admin/posts
sbt "blog/run status"           # in sync ✓
```

`sbt "blog/run dev"` (or `blog/eezoDev`) runs the drift check before serving and answers the drift
page while the drift is dangerous; `../todo/README.md` walks through that loop and the rest of the
schema commands.

## Why both derivations compile here

`Table` is the database edge's derivation and `Form` and `Resource` are the http edge's. This
application depends on the umbrella, so both edges are on its classpath and all three derive.
`../hello` has the http edge only and shows `derives Table` failing to compile; `../reminders` has
the database edge only and shows `derives Form` failing. Opting out of an edge is one changed line
in `build.sbt`.
