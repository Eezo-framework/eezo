# Routing conventions

What a file under `src/main/scala/app/` mounts, how the generated table is ordered, and what
the framework reserves. `Route`, `RouteTable` and `PathPattern` are in [the API](/api/io/eezo/http/RouteTable.html).
[Routing by file layout](../explanation/routing.md) is the reasoning.

## The file name table

| file | method | `def` called | path suffix |
|---|---|---|---|
| `Index.scala` | `GET` | `index` | |
| `New.scala` | `GET` | `` `new` `` | `/new` |
| `Show.scala` | `GET` | `show` | |
| `Edit.scala` | `GET` | `edit` | `/edit` |
| `Create.scala` | `POST` | `create` | |
| `Update.scala` | `PUT` | `update` | |
| `Destroy.scala` | `DELETE` | `destroy` | |
| any other `Name.scala` | `GET` | `name`, decapitalised | `/name`, lower-cased |

The `def` lives on the object the file declares, `app.<dirs>.<FileName>`, takes a `Request` and
returns a `Response`. A custom name is always a `GET`; a custom `POST` is a `Create.scala` in
a directory of its own, `app/health/Create.scala` for `POST /health`.

## Directory segments

| directory | segment |
|---|---|
| `books` | the literal `books` |
| `_id` | `:id`, captures one segment |
| `__rest` | `*rest`, captures everything left, including nothing |

A catch-all anywhere but last, and a parameter name used twice in one pattern, fail the boot.
A trailing slash is ignored on both the pattern and the path.

## Emit order

Handwritten routes first, sorted most static first: segment by segment, a literal before
`:name` before `*rest`; a shorter path after a longer one when the ranks tie; then by path
text, then by method. Derived routes follow, model by model in fully-qualified-name order,
each model's seven in the order `Index, New, Show, Edit, Create, Update, Destroy`. The table
is matched in that order and never re-sorted.

## Precedence

| situation | outcome |
|---|---|
| a handwritten and a derived route on one method and path | the handwritten one is served, the derived one is dropped, a warning at boot and in `eezo routes` |
| an earlier pattern matches everything a later one would | legal; a warning naming both |
| a derived form page whose submit target isn't mounted | legal; a warning naming the page and the target |
| two handwritten or two derived routes on one method and path | the boot fails |
| a path matches but no method does | 405, with `Allow` |
| nothing matches | 404 |

## The generated file

`target/scala-<version>/src_managed/main/io/eezo/generated/Routes.scala`, package
`io.eezo.generated`, object `Routes`, one member you call: `def table(): RouteTable`. Every
row carries `// from <source file>`. A compile error on a row means the `def` the row names
doesn't have the handler's shape, and the fix is in the file the comment names.

The file also holds, when there are rows that need them:

- `storeFor[A]`, which picks each derived model's store: a scoped one for a model declaring
  `Owned`, a `JdbcStore` for a model with a `Table` when `eezo-db` is on the compile classpath,
  an `InMemoryStore` otherwise.
- `guardFor[A]`, which looks up the model's or the page's `Guarded`. When the build names
  `eezo-auth` in a configuration the compiler sees, no `Guarded` in scope is a compile error
  naming the type; otherwise it's `Guarded.public`.
- `identify`, the composition of every declaration's naming, which the table runs on a socket
  upgrade.

A candidate model is any `case class` with a `derives` clause, at the top level or nested only
in `object`s. One nested in a class, a trait or a block, or indented in a file that uses
significant indentation, gets a warning and no row.

## The reserved prefix

`/eezo` is the framework's, on every server:

| path | what | when |
|---|---|---|
| `GET /eezo/health` | `ok`, 200, no cookie read or written | always; asked before your table |
| `WS /eezo/reload` | the dev server's reload socket | `dev` only; asked before your table |
| `WS /eezo/live/:page` | the live socket | `LiveApp`; appended after your table |
| `GET /eezo/live.js` | the live client | `LiveApp`; appended after your table |
| `POST /eezo/sync`, `POST /eezo/freeze` | the drift page's actions | only while the drift page is served |

A guard's own routes are not under the prefix: `GET /login`, `POST /login` and `POST /logout`
by default, relative to the mount.

## Related

[URLs and mounts](../explanation/urls-and-mounts.md) is what `Route.under` does to a table.
