# Design: derivation and schema sync — decision record

**Status:** live. Supersedes `research/derivation-design.md`, which is the
pre-review draft and is now wrong in six places (listed in §3). Read this one.

Part 1 is settled. Part 2 is the open agenda, in the order we're working it.

---

## Part 1 — Settled

### 1. The dev watcher never asks a question

It reports **actions**, not interpretations, because in dev there is no
interpretation to make. `author → authorName` needs the same work whichever it
turns out to mean:

```
ADD COLUMN author_name TEXT NULL
UPDATE book SET author_name = author        -- same type, so the data comes along
ALTER COLUMN author DROP NOT NULL           -- retired, not dropped
```

Nothing is destroyed, so both readings survive to `freeze`. A modal prompt in a
file watcher breaks in a non-tty, goes stale on the next save, and has no sensible
form when two fields are renamed at once.

**Why fidelity doesn't suffer:**

> The dev database's physical schema is not production's and doesn't have to be.
> The migration is derived from the model, never from the dev database.

That licenses whatever keeps the model runnable — including a shadow column for an
incompatible retype (`title: String → Int` becomes `+ title_2 INTEGER`, with the
field mapped to it). The frozen migration says `book.title is INTEGER` because it
comes from the model; `title_2` never appears anywhere. Correctness comes from
`eezo db reset` and CI replaying the real migrations from empty.

**Output format.** `+` added · `~` relaxed or retired · `?` interpretation pending
· `!` eezo did something you should know about. Only `?` and `!` need attention.

```
14:03:22  Book.scala                              compile 180ms   reload 4ms
          + published_on TEXT NOT NULL                                 12ms

14:07:41  Book.scala                              compile 194ms   reload 4ms
          + author_name TEXT NULL  ← copied from author                 9ms
          ~ author retired (NOT NULL dropped)
          ? author → author_name  unresolved, decided at freeze

14:12:08  Book.scala                              compile 176ms   reload 5ms
          ! isbn: String → Int  incompatible; mapped to isbn_2
```

Plus a standing line that reprints only when the pending set changes:

```
  pending: 6 changes · 1 unresolved              eezo db freeze "…"
```

**Idempotency.** The action is `diff(introspect(dev_db), model)`, so firing twice
is a no-op. That matters: `research/build-reload.md` §4.4 measured sbt's watcher
firing **twice** on multi-file edits.

**Empty tables** skip the expand dance — add the column `NOT NULL` directly.

### 2. There is no journal

`freeze` emits `diff(baseline, model)` and shows only final diffs. A journal is not
merely redundant, it asks *wrong* questions: `add foo` then rename to `bar`, both
since the last freeze, makes a journal-aware freeze ask "did you rename foo to
bar?" about a column production has never heard of. The net diff says `+ bar` and
asks nothing.

eezo persists nothing outside the migration files and the two databases. Early
resolutions (`eezo db resolve`) are optional sugar living in a gitignored
`.eezo/dev.json`; drop that command and the state disappears entirely.

### 3. Generated vs hand-written, not schema vs data

Hand-written migrations were always a category — `research/migrations.md`'s
placeholder vocabulary exists for exactly that path. So:

- generated files (from `freeze`) are **uneditable**
- hand-written files (`eezo g migration --sql <name>`) are **yours**
- both are checksummed, both ordered by timestamp, both in the ledger

A non-derivable transformation needs no new mechanism, because retire-don't-drop
already leaves the window:

```
…__split_author_name.sql   generated   add first_name, last_name; retire full_name
…__backfill_names.sql      yours       UPDATE book SET first_name = …
…__contract.sql            generated   drop full_name
```

A *constant* backfill belongs in the model (`default(_.publishedOn, …)` in the
companion), so the generated file stays generated.

### 4. Non-null is the user's responsibility

Type says non-null → column is `NOT NULL`. If that can't be applied to a table with
rows, the migration fails at apply and the user writes a backfill. No prompt, no
expand-then-tighten flow.

Consequence to accept knowingly: `eezo db check` replays from **empty**, and an
empty table always accepts a `NOT NULL` column, so this failure surfaces first on
staging or production. Same trade Rails and Django make.

`-Yexplicit-nulls` makes the mapping total — `String` cannot hold `null`, so
`Option` is the only spelling of nullable and there is no third case. `eezo new`
sets the flag; eezo's own codecs must not *depend* on it, because under explicit
nulls every Java library in the user's tree returns flexible types and that's a tax
eezo would be imposing on code it never sees.

### 5. Retirement relaxes exactly three things

