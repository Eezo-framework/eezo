# blog: both edges

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo"` (the umbrella) and `"io.eezo" %% "eezo-auth"` |
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
sbt "blog/run sync --apply"     # the two tables have to exist before the first request
EEZO_BLOG_EMAIL=you@example.com EEZO_BLOG_PASSWORD='a long one' \
  sbt "blog/runMain CreateUser" # the first user; there is no sign up page
sbt blog/run                    # http://localhost:8080, posts at /admin/posts
sbt "blog/run routes"           # 11 routes: GET /, the three the guard carries, and the seven
sbt "blog/run status"           # in sync ✓
```

An existing blog database predates the `users` table, so `sync --apply` is not optional on one:
without it the first sign in fails on a table that is not there.

## Signing in

`/admin` is guarded. `models/Post.scala` says so in one line, `given Guarded[Post] =
User.guard.required`, and that line is also what mounts `/admin/login` and `/admin/logout`: a
declaration carries the guard's own routes with it, so the page a refusal redirects to cannot be
forgotten. `app/Index.scala` says the opposite in the same shape, `given Guarded[Index.type] =
Guarded.public`, because this application has a guard and so every route it mounts has to say who
may reach it. Leaving one out is a compile error naming the type, in the generated table.

Visiting `/admin/posts` while signed out answers a 303 to `/admin/login` and remembers where you
were going, so signing in lands on the page you asked for. `POST /admin/logout` empties the
session, but no page the blog serves renders a form that posts to it yet: neither the derived
pages behind the guard nor `Guard.page` links to it. Signing out today means posting to that route
by hand, with the token read out of your own cookie, or simply closing the browser so the session
cookie goes away on its own.

### The first user, without a sign up page

There is no registration: `User` derives `Table` alone, with no `Form` and no `Resource`, so nothing
mounts a page that writes one. `CreateUser.scala` is the operator's tool instead, a second `DbApp`
in the same project, and the command above is the whole of it. It reads the credentials from the
environment rather than from arguments, because a command line is visible to every process on the
machine through `ps` and is written to the shell's history file.

It hashes through `Password.hash`, which is the only door: `User.password` is a `Password`, and the
only way to make one from text is to hash it.

`sbt "blog/run dev"` (or `blog/eezoDev`) runs the drift check before serving and answers the drift
page while the drift is dangerous; `../todo/README.md` walks through that loop and the rest of the
schema commands.

## Why both derivations compile here

`Table` is the database edge's derivation and `Form` and `Resource` are the http edge's. This
application depends on the umbrella, so both edges are on its classpath and all three derive.
`../hello` has the http edge only and shows `derives Table` failing to compile; `../reminders` has
the database edge only and shows `derives Form` failing. Opting out of an edge is one changed line
in `build.sbt`.

`eezo-auth` is named in `build.sbt` beside the umbrella, even though the umbrella already carries
it, and naming it is what turns the "every route must declare who may reach it" rule on. `../todo`
depends on the same umbrella and does not name it, so its routes declare nothing and its generated
table is what it always was. Guards are a feature an application asks for, not something it
inherits from depending on eezo.
