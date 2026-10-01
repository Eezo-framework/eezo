# From a case class to seven routes

This page continues from [your first application](getting-started.md). By the end, the bookshelf
application has a `Book` model, a table in Postgres, a migration file you can commit, and seven
pages to list, show, create, edit and delete books, all derived from one case class. About thirty
minutes. You need Docker for the database.

## A database

eezo talks to Postgres. For development, start one in Docker on the port eezo looks at by
default:

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17
```

With this exact command, there's nothing to configure: eezo's defaults are `localhost:5442`,
database `eezo`, user and password `postgres`. If your database lives elsewhere, set
`DATABASE_URL` or the `EEZO_DB_*` variables; [configuring the database](../how-to/configure-the-database.md)
has the details.

## The model

Create `src/main/scala/models/Book.scala`:

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

The `derives` clause is the whole feature. Each of the three names asks eezo to generate
something from the fields:

- `Table`: a table with a column per field, and the SQL to read and write rows.
- `Form`: an HTML form with an input per field, and the code that reads a submitted form back
  into a `Book`.
- `Resource`: the seven CRUD routes, each one calling the form and the table.

`Form` and `Resource` are separate on purpose. A login form is a `Form` with no `Resource`,
because nobody lists or deletes logins. `Resource` needs an `id: Id[Book]` field, since the
create route has to mint an id before it can insert a row; `Form` doesn't care.

The field types eezo knows out of the box: `String`, `Int`, `Long`, `Double`, `Boolean`,
`UUID`, `LocalDate`, `LocalDateTime`, `Instant`, `Id[T]`, and `Option` of any of those. An
`Option[String]` is a nullable column and an input that may be left empty. You'll add one in a
minute.

## The schema

The model says what a book is. The schema says which models the application has, plus the
things a type can't say, like uniqueness. Create `src/main/scala/AppSchema.scala`:

```scala
import io.eezo.db.Schema
import models.Book

object AppSchema extends Schema {
  val books = table[Book].unique(_.title)
}
```

`_.title` is a typed selector. Rename the field and this line stops compiling.

Then name the schema in `Main.scala`, replacing `Schema.empty`:

```scala
object Main extends EezoApp {
  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
}
```

## The routes

```bash
eezo routes
```

```
9 routes:
  GET /hello
  GET /
  GET /books
  GET /books/new
  GET /books/:id
  GET /books/:id/edit
  POST /books
  PUT /books/:id
  DELETE /books/:id
```

The path is the class name, lower-cased and pluralised. The two handwritten routes come first,
then the seven derived ones, with `/books/new` ahead of `/books/:id` so that `new` isn't read as
an id.

## The table

The routes exist, but the table doesn't yet. Ask eezo what the difference is between your model
and the database:

```bash
eezo status
```

```
2 difference(s) between model and database:

  create table book
  + index uq_book_title on book
```

(The command exits with code 1 whenever there's drift, so you can gate a CI job on it.)

There are two ways to close that gap. For a database you don't care about, `eezo sync --apply`
runs the changes directly and keeps nothing. For anything else, freeze the change as a migration
file, which is what you'd do in a real project, so do it here:

```bash
eezo freeze initial schema
```

```
2 change(s) since last freeze:

  create table book
  + index uq_book_title on book

wrote db/migrations/0001_initial_schema.sql
```

Everything after `freeze` that isn't a flag is the migration's name, no quotes needed. The file
it wrote:

```sql
-- eezo migration 1
-- initial_schema
-- fingerprint: afd57a88af6edda1
-- GENERATED. Do not edit; change the model and re-freeze.

create table "book" (
  "author" text not null,
  "id" uuid primary key,
  "pages" integer not null,
  "title" text not null
);

create unique index "uq_book_title" on "book" ("title");
```

Commit it. The fingerprint is how `migrate` knows the file matches the model it was frozen from;
edit the SQL by hand and `migrate` refuses it. `freeze` didn't touch the database, by the way. It
diffs your code against a snapshot it keeps in `db/schema.json`, so you can freeze on a plane.

Now preview and apply:

```bash
eezo migrate
```

```
  0001  0001_initial_schema.sql  (2 statements)

--apply to execute
```

```bash
eezo migrate --apply
```

```
  0001  0001_initial_schema.sql  (2 statements)

applied ✓
database matches model ✓
```

```bash
eezo status
```

```
in sync ✓
```

## The seven pages

Start the application with `eezo dev` and open http://localhost:8080/books.

The list page says "No books yet" and offers a "New Book" link. Follow it. The form has one
input per field, with the input type picked by the field's type: `pages` is an `Int`, so it's a
number input. Fill it in with a title, an author and a page count, and save.

You land on the book's page, at `/books/<id>`, with every field laid out, an Edit link, and a
Delete button. Back on `/books`, the list is now a table with one row, and the title links to the
book.

Three things worth trying while you're here:

- Type "lots" into the pages field and save. The form comes back with a 422 and "is not a number"
  next to the field; nothing was written.
- Change the id in the address to one that doesn't exist. That's a 404 with eezo's problem page.
- Change the id to something that isn't a UUID at all, like `/books/nope`. That's a 400, because
  the path parameter couldn't be read as an id.

The pages are plain: no stylesheet, no layout of yours, no navigation beyond the links between
them. That's what a derived page is: correct, complete, and unstyled. When you want one of the
seven to look different, you write that one page yourself and keep the other six, which
[taking over a derived route](../how-to/take-over-a-derived-route.md) shows.

## Change the model

Add a field to `Book`:

```scala
case class Book(
    id: Id[Book],
    title: String,
    author: String,
    pages: Int,
    notes: Option[String]
) derives Table,
      Form,
      Resource
```

Under `eezo dev` the application restarts, and the console warns that the database doesn't match
the model but serves anyway, because adding a nullable column breaks nothing until code touches
it. The same loop as before closes the gap:

```bash
eezo status
```

```
1 difference(s) between model and database:

  + book.notes text null
```

```bash
eezo freeze add notes
```

```
1 change(s) since last freeze:

  + book.notes text null

wrote db/migrations/0002_add_notes.sql
```

```sql
-- eezo migration 2
-- add_notes
-- fingerprint: c997cd02b5395c97
-- GENERATED. Do not edit; change the model and re-freeze.

alter table "book" add column "notes" text;
```

```bash
eezo migrate --apply
```

```
  0002  0002_add_notes.sql  (1 statements)

applied ✓
database matches model ✓
```

Reload the new-book form and there's a Notes input, optional.

Removing a field, or making one narrower, is a different story: that's destructive drift, and
under `eezo dev` the application refuses to serve until you've decided what happens to the data.
[Changing the schema](../how-to/evolve-the-schema.md) walks through that page and the choices on
it.

## Where to go next

The pages you have are public. [Adding sign in](sign-in.md) puts them behind a login and makes
each book belong to whoever created it. Or ship what you have: [the deploy walkthrough](deploy-to-fly.md)
takes this exact application, migrations included, to Fly.io.