| Constraint on the retired column | Blocks writes? | Action |
|---|---|---|
| `NOT NULL` | **yes** | `DROP NOT NULL` |
| `CHECK (col IS NOT NULL)` | **yes** | drop it |
| `UNIQUE NULLS NOT DISTINCT` (PG 15+) | **yes** | drop the index |
| `UNIQUE`, single or composite | no — NULLs compare distinct | none |
| `CHECK` referencing it | no — fails only on FALSE, and NULL isn't FALSE | none |
| `FOREIGN KEY` | no — not enforced for NULL | none |
| any index | no | none |
| `PRIMARY KEY` | n/a | can't happen; derivation fails with no `Id[Self]` |

Retiring a whole table needs nothing: inbound FKs still point at a table that still
exists, outbound ones are never exercised.

**Hard floor this creates:** `ALTER TABLE … DROP NOT NULL` exists in SQLite only
from **3.53.0**. Below that the only route is the twelve-step rebuild, which the
design rules out. Pin `org.xerial:sqlite-jdbc` at a build bundling ≥ 3.53.0
(`3.53.2.1` is published). This belongs next to the JDK 25 floor.

### 6. Merges are not eezo's problem, so nothing generated goes in git

No `eezo db rebase`. But "let git handle it" only works if there is nothing
generated for git to fail at:

