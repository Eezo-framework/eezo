# Type mappings

What each field type becomes under `Table`, `Form` and `Resource`: the column, the input, the
parse, and what an absent submission means. The types are in [the API](/api/):
[`Field`](/api/io/eezo/http/Field.html), [`Form`](/api/io/eezo/http/Form.html),
[`Resource`](/api/io/eezo/http/Resource.html), [`Column`](/api/io/eezo/db/Column.html),
[`PgType`](/api/io/eezo/db/PgType.html).

## Field types

| Scala type | `Column`: Postgres type | `Field`: input type | absent submission | parse failure |
|---|---|---|---|---|
| `String` | `text` | `text` | required | |
| `Int` | `integer` | `number` | required | "is not a number" |
| `Long` | `bigint` | `number` | required | "is not a number" |
| `Double` | none | `number` | required | "is not a number" |
| `Boolean` | `boolean` | `checkbox` | `false` | |
| `BigDecimal` | `numeric(19,4)` | none | | |
| `UUID` | `uuid` | `text` | required | "is not an id" |
| `Id[T]` | `uuid`, primary key when named `id` | `text` | required | "is not an id" |
| `Ref[T]` | `uuid`, a foreign key to `T`'s table, column named `<field>_id` | none | | |
| `LocalDate` | `date` | `date` | required | "is not a date" |
| `LocalDateTime` | none | `datetime-local` | required | "is not a date and time" |
| `Instant` | `timestamptz` | `datetime-local` | required | "is not a date and time" |
| `Array[Byte]` | `bytea` | none | | |
| `Option[A]` | `A`'s type, nullable | `A`'s input | `None`; empty text is also `None` | `A`'s |
| `Password` (`eezo-auth`) | written by you, `Column[String].imap(Password.stored)(_.value)` | `password`, hashed on read, never shown | required | "is longer than the 72 bytes bcrypt reads" |
| `Password.Plain` | none | `password`, kept as typed | required | same |

"Required" means the field reports "is required" when its key is missing from the body. A
checkbox's value is `true` for anything but an empty string or `false`. Numbers are trimmed
before parsing. A `Field` whose column is "none" works in a form and can't be a column of a
table; a `Column` whose input is "none" can be stored and can't appear in a form.

The key is never rendered as an input. `Form.parse` takes it from the caller: a minted one on
create, the path parameter on update.

## Labels and names

An input's `name` is the field's exact Scala label. Its label text is humanised:
`publishedOn` becomes "Published on", with no dictionary. A table's name is the class name in
snake case, and so is each column's. A resource's path is the snake-cased class name
pluralised: `+s`, a consonant before `y` becomes `ies`, a sibilant takes `es`. `blog_post`
mounts at `/blog_posts`.

## What a model has to be

| derivation | requires |
|---|---|
| `Table` | a case class, one parameter list, no type parameters, a field `id: Id[Model]`, a `Column` for every field type |
| `Form` | a case class with a `Field` for every field type; no key needed |
| `Resource` | `Form`, plus a field `id: Id[Model]`; `Actions` defaults to all seven |

## The seven routes

| action | route | store call | on success |
|---|---|---|---|
| `Index` | `GET /books` | `all()` | 200 |
| `New` | `GET /books/new` | | 200 |
| `Show` | `GET /books/:id` | `find(key)` | 200, or 404 |
| `Edit` | `GET /books/:id/edit` | `find(key)` | 200, or 404 |
| `Create` | `POST /books` | `insert(key, row)` | 303 to show, or to the index when `Show` isn't mounted; 422 on a rejected form |
| `Update` | `PUT /books/:id` | `update(key, row)` | 303 to show; 404 when the row is gone; 422 on a rejected form |
| `Destroy` | `DELETE /books/:id` | `delete(key)` | 303 to the index; 404 when the row is gone |

A `:id` that isn't a UUID is a 400. `PUT` and `DELETE` arrive as a `POST` with a hidden
`_method` field, which the form and the delete button carry.

## Subtracting an action

```scala
object Book {
  given Actions[Book] = Actions.except(Action.Destroy)   // or Actions.only(...)
}
```

Subtracting a role removes its route and every control that pointed at it: no Delete button
without `Destroy`, no Edit link without `Edit`, no "New" link without `New`. Subtracting `Show`
sends a successful write to the index. Subtracting `Create` or `Update` while keeping `New` or
`Edit` mounts a form page whose submit answers 405, and boot warns about it unless a handwritten
route serves the target.

## Your own types

A form field:

```scala
given Field[Isbn] = Field.of("text")(_.value)(text =>
  Isbn.parse(text).toRight("is not an ISBN")
)
```

Three arguments: the input type, the value as text, and text to either a value or the message
the user sees. Override `absent` for a type that has a meaning when the key is missing.

A column:

```scala
given Column[Isbn] = Column[String].imap(Isbn.unsafe)(_.value)
```

`imap` reuses the underlying type's Postgres type and JDBC calls. `withType(PgType.Varchar(13))`
changes the column type, and `withCheck(Check.MaxLen(13))` adds a check constraint. The checks
are `MinLen`, `MaxLen`, `Positive`, `Between` and `Raw`, where `Raw("{} ~ '^[0-9]+$'")` puts the
quoted column name at `{}`.

## Related

[The case class is the source of truth](../explanation/the-case-class.md) is why the three are
separate. [Migrations](migrations.md) is what a `Column`'s type and checks become in SQL.
