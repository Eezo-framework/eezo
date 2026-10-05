# Create a CRUD app

The shortest path from an empty directory to seven working routes over a Postgres table. If
you'd rather have each step explained, the two tutorials [your first application](../tutorials/getting-started.md)
and [from a case class to seven routes](../tutorials/first-model.md) do the same thing slowly.

## 1. Scaffold

```bash
eezo new bookshelf
cd bookshelf
```

## 2. A database

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17
```

That's eezo's default connection; nothing to configure.

## 3. The model

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

`Resource` needs the `id: Id[Book]` field. Everything else is up to you; `String`, `Int`,
`Long`, `Double`, `Boolean`, `UUID`, `LocalDate`, `LocalDateTime`, `Instant` and `Option` of any
of them derive out of the box.

## 4. The schema

`src/main/scala/AppSchema.scala`:

```scala
import io.eezo.db.Schema
import models.Book

object AppSchema extends Schema {
  val books = table[Book].unique(_.title)
}
```

And in `Main.scala`, replace `Schema.empty`:

```scala
override def schema: Schema = AppSchema
```

## 5. The table

For a database you'll throw away:

```bash
eezo sync --apply
```

For one you'll keep, write the migration and apply it:

```bash
eezo freeze initial schema
eezo migrate --apply
```

## 6. Run

```bash
eezo dev
```

Open http://localhost:8080/books. List, new, show, edit, update and delete are all there, and
`eezo routes` lists the nine routes you have.

## Next

- Replace one of the seven pages with your own: [take over a derived route](take-over-a-derived-route.md).
- Put the editing screens behind a login: [add authentication](add-authentication.md).
- Serve the whole resource under `/admin`: [mount under a prefix](mount-under-a-prefix.md).
- Ship it: [deploy](deploy.md).