- **No `migrations/schema.json`.** Two independent freezes merge cleanly on
  filenames and then conflict on a generated JSON file users are told not to edit.
  Derive the baseline instead:
  `baseline = normalise(introspect(replay(migrations/*.sql → :memory:)))`.
  Milliseconds; `eezo db check` already does this replay for the down-round-trip
  verification. It's also what Django actually does — its snapshot *is* the
  migration files. Cache it in `.eezo/` keyed on a hash of the migration directory
  (`research/migrations.md` §7's short-circuit).
- **No `-- eezo:parent` / `-- eezo:result` headers.** After a merge the last file's
  recorded result was computed without the other branch's change in it, so every
  merge would fail the deploy gate spuriously.

**The deploy gate is two comparisons, one stored artefact:**

```
sha256(file) == ledger.checksum, per applied migration     → hand-edited or deleted
fingerprint(model) == fingerprint(baseline)                 → freeze not run
```

The second catches the failure the first structurally cannot: you changed the model
and never froze, so every file is byte-perfect and there is no file to be wrong.

**One predicate, three gates,** decreasing skippability: git pre-commit hook
(`--no-verify`), `eezo db check` in CI (skip CI), `eezo deploy` (no escape). The
hook is a **check** — it reads, and never writes or mutates the working tree.

```sh
#!/bin/sh
eezo db check --quiet || {
  echo "eezo: model does not match migrations. Run: eezo db freeze \"<message>\""
  exit 1
}
```

When two migrations genuinely conflict — both add `isbn` — the replay fails or the
model comparison reports it. Correct outcome, user's to fix.

### Freeze, as settled

```
$ eezo db freeze "book data type changed"

  book
    + author_name TEXT NOT NULL   ← author?
    + published_on TEXT NULL
    ~ subtitle retired, not dropped
  author
    + table created

  ? book.author (gone) → book.author_name (new), same type
    [r] rename   [n] new column
  > r

  wrote migrations/20260806143022__book_data_type_changed.sql
```

`eezo db unfreeze` restores dev mode; it must refuse once the migration is
committed, unless forced.

### 7. The query DSL

The target surface, which compiles as written on Scala 3.8.4 / JDK 25:

```scala
Table[Book].where(_.author == "JK Rowling").orderBy(_.datePublished).desc
```

Everything in this section marked **verified** was compiled and run against
3.8.4 before being written down, not reasoned about. The prototype rendered:

```
SELECT * FROM book WHERE (author = ?) ORDER BY date_published DESC
SELECT * FROM book WHERE ((author = ?) AND (date_published > ?))
SELECT * FROM book ORDER BY author ASC, date_published DESC
```

#### 7.1 The selector is a named tuple derived from the case class itself

```scala
type Columns[A] = NamedTuple.Map[NamedTuple.From[A], Col]
```

**Verified.** This is *not* the `MirroredElemLabels` formulation from the first
proposal —

```scala
// DOES NOT COMPILE. Do not retry.
trait Table[A]:
  type Names <: Tuple
  type Columns = NamedTuple[Names, Tuple.Map[Types, Col]]
// → value author is not a member of Book.derived$Table.Columns
```

— because a path-dependent type member does not reduce at the use site.
`NamedTuple.From[A]` does.

Two consequences, both good:

- The field names are not a generated copy that can drift; `NamedTuple.From[Book]`
  **is** `Book`'s field list. Rename a field and every query site is a compile
  error that names the field. A typo reads `value authr is not a member of
  Columns[Book]`. (**Verified.**)
- `Table[A]` needs no type members. It stays a plain trait — name, columns, codec —
  which is the starting point for §8.

SQL column names are snake-cased at derivation; the selector always uses the Scala
field name.

#### 7.2 `==` and `!=` are overloaded on `Col[A]`. There is no `===`

**Verified**: overload resolution prefers `def ==(that: A): Col[Boolean]` over
`Any.==`, and the errors are *better* than `===` would produce.

```
where(author == 3)     Found: (3 : Int)  Required: String     ← points at the literal
```

Three consequences:

- **`!=` must be overloaded alongside `==`.** Omit it and `_.author != "x"` fails
  with `Values of types Col[String] and String cannot be compared with == or !=`,
  which reads like a language restriction rather than a missing method. (**Verified.**)
- **eezo's own code must never use `==` on a `Col`** — it returns `Col[Boolean]`
  now. Use `.equals` or pattern matching. Contained, because `Col` is internal.
- **`Col[Option[B]]` gets sugar.** An extension accepting a bare `B` (so
  `_.subtitle == "x"` works without `Some`), plus explicit `.isNull` / `.isDefined`.
  `== None` must render `IS NULL`, never `= NULL`, which is silently never true.

#### 7.3 `.desc` sits outside the lambda, and costs one type

`orderBy` returns `Sorted[A] extends Query[A]`, carrying `.asc` / `.desc` /
`.thenBy`. **Verified**, including `.orderBy(_.a).thenBy(_.b).desc`.

The alternative spelling `.orderBy(_.x.desc)` needs one fewer class, but the two
cannot coexist: overloaded methods taking lambdas break parameter inference.

`Table[Book]` is the entry point, with `where` as an **extension** on `Table[A]` —
so the typeclass stays data-only and the DSL lives in the query module. A query
with no terminal is a plain value, composable and passable. Repeated `where` = AND.

#### 7.4 Postgres first, with the door left open

**The rule that preserves SQLite: nothing Postgres-specific becomes a node in the
`Expr` tree.** If it is an enum case, both dialects must be able to render it.
`ILIKE`, `DISTINCT ON`, arrays, `ON CONFLICT DO UPDATE`, `NULLS NOT DISTINCT` enter
as raw fragments. Rendering is a separate pass over a dialect-free tree.

Won immediately:

- `RETURNING` is universal on Postgres, so `.returning[T]` has one implementation
  and the `last_insert_rowid()` split disappears for now.
- **`in` renders `= ANY(?)`** — one placeholder, one array parameter, instead of N
  placeholders that blow the statement cache. Strictly better than the
  SQLite-compatible form.
- **`Instant` maps to native `timestamptz`.** This settles the open half of §12.
  Epoch-millis was a portability compromise; Postgres-first, take the native
  timestamp operators and let SQLite pay the cost when it arrives.

Dormant but not wrong: the SQLite 3.53.0 floor from §5. **Retire-don't-drop stays** —
it is about zero-downtime deploys, not about SQLite.

#### 7.5 Magnum: `Frag` is the compile target, eezo owns the surface

Depend only on the dialect-free half — `DbCodec`, `Frag`, `sql"…"`, `DbCon`/`DbTx`,
`Transactor` (`research/db-query-layer.md` §8.1). `@Table`, `Repo` and `Spec` are
ruled out: compile-time dialect constant, `Repo` roughly doubles compile cost per
entity, and `Spec` is stringly typed with no joins. Alias Magnum's types
(`type Frag = magnum.Frag`) so they never appear in user source — that is what keeps
the vendoring fallback cheap (0.46 MB, Apache-2.0, no transitive deps). Users write
`derives Table`, never `derives DbCodec`.

Two escape levels, both landing back in the typed world:

```scala
// predicate-level — stays inside the typed query
Table[Book].where(sql"lower(title) like $pattern").orderBy(_.datePublished).desc

// statement-level — full raw SQL, for joins and aggregates
sql"select b.title, a.name as author_name from book b join author a on b.author = a.id"
  .as[(title: String, authorName: String)].list
```

A named tuple as the ad-hoc row shape means a one-off join needs no declaration, and
it is the same mechanism as the selector. **No join DSL** — raw SQL was measured
331 ms/query cheaper than ScalaSql and 1140 ms cheaper than Quill.

#### 7.6 `.as[T]` is forced, and matches by column label

Magnum's derived `DbCodec` decodes **positionally**, so `sql"select title, author
from book".as[Book]` would read column 1 into `id` — the exact failure the research
condemns in Skunk. Read `ResultSetMetaData` once at prepare, cache per SQL string,
and error with the missing and extra column names.

#### 7.7 `Table[A] <: Row[A]`

`Row[A]` is "readable from and writable to a result set." `Table[A]` is that plus a
table name, a column list and a primary key — strictly more. `.as[T]` needs `Row[T]`,
and `T` arrives in two shapes: ad-hoc named tuples from a join, and real entities.

Subtyping, not composition-plus-a-given, so that `.as[Book]` works with `derives
Table` alone. Writing `derives Table, Row` later is the one edit measured at
O(project size). (A library-side `given [A](using Table[A]): Row[A]` would be legal —
the never-emit-`given`s rule governs generated code in the user's project, not eezo's
own compiled library — but it buys nothing and costs an implicit search per `.as`.)

There is no coherent case where something is a table but not decodable. Whether the
name is `Row` or `Codec` is §10's to settle.

#### 7.8 Reads take `DB`, writes take `Tx`, and `Tx <: DB`

| Terminal | Capability |
|---|---|
| `.list` `.option` `.one` `.count` `.exists` | `using DB` |
| `.insert` `.update` `.delete` `.returning[T]` | `using Tx` |

Because `Tx <: DB`, everything inside `transaction { … }` works and the distinction
is invisible there. A write outside a transaction is a **compile error**, with
`@implicitNotFound` naming the fix (`research/capture-checking.md` §9: plain `using`
+ `@implicitNotFound`, not capture checking).

Requiring `Tx` for reads as well would put `BEGIN`/`COMMIT` around every
single-statement read — two extra round trips where Postgres autocommit needs zero,
on the path every read endpoint takes. What the split gives up is that two separate
reads in one handler are not a consistent snapshot; that is true of every framework,
and `transaction { … }` is the answer when it matters.

Related, and not optional: **writes must require `Tx` because Magnum will not enforce
it.** `Update.run` takes a `DbCon` and silently auto-commits outside a transaction
(read from the 2.0.0-M3 jar). And **`.returning[T]` must be eezo's**, because that is
where the dialect split lives.

---

## Part 2 — Open

**8. What `Table[A]` actually is.** Whether CRUD lives on it or on a separate
`Repo`. The `Id[A]` proposal: `id: Id[Book]` is the primary key and
`author: Id[Author]` *is* the foreign key, with no annotation. How insert works
before an id exists.

**9. Where non-type metadata lives.** Indexes, uniques, checks, `ON DELETE`,
column-name overrides, constant defaults. Proposal is a `Table.Refined[Book]`
companion block providing **no given** — a given there would make editing your
index list an O(dependents) recompile. Open: how the derivation reads it without a
companion-ordering hazard.

**10. The typeclass roster.** `derives Table, Json, Form, Api`. Whether `Json` is
separate from `Table`. Whether `derives Endpoints` mounts routes or only describes
them. The write-once rule: adding to a `derives` clause later is the one edit that
costs O(project size), so the generator writes the full clause up front.

**11. Model discovery.** sbt `sourceGenerators` emitting a plain `val` registry
(never `given`s), globbing `app/models/`. Needs an `sbt-eezo` plugin project that
does not exist in the current eight modules.

**12. The type mapping.** Which Scala types are supported at all. `Instant` is
settled by §7.4 — native `timestamptz`, not epoch-millis. Still open: no
`BigDecimal` (opaque `Money` over `Long` minor units instead), flat models only.

**13. Dev auto-apply safety gate.** Auto-apply must refuse structurally when the
database URL is not the configured dev one.

---

## Part 3 — What changed from `research/derivation-design.md`

Six things in that draft are now wrong. Do not build from it.

| Draft said | Now |
|---|---|
| interactive `[r]/[n]` prompt in the dev watcher | no prompt in dev; asked once at freeze (§1) |
| a dev change journal, squashed at freeze | no journal; `diff(baseline, model)` (§2) |
| `0007.1__…` data-migration sub-files with deploy-halting stubs | plain hand-written migrations, no mechanism (§3) |
| freeze prompts `[d]/[e]` for a new non-null field | emits `NOT NULL`; failure at apply is the user's (§4) |
| retiring a column breaks unique constraints | it doesn't; NULLs compare distinct (§5) |
| `migrations/schema.json` committed; `-- eezo:parent/result` headers | neither; baseline is replayed (§6) |

Still standing from the draft, pending review in Part 2: the `Id[A]`
primary-key-and-foreign-key-from-the-type idea, the `Table.Refined` companion, the
four-typeclass roster, the generated registry, the type mapping table, and the CLI
surface.
