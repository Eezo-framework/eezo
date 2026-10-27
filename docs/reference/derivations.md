# Type mappings

What each field type becomes under `Table`, `Form`, `Resource` and `Api`: the column, the
input, the parse, and what an absent submission means. The types are in [the API](/api/):
[`Field`](/api/io/eezo/http/Field.html), [`Form`](/api/io/eezo/http/Form.html),
[`Resource`](/api/io/eezo/http/Resource.html), [`Api`](/api/io/eezo/http/Api.html),
[`Column`](/api/io/eezo/db/Column.html), [`PgType`](/api/io/eezo/db/PgType.html).

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
checkbox's value is `true` for anything but an empty string or `false`, and the show page and
the index print it as `true` or `false`, not as the `on` a ticked box submits. Numbers are trimmed
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
| `Api` | an `api.Serde` in scope where it derives, plus a field `id: Id[Model]`; the same `Actions`; no `Form` |

Under `Api` a field is whatever the `Serde` makes of it. Through `eezo-upickle`, a case class
with a `ReadWriter` for each field type reads and writes as upickle's derivation does, and a
key is the text `Id.show` gives, the same text it has in a path.

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

## The five JSON routes

What `Api` mounts, under `/api` and the same plural, over the same store as the seven:

| action | route | store call | on success |
|---|---|---|---|
| `Index` | `GET /api/books` | `all()` | 200, a JSON array |
| `Show` | `GET /api/books/:id` | `find(key)` | 200, the row, or 404 |
| `Create` | `POST /api/books` | `insert(key, row)` | 201, the row as stored, `Location` naming it |
| `Update` | `PUT /api/books/:id` | `update(key, row)` | 200, the row as stored; 404 when the row is gone |
| `Destroy` | `DELETE /api/books/:id` | `delete(key)` | 204, no body |

Every route is of the API kind: no session is read, no CSRF token is asked for, and `PUT` and
`DELETE` arrive as themselves. A body that is not JSON is a 400 and JSON that does not fit the
model is a 422, each as `application/problem+json` with the adapter's reason as the detail. A
missing row is the same 404, or the same 403 on an owned model, that the pages answer. The key
is minted on `Create` and read from the path on `Update`, and so is the owner of an owned
model, so a body that names either is overwritten and one that names neither is complete.
There is no `New` and no `Edit`: both serve a form.

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
route serves the target. One `Actions` covers both derivations: subtracting `Destroy` removes
the Delete button and `DELETE /api/books/:id` alike, and subtracting `New` or `Edit` changes
nothing under `/api`.

## Your own types

A form field:

```scala
given Field[Isbn] = Field.of("text")(_.value)(text =>
  Isbn.parse(text).toRight("is not an ISBN")
)
```

Three arguments: the input type, the value as text, and text to either a value or the message
the user sees. A type that has a meaning when the key is missing gives itself a `Parser` whose
`absent` says what that meaning is, and builds its Field on it with
`new Field[X](inputType, show)`.

A column:

```scala
given Column[Isbn] = Column[String].imap(Isbn.unsafe)(_.value)
```

`imap` reuses the underlying type's Postgres type and JDBC calls. `withType(PgType.Varchar(13))`
changes the column type, and `withCheck(Check.MaxLen(13))` adds a check constraint. The checks
are `MinLen`, `MaxLen`, `Positive`, `Between` and `Raw`, where `Raw("{} ~ '^[0-9]+$'")` puts the
quoted column name at `{}`.

## Related

[The case class is the source of truth](../explanation/the-case-class.md) is why the four are
separate. [Migrations](migrations.md) is what a `Column`'s type and checks become in SQL.
[Serve a JSON API](../how-to/serve-a-json-api.md) is the five routes in use, with a
handwritten API route and the token a program sends.
