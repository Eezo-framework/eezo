<!-- draft -->
# From a case class to seven routes

Add one case class deriving `Table`, `Form` and `Resource`, give it a schema, and browse the seven CRUD pages it mounts over rows in Postgres.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- Starting point: the application from the first tutorial, a dev Postgres in Docker, the connection settings eezo reads by default
- The model
  - `case class Book(id: Id[Book], title: String, author: String, pages: Int) derives Table, Form, Resource`
  - why `id: Id[Book]` is required for `Resource` and not for `Form`
  - which field types derive out of the box, and what each becomes as a column and as an input
- The schema
  - `object AppSchema extends Schema { val books = table[Book] }` and naming it in `Main`
  - `.unique(_.title)` and `.index(...)`: typed selectors, a renamed field fails at compile time
- The table has to exist first
  - `eezo status` shows the drift; `eezo sync --apply` for a throwaway database
  - or the reviewed path: `eezo freeze initial schema` then `eezo migrate --apply`
  - what the drift page under `eezo dev` offers instead of the terminal
- Browse the seven pages
  - `/books`, `/books/new`, `/books/:id`, `/books/:id/edit`, and the three writes
  - what the derived list, show, and form pages look like and what they will not do
  - the 404 for a missing row, the 400 for a bad id
- Change the model: add `Option[String] notes`, see additive drift, freeze it, migrate it
- Take over one page: a handwritten `app/books/Index.scala` beats the derived index (link the how-to)
- Where to go next: sign in, mounting under a prefix, deploying

## Where the material is

- `examples/todo/README.md`: the same arc on the todo model, with captured output
- `examples/blog/src/main/scala/models/Post.scala` and `AppSchema.scala`
- `modules/http/src/main/scala/io/eezo/http/Resource.scala`, `Form.scala`, `Field.scala`
- `modules/db/src/main/scala/io/eezo/db/Table.scala`, `Schema.scala`
