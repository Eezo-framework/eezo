# From nothing to Fly.io — the eezo deploy walkthrough

The whole arc: scaffold an app, give it a model, ship it. Every output below is from a real run
(2026-09-09, first field deploy). Time budget: ~15 minutes, most of it Fly's first image build.

What you will *not* do at any point: write a Dockerfile, run Docker, write deployment YAML, run
migration SQL by hand, or SSH into anything.

---

## 0. Prerequisites

- **Fly account + CLI.** `curl -L https://fly.io/install.sh | sh`, add `~/.fly/bin` to `PATH`,
  then `fly auth signup` (or `fly auth login`).
- **The eezo command line:** `curl -fsSL https://eezo.io/install | bash`, as in
  [your first application](getting-started.md).

## 1. Scaffold

```bash
eezo new bookshelf
cd bookshelf
eezo routes
```

```
1 route:
  GET /
```

That's the whole app: a one-override `Main extends EezoApp`, one handwritten route under `app/`,
and the build files. No deploy configuration exists yet, anywhere.

## 2. See it run

```bash
eezo dev
```

Serves on 8080 (add `override def port: Int = <n>` in `Main` if 8080 is taken), restarts on every
save, and keeps serving the old code if you save a compile error. Two URLs worth visiting:

- `http://localhost:8080/` — the scaffold page
- `http://localhost:8080/eezo/health` — `ok`. The framework answers this on every eezo server;
  it is what Fly's health checks and the deploy's post-deploy poll will hit.

## 3. A model, and its migration

`src/main/scala/models/Book.scala`:

```scala
package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.{Form, Resource}

case class Book(
    id: Id[Book],
    title: String,
    author: String,
    pages: Int
) derives Table,
      Form,
      Resource
```

`src/main/scala/AppSchema.scala`:

```scala
import io.eezo.db.Schema
import models.Book

object AppSchema extends Schema {
  val books = table[Book].unique(_.title)
}
```

And name the schema in `Main.scala`:

```scala
import io.eezo.db.Schema
// inside Main:
  override def schema: Schema = AppSchema
```

`eezo routes` now shows 8 routes — yours plus seven derived CRUD routes at `/books`. Freeze the
schema as the first migration (no database needed — freeze diffs the committed snapshot against
your code):

```bash
eezo freeze initial schema
```

```
2 change(s) since last freeze:

  create table book
  + index uq_book_title on book

wrote db/migrations/0001_initial_schema.sql
```

That file is committed SQL with a fingerprint header, and it is exactly what will run on Fly.

## 4. First deploy — designed to stop halfway

```bash
eezo deploy
```

```
→ first deploy: creating the Fly app
Created app 'bookshelf-…' in organization '…'
→ wrote fly.toml (app: bookshelf-…, region: fra)
✗ deployment cannot continue: no database secret is set.
```

Three things happened: Fly created the app and picked your nearest region (`fly launch` under the
hood — that detection is Fly's, not eezo's), eezo wrote `fly.toml` (yours: committed, editable,
rewritten only if you delete it), and the deploy stopped **on purpose** — this app has
migrations, `migrate --apply` will run on deploy, and there is no database yet.

The interesting line in the generated `fly.toml`:

```toml
[deploy]
  release_command = 'migrate --apply'
```

This is eezo's migration ordering, delegated to the platform: Fly runs the command in a one-off
machine with the **new** image, *before* any serving machine is updated. A nonzero exit stops the
deployment and the old version keeps serving. The schema converges before new code takes traffic.

## 5. The database

```bash
fly postgres create --name bookshelf-db --region fra   # pick "Development" (single node)
fly postgres attach bookshelf-db --app <your-app-name>
```

`attach` creates a database + user inside the cluster and sets the `DATABASE_URL` secret on your
app. Do not save the printed URL anywhere — it lives in Fly's secret store, which is the point;
eezo parses `DATABASE_URL` natively (it also understands `EEZO_DB_URL`/`_USER`/`_PASS`). On a
platform URL eezo requires TLS unless the URL says otherwise with its own `sslmode`, and Fly's
attach says otherwise on purpose with `sslmode=disable`, so a database elsewhere that only speaks
clear text needs `sslmode=disable` added to its URL or a raw JDBC url in `EEZO_DB_URL`. Verify
without exposing anything:

```bash
fly secrets list
```

One row, `DATABASE_URL`, digest only. `Staged` status is normal — there are no machines yet.

`EEZO_DB_POOL_SIZE` is the number of connections in the pool, default 10.

`EEZO_DB_ACQUIRE_TIMEOUT` is how many milliseconds a request waits for a connection before it fails with a 500, default 5000.

## 6. Deploy for real

```bash
eezo deploy
```

```
→ staging the application
→ deploying to Fly (migrations run before the new version takes traffic)
   … Fly's build output (remote builder; no local Docker involved) …
   … release_command: migrate --apply →  0001_initial_schema.sql, applied ✓,
     database matches model ✓  (on some flyctl versions this appears in the Fly
     dashboard's release logs rather than inline) …
→ waiting for health at https://<app>.fly.dev/eezo/health

✓ deployed and healthy

  https://<app>.fly.dev
```

Behind the scenes, `eezo build` staged `target/eezo/stage/` — every runtime jar, your
`db/migrations`, and a generated Dockerfile (JRE-only, no sbt) — and Fly's remote builder turned
it into the image. The first build is the slow one (base image pull); later deploys are much
faster.

## 7. Prove it

Open `https://<app>.fly.dev/books`. Create a book through the derived form, read it, delete it.
Those rows live in your Fly Postgres, written through `JdbcStore` into the table your migration
created. `/eezo/health` answers `ok`; anything unknown 404s with eezo's problem page.

## 8. The loop from here

Change the model → `eezo freeze <name>` → `eezo deploy`. The new migration ships inside the
image and runs in the release machine before the new code serves. If it fails, the deploy fails,
the old version keeps serving, and the migration's own output says why. `eezo deploy` with
nothing pending prints `no pending migrations` in the release step and just ships code.

When the new version replaces a machine, Fly signals the old one to stop. The server stops taking
new requests at once, gives the ones already running up to three seconds to finish, and only
then closes the database, so a request inside a transaction completes instead of losing its
connection halfway.

CI is the same two commands, non-interactively: authenticate with `FLY_API_TOKEN`, and pass
`--app <name>` on the first-ever deploy (interactive prompts are refused when there's no TTY).

## Troubleshooting, from the field

- **`eezo routes` shows fewer routes than expected** — check the generator's warnings. The scan
  mounts top-level models (indented or not) and models nested in objects (as `Outer.Inner`); a
  model nested in a class, or an indented one in a significant-indentation file, cannot be mounted
  by name and warns, naming the enclosure and the `Resource.routesOf` escape hatch.
- **Health checks never go green** — `internal_port` in `fly.toml` must equal the app's `port`
  (default 8080). They drift when you override one and not the other.
- **Migration failed** — the deploy stopped before traffic switched; the old version is still
  serving. `fly logs` (or the release logs in the Fly dashboard) has the migration's own output.
- **Where are the deploy logs?** — `fly deploy`'s stream, plus the Fly dashboard for the release
  machine's logs. `eezo` deliberately does not swallow Fly's output during deploy.
