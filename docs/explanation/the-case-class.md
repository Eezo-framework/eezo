# The case class is the source of truth

This page is for you if you've been through [the first model](../tutorials/first-model.md) and
want to know why one `derives` clause gets you a table, a form and seven routes, where each of
the three stops, and why they're three derivations and not one. Nothing here is needed to use
eezo. It's the reasoning behind the feature you've already used.

## What gets written three times

Say you're building a bookshelf. A book has a title, an author and a page count. In most web
frameworks that sentence ends up in your code three times:

- as columns, in a table definition or a migration;
- as inputs, in a form template, plus the code that reads the submission back;
- as handlers, one per page: list, show, new, create, edit, update, delete.

Each of the three is the field list again, in a different syntax, and they drift. A column gets
added and the form doesn't. The form gets a field the handler never reads. eezo's answer is to
write the field list once, as a case class, and derive the other three from it:

```scala
case class Book(
    id: Id[Book],
    title: String,
    author: String,
    pages: Int
) derives Table,
      Form,
      Resource
```

Each name in the `derives` clause is a typeclass instance the compiler builds from the fields.
Add a field, and all three pick it up on the next compile.

## What each derivation knows

**`Table`** knows the database. From the class it takes a table name (`book`, the class name in
snake case) and one column per field, with the Postgres type coming from a `Column[A]` instance
for the field's Scala type: `String` is `text`, `Int` is `integer`, `Id[T]` is `uuid`,
`Option[A]` is a nullable column of whatever `A` is. It also builds the code that writes a row
into a `PreparedStatement` and reads one back from a `ResultSet`, by column offset, so the
select list and the decoder can't disagree. It insists on a field named `id` of type
`Id[Book]`, and refuses to compile without one. What it knows nothing about is HTTP: no
paths, no inputs, no verbs.

**`Form`** knows the browser. One `<input>` per field, with the input type picked by a
`Field[X]` instance: `Int` is a number input, `LocalDate` a date input, `Boolean` a checkbox.
It renders the whole `<form>` element, submit button included, and it reads a submission back
into a `Book`, reporting per field what wouldn't parse ("is not a number") or was missing ("is
required"). The key is never rendered as an input. A hidden `id` field is one the user can
edit, so the key arrives from the caller instead: a freshly minted one on create, the path
parameter on update. What `Form` knows nothing about is where the form posts, what verb, what
happens on success, or where the data goes.

**`Resource`** knows the routes. Seven of them, named by role:

| role | route |
|---|---|
| Index | `GET /books` |
| New | `GET /books/new` |
| Show | `GET /books/:id` |
| Edit | `GET /books/:id/edit` |
| Create | `POST /books` |
| Update | `PUT /books/:id` |
| Destroy | `DELETE /books/:id` |

The path is the class name, lower-cased and pluralised. Every handler `Resource` writes calls
`Form` to render or to parse, and calls a `Store[Book]` for the rows. It owns the redirects (a
successful write lands on the show page, or the index when show isn't mounted), the 404 for a
missing row, and the 422 that sends a rejected form back with the typing intact. It needs the
`id` field too, because `create` has to mint a key before it can insert anything.

Notice that `Resource` doesn't need `Table`. The store arrives as a parameter when the routes
are built, and the generated route table is where the choice is made: a model with a `Table`
gets a store over Postgres, a model without one gets a store in memory. That's why `derives
Form, Resource` with no database in the build still mounts seven working routes.

## Why Form and Resource are two things

Three shapes come out of the split:

- **`Form` alone.** A handwritten route renders the form and reads the submission itself, and
  decides its own path, verb and outcome. A login form is the usual case: `Login(email,
  password)` has no `id`, will never be a row, and mounts nothing.
- **Both.** Seven routes, no handwritten code, each doing what the handwritten version would.
- **Both, minus an action.** The model subtracts a role in its companion, say `Actions.except(Destroy)`,
  and a handwritten route takes it over. The handwritten half still calls the same `Form`, so
  the page that emitted the inputs and the handler that reads them can't disagree.

One merged derivation would break the first shape, because a model with no key can't mount
seven routes, and it would leave the third shape with no `Form` for the handwritten half to
call. So they stay apart, and `Resource` requires a `Form` to exist.

## Field: one type, one input

`Field[X]` is the small piece under `Form`: for one Scala type, the input type, how to show a
value as text, and how to read text back. It has a fourth member, `absent`, that says what it
means when the key isn't in the submission at all. A browser leaves an unchecked checkbox out
of the body entirely, so `Field[Boolean].absent` is `Some(false)`; an empty optional field is
`None`, so `Field[Option[X]].absent` is `Some(None)`; for everything else it's `None`, and
those are exactly the fields that can say "is required".

`Field` is not `Column`. A model with no table still has to form, and `Login` is
the proof. The two instances for one type live in different modules and never see each other.

## What derivation costs

The `derived` methods are `inline` only long enough to read the compiler's `Mirror`, the
labels and the field types. The instance itself is built by an ordinary method that's compiled
once, so a project with forty models doesn't carry forty copies of the form renderer. `Table`
is a macro for the same reason: it has to generate the encoder and decoder as code, and it does
that once per model.

## What is not derived

- **Who may do what.** Authorization is a declaration you write beside the model, `given
  Guarded[Book]` or `given Owned[Book, User]`, and it's covered in
  [guards and ownership](guards-and-ownership.md). The case class says what a book is, not who
  may edit one.
- **Presentation.** A derived page is a plain HTML envelope with the fields in it. No
  stylesheet, no layout of yours. When one of the seven needs to look different, you write
  that page and keep the other six.
- **Relations.** A `Book` with an `Id[Author]` field stores a foreign key, and the schema
  commands emit the constraint. Nothing derives a join, a nested form, or an author's list of
  books. That's a handwritten route over the query layer.

## Where to go next

The three derivations sit on two sides of the framework, and which sides your application has
is the subject of [the two edges](edges.md). How the seven routes become rows in a table, and
how a handwritten route wins over one of them, is [routing](routing.md).
