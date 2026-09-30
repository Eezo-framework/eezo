<!-- draft -->
# Type mappings

What each field type becomes under `Table`, `Form` and `Resource`: the column, the input, the parse, and what an absent submission means.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done. The types and members themselves
> are documented in [the generated API](/api/); this page holds what scaladoc cannot say.

## What to cover

- a table, one row per supported field type: Postgres column, HTML input type, rendered text, parsed value, absence
- `Id[T]`, `Option[A]`, `Boolean`, `Int`, `Long`, `Double`, `String`, `UUID`, dates and times, `Password`
- what `Resource` requires of the case class (`id: Id[T]`) and what `Form` does not
- the seven routes a `Resource` mounts, as a table of method, path and store call
- `Action`: the enum and what subtracting one removes
- writing a `Field[A]` and a `Column[A]` for your own type: the two instances and their contracts
- the `Field`, `Column`, `PgType` and `Form` types themselves are in the API: link them

## Where the material is

- `modules/http/src/main/scala/io/eezo/http/Field.scala`, `Form.scala`, `Resource.scala`, `Actions.scala`
- `modules/db/src/main/scala/io/eezo/db/Column.scala`, `PgType.scala`, `Table.scala`
