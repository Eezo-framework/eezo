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
```

The first user comes next, since there is no sign up page. Run this line on its own and type the
password at the prompt. It is a separate block on purpose: pasted together with the lines after it,
`read` would take the next line as the password.

```bash
printf 'password: '; IFS= read -rs EEZO_BLOG_PASSWORD; echo; export EEZO_BLOG_PASSWORD
```

```bash
EEZO_BLOG_EMAIL=you@example.com sbt "blog/runMain CreateUser"
unset EEZO_BLOG_PASSWORD        # the server below has no use for it
sbt blog/run                    # http://localhost:8080, posts at /admin/posts
sbt "blog/run routes"           # 11 routes: GET /, the three the guard carries, and the seven
sbt "blog/run status"           # in sync ✓
```

An existing blog database predates the `users` table, so `sync --apply` is not optional on one:
without it the first sign in fails on a table that is not there.

## Signing in and out

`/admin` is guarded, and each post belongs to whoever wrote it. `models/Post.scala` says both in
one line, `given Owned[Post, User] = User.guard.required[Post].owning(_.author).except(Action.Index,
Action.Show)`, and that line is also what mounts `/admin/login` and `/admin/logout`: a declaration
carries the guard's own routes with it, so the page a refusal redirects to cannot be forgotten.
`app/Index.scala` says the opposite in the same shape, `given Guarded[Index.type] =
Guarded.public`, because this application has a guard and so every route it mounts has to say who
may reach it. Leaving one out is a compile error naming the type, in the generated table.

## Each author edits their own posts

`required` is who has to be signed in; `except(Index, Show)` is whose rows each route reads. Every
signed in author sees the whole blog on `/admin/posts` and can open anybody's post, and the five
remaining routes read and write that author's rows alone. Opening somebody else's post shows its
fields and offers neither an Edit link nor a Delete button; asking for `/admin/posts/<id>/edit` on
one answers 403, and a `PUT` or `DELETE` at a hand-typed address answers the same. An id that names
no post at all answers 404, so the two are told apart.

`author` is a field of `Post` like any other, and it is on no page: the derived form emits no input
for it, the index heads no column with it and the show page prints no row. The handler fills it from
who is signed in, on `create` and on `update` both, so a hand-crafted `POST` carrying
`author=<somebody else>` writes a post attributed to whoever sent it.

Adding `author` is drift against a blog database that predates it, and on one already holding posts
`sync --apply` alone is not enough: `author` is `not null` with no default, and Postgres refuses to
add such a column to a table that already has rows. On a database with nothing worth keeping, `sbt
"blog/run reset"` drops every table and recreates them from the model in one pass, at the price of
every `user` row too, so create the first user again afterward. To keep the existing posts, add the
column nullable first and give each one an owner by hand, then let sync narrow it: `alter table
blog."post" add column "author" uuid;` then `update blog."post" set "author" = (select "id" from
blog."user" limit 1);` then `sbt "blog/run sync --apply --force"`. The update hands every existing
post to one user, whichever the database returns first, and it needs a user to be there: on an empty
`user` table it writes null into every row and the forced sync then fails on the nulls, so create
the first user before running it. `--force` is what lets the `not null` through, since narrowing a
column is a change sync blocks on purpose, but it is not selective: it applies every change sync
was blocking, dropped tables and columns included, so read what `sbt "blog/run sync"` lists before
forcing it. Either way, a second user is what it takes to see the effect.

Visiting `/admin/posts` while signed out answers a 303 to `/admin/login` and remembers where you
were going, so signing in lands on the page you asked for.

`POST /admin/logout` empties the session, and the front page is where the blog offers it:
`app/Index.scala` ends with `User.guard.logoutForm(request, Url.Absolute("/admin/logout"))`, which
renders a Sign out button for a browser that is signed in and nothing at all for one that is not.
The guard builds the form rather than the application, because a `POST` has to carry the CSRF token
and a hand written form that forgot it would be refused with a 403 at the route it posts to.

The button is on `/` and not on the editing screens because those are derived: a derived page comes
back in a plain envelope with nowhere to put a control, and whether an application can replace that
envelope is still an open question. The address is written out because `/` is outside the mount, the
same reason the "All posts" link on that page names `/admin` by hand; a page inside a mount takes
`logoutForm`'s default and never spells the prefix.

### The first user, without a sign up page

There is no registration: `User` derives `Table` alone, with no `Form` and no `Resource`, so nothing
mounts a page that writes one. `CreateUser.scala` is the operator's tool instead, a second `DbApp`
in the same project, and the commands above are the whole of it. It reads the email and password
from the environment rather than from arguments, because an argument list is visible to every
process on the machine through `ps`. The environment alone does not keep a secret out of the
shell's history file, which records the whole command line, so the password is typed at a prompt
that does not echo instead of being written into the command.

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
depends on the same umbrella and does not name it, so its routes declare nothing and every one of
them is public. Demanding a declaration is a feature an application asks for, not something it
inherits from depending on eezo. Honouring one is not optional either way: the generated table
looks a `Guarded` up for every route it mounts, so a guard written in an application that never
named `eezo-auth` still guards.
