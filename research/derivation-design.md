# Design: the derivation module, and how the case class proves the database

Not research. This is a design proposal that sits on top of
`research/migrations.md`, `research/db-query-layer.md`, `research/build-reload.md`,
`research/http-server.md`, `research/capture-checking.md` and
`research/deploy-target.md`. Every load-bearing claim below cites the document and
section that measured it. Where I am proposing something nothing measured, it is
marked **decision needed**.

Written 2026-08-06.

---

## 0. The three prior findings that constrain the whole design

Everything here is downstream of three measurements that are already in the repo.
If you disagree with the design, disagree with one of these first.

**0.1 Changing a `derives` clause is the only edit whose cost is O(project size).**
`build-reload.md` §1.1 and §4.3. Adding a *field* to a case class that four
typeclasses derive recompiles **one file**, at 97 ms on a 24-file project and
129 ms on a 504-file project. Adding a *fifth typeclass to the clause* recompiles
**401 files at 766 ms**, extrapolating to ~2.7 s at 1600 dependents. The cause is
one branch in Zinc's `MemberRefInvalidator`: any modified name in
`UseScope.Implicit` turns name hashing off and invalidates every member-reference
dependent unconditionally. A `derives Foo` clause generates `given Foo[User]` in
the companion, so the *set* of derived typeclasses is exactly what lives in that
scope.

Consequence, and it is the single most important design rule in this document:
**the derives clause must be written once, in full, at model creation, and never
edited again.** Design so that the clause a generator writes on day one is the
clause the model still carries a year later. That rules out an incremental
"add `derives Api` when you're ready to expose it" workflow, and it rules out a
long menu of small typeclasses the user opts into one at a time.

**0.2 eezo owns naming, or naming diverges silently.**
`db-query-layer.md` §6.5 and §8.1/8.2. Magnum's `@Table(dbType, SqlNameMapper)`
and ScalaSql's `SimpleTable` both own table and column naming, which is knowledge
eezo's schema model also has; if they disagree, DDL and DML diverge and nothing
catches it. Magnum's dialect is a compile-time annotation constant and mismatches
surface at runtime, in the worst case as a bare `UnsupportedOperationException`
with a null message on SQLite `insertReturning`. The recommendation there is to
depend on the dialect-free half of Magnum only — `DbCodec`, `Frag`, the `sql`
interpolator, `DbCon`/`DbTx`, `Transactor` — and for eezo to generate its own CRUD
from its own schema model.

Consequence: **`Table[A]` is the single source of naming**, and the query layer,
the DDL renderer, the migration differ and the boot-time verifier all read from
that one value. Nothing else is allowed to know a table name.

**0.3 The migration engine is eezo's, the primary mechanism is a typed schema
model rendered per dialect, and the diff is against a committed snapshot.**
`migrations.md` §8. Placeholders are demoted to an escape hatch for hand-written
SQL. The snapshot is a file in the repository, not the live database, because
diffing against a live database requires it to be reachable and correct and gives
different answers in dev and prod (§5, following Django).

---

## 1. `derives Table`

### 1.1 What it produces

```scala
case class Book(id: Id[Book], title: String, author: Id[Author], published: Instant)
  derives Table, Json
```

`Table[Book]` is a plain value, not a bag of macros:

```scala
trait Table[A]:
  def name: String                     // "book"
  def columns: List[Column]            // name, ColType, nullable, default
  def primaryKey: Column
  def foreignKeys: List[ForeignKey]
  def refinements: List[Refinement]    // indexes, uniques, checks — see §1.4
  def codec: DbCodec[A]                // Magnum's, derived inside
  def fingerprint: String              // §2.2
  // CRUD, emitted as Frags, dialect resolved at render time
  def insert(a: A)(using Tx): Id[A]
  def update(a: A)(using Tx): Unit
  def delete(id: Id[A])(using Tx): Unit
  def find(id: Id[A])(using DB): Option[A]
  def all(using DB): Vector[A]
  def where: Query[A]                  // the typed predicate builder
```

Two mechanical notes from prior measurement:

- `DbCodec` is derived *inside* `Table.derived`, not listed separately in the
  clause. It is an implementation detail of how a row maps to storage and the user
  should never name it. This also keeps the clause one entry shorter, per §0.1.
- `inline def derived` must **not** return an anonymous class. `db-query-layer.md`
  §9 records the compiler warning "New anonymous class definition will be
  duplicated at each inline site". Shape it as
  `inline def derived[A](using m: Mirror.ProductOf[A]): Table[A] = Table.make(nameOf[A], columnsOf[A], DbCodec.derived[A])`
  where `make` is an ordinary method returning a final class.
- No chained `transparent inline`. `build-reload.md` §8.2 documents
  [scala/scala3#25728](https://github.com/scala/scala3/issues/25728), open, where
  each additional chained transparent inline operation roughly doubles PostTyper
  time — 1.0 s at 10 links, ~90 s at 15. Use plain `inline` and concrete result
  types throughout the derivation.

### 1.2 The type mapping, and it is deliberately small

Taken from `migrations.md` §2, which established construct by construct what is
genuinely irreconcilable between SQLite and Postgres and what is not.

| Scala | SQLite (STRICT) | Postgres | Note |
|---|---|---|---|
| `String` | `TEXT` | `text` | never `varchar(n)`; §2.6 measured that length is accepted and unenforced on SQLite, which is the exact dev/prod divergence eezo exists to remove |
| `Int` | `INTEGER` | `integer` | |
| `Long` | `INTEGER` | `bigint` | |
| `Boolean` | `INTEGER` | `boolean` | §2.4 |
| `Double` | `REAL` | `double precision` | |
| `Instant` | `INTEGER` | `bigint` | epoch millis on **both**. See below |
| `UUID` | `TEXT` | `text` | §2.5's simpler policy |
| `Id[A]` | `INTEGER PRIMARY KEY` / `INTEGER` | `bigint GENERATED BY DEFAULT AS IDENTITY` / `bigint` | §2.1 |
| `Option[X]` | X, nullable | X, nullable | |
| `enum E` (simple) | `TEXT` + `CHECK (col IN (...))` | `text` + same check | |
| `Array[Byte]` | `BLOB` | `bytea` | |

Deliberately **absent**: `BigDecimal`, `LocalDate`, `LocalDateTime`, nested case
classes, collections, `Map`. Reasons, each of which is a real trap:

- `BigDecimal` has no honest portable spelling (`numeric` on Postgres, `TEXT` with
  lexicographic ordering on SQLite). Ship an opaque `Money` over `Long` minor units
  instead and let people who want `numeric` write the column by hand.
- Nested case classes tempt a recursive derivation over `MirroredElemTypes` with
  `summonInline` per field, which `build-reload.md` §8.1 flags as inductive given
  resolution and superlinear in *depth*. eezo's models are flat. Keep them flat, and
  when someone needs structure, that is a `Json` column or a second table.
- `LocalDate`/`LocalDateTime` are timezone traps disguised as convenience.

**Decision needed: `Instant` as epoch millis on both sides.** `migrations.md` §2.3
is unusually direct about this — storing epoch millis in an integer column
"sidesteps the entire problem: an INTEGER on both sides, ordering identical,
arithmetic identical, no timezone semantics anywhere", and calls it "the more
robust of skiff's two timestamp strategies". The cost is that you lose native
Postgres timestamp operators, so `created_at > now() - interval '7 days'` in raw
SQL stops being available and has to go through the query builder
(`Book.where(_.published after Instant.now.minus(7, DAYS))`). I recommend paying
that cost, because the alternative is that the read path and the write path have
to agree across two dialects and §2.3 shows placeholders only ever fixed the write
path's DDL. Sign this off explicitly; it is the one mapping that will be argued
about.

### 1.3 Identity and foreign keys carry themselves in the type

This is where most of the annotation pressure goes away.

```scala
opaque type Id[A] = Long
```

- A field typed `Id[Book]` **in `Book`** is the primary key. There is no `@Id`.
- A field typed `Id[Author]` **in `Book`** is a foreign key to `author.id`. There
  is no `@ForeignKey` and no `references` string anywhere. The derivation reads the
  type parameter and emits the constraint.
- The types do not unify, so passing an `Id[Author]` where an `Id[Book]` is wanted
  is a compile error. That is free value from a decision made for another reason.

Insert with an unassigned id: `Table.insert` ignores the primary key column and
returns the assigned `Id[A]`. Use `Id.unassigned` as the placeholder. This
deliberately avoids the separate "entity creator" case class that Magnum's `Repo`
requires: `db-query-layer.md` §3.2 measured that `Repo` roughly *doubles* Magnum's
marginal compile cost per entity (+70.6 ms against +41.5 ms), and §1 found skiff
used `Repo` twice against 224 uses of the `sql` interpolator. Do not make users
declare two case classes per table to save one sentinel.

The `RETURNING id` versus `last_insert_rowid()` split is absorbed inside
`Table.insert` behind one signature. `db-query-layer.md` §8.2 calls this "the
single most important piece of the recommendation, because it is the only place
the portability promise can actually be kept."

### 1.4 What the type genuinely cannot express, and where it goes instead

Indexes, uniqueness, check constraints, `ON DELETE` behaviour, column defaults and
a non-default table or column name. These are not properties of a Scala type and
pretending otherwise is what produces annotation soup.

They go in the companion object, as an ordinary value, never as an annotation:

```scala
object Book extends Table.Refined[Book]:
  index(_.author)
  unique(_.isbn)
  onDelete(_.author, Cascade)
  check("published_after_1450", _.published > Instant.parse("1450-01-01T00:00:00Z"))
```

Three properties that matter:

- The case class declaration stays one line and stays clean, which is the stated
  requirement.
- Every selector is a real function on `Book`, so a renamed field is a compile
  error here, not a silently stale string.
- **`Table.Refined` provides no `given`.** It carries a `refinements: List[Refinement]`
  value only. This is not fussiness: putting a given in the companion via
  inheritance would put a name in `UseScope.Implicit` (§0.1) and make editing your
  index list an O(dependents) recompile.

The merge of `Table[Book]` with `Book.refinements` happens in the generated
registry (§2.1), at application-assembly time, not inside the derivation macro.
That avoids the macro having to inspect a companion that is still being elaborated,
which is a known way to get non-deterministic derivation behaviour.

### 1.5 The full annotation-elimination table

The requirement was "keep the case classes clean of annotations when changes
happen". Here is every piece of metadata a JPA/Ecto/ActiveRecord model normally
carries, and where eezo puts it.

| Normally an annotation | eezo puts it |
|---|---|
| `@Table("books")` | derived from the class name; override in `Table.Refined` |
| `@Column("published_at")` | derived, camelCase → snake_case; override in `Table.Refined` |
| `@Id` | the field typed `Id[Self]` |
| `@GeneratedValue` | implied by `Id[Self]` |
| `@ManyToOne` / `@JoinColumn` | the field typed `Id[Other]` |
| `@Nullable` / `nullable = true` | `Option[X]` |
| `@Index`, `@UniqueConstraint`, `@Check` | `Table.Refined` companion block |
| `@Transient` (do not persist) | it is not a field on the case class |
| rename hint (`was: "name"`) | **the generated migration file**, see §3.3 |
| `@Version` optimistic lock | a field typed `Version` (opt-in) |
| `@Enumerated(STRING)` | it is a Scala 3 `enum`; there is no other option |

The one row that carries the whole answer to "how do I stay clean when things
change" is the rename hint. Every framework that keeps models clean has to put
migration intent *somewhere*, and the only two candidates are the model file or
the migration file. Django puts it in the migration file and asks the user
interactively at generation time. eezo should do the same (§3.3). It is the right
place because a rename is an event that happened once, not a property of the type,
and a property recorded in the migration file gets reviewed in a pull request and
then never has to be read again.

### 1.6 Naming rules, stated once so they never have to be looked up

- Table name: the class name, camelCase → snake_case, **singular**. `Book` → `book`,
  `BlogPost` → `blog_post`.
- **No pluralisation, ever.** Pluralisation is an inflection engine, an irregular
  word list, and a per-language argument, in exchange for nothing. URLs are plural
  because URLs are a convention, and the URL is a string the user writes at the
  mount site anyway (§5.4).
- Column name: field name, camelCase → snake_case.
- Index name: `ix_<table>_<cols>`; unique: `ux_<table>_<cols>`; FK:
  `fk_<table>_<col>`; check: `ck_<table>_<name>`. Always named, never anonymous —
  `migrations.md` §2.7 records that an unnamed `CREATE INDEX` is legal Postgres and
  illegal SQLite, and that this is the exact hole Liquibase falls through.

---

## 2. The three artefacts, and the invariant that ties them together

This is the core of "prove the database is in sync with my data type."

### 2.1 The model schema

Assembled at application start from a **generated registry**, not from classpath
scanning or reflection.

`build-reload.md` §3.3 is explicit that sbt's `Compile / sourceGenerators` is the
only mechanism in the field that is both standard and incrementally aware, and it
carries a warning that has to be obeyed: *"if the generated route table contains
`given` values, every edit that changes the set of those givens will invalidate
every file that references the route table. Generate a plain `val`, not a family
of givens."* The same rule applies to the model registry.

So the eezo sbt plugin globs `app/models/*.scala` and emits:

```scala
// generated — do not edit
object EezoSchema:
  val tables: List[Table[?]] = List(
    summon[Table[Book]].refinedBy(Book.refinements),
    summon[Table[Author]].refinedBy(Author.refinements)
  )
  val fingerprint: String = "sha256:2b1f…"
```

Reflection-free, no classpath scan, and it keeps application initialisation inside
a fresh classloader cheap — which `build-reload.md` §10 names as **the single
unmeasured term** in the reload budget and the weakest link in its own
recommendation. A framework that builds its schema by scanning the classpath at
boot is exactly the failure it warns about.

Escape hatch for models kept elsewhere: an explicit `eezoModelSources += file(...)`
setting.

**Gap in the current build.** `build.sbt` declares eight modules and none of them
is an sbt plugin. The registry generator, the dev-server command and the
`eezo`-CLI-to-sbt bridge all need one. An `sbt-eezo` project has to exist, and per
`build-reload.md` §3.1 it may need cross-publishing to the `_sbt2_3` suffix if eezo
ever moves to sbt 2.x. Worth an issue now, before the module layout hardens.

### 2.2 The snapshot, and the fingerprint

`migrations/schema.json`, committed, dialect-agnostic — following `migrations.md`
§5, which recommends skiff's approach of diffing the model against a committed
JSON snapshot rather than against the live database, and notes Django does the
same.

The **fingerprint** is SHA-256 over a canonical serialisation of the schema:
tables sorted by name, columns sorted by name, each carrying
`(name, colType, nullable, default, pk?)`, then foreign keys, indexes and checks,
also sorted. It must be stable across dialects, across Scala versions and across
JVM iteration order. It deliberately excludes anything that does not affect
correctness (comments, column ordering as declared, storage hints).

Per-table and per-column fingerprints are stored alongside the whole-schema one.
That is what makes the boot-time error message precise instead of "schema
mismatch" (§4.2).

### 2.3 The ledger

The `eezo_migrations` table. `migrations.md` §8 recommends reusing Flyway's
`flyway_schema_history` shape (version, description, script, checksum,
installed_by, installed_on, execution_time, success) under a different name,
because it is a proven design and it keeps migration off-ramps open. Add two
columns:

- `fingerprint` — the schema fingerprint **after** this migration was applied.
- `snapshot` — the full canonical schema JSON after this migration. A few KB.

The `snapshot` column is what lets the boot check be structural and precise without
introspecting the database, and without a second round trip.

Two non-negotiables from `migrations.md`:

- **Checksum the raw migration file, never the resolved SQL** (§3.1, verified from
  Flyway's `SqlMigrationResolver`). Placeholder-resolved SQL differs per dialect, so
  a resolved-SQL checksum makes dev and prod disagree, and the failure surfaces late
  and confusingly.
- **Lock properly.** `pg_advisory_lock(<constant>)` on a dedicated connection for
  Postgres, released automatically when the session ends, so a SIGKILLed pod does
  not wedge every subsequent deploy (§6.2, contrasted with Liquibase's
  `DATABASECHANGELOGLOCK` row, which does). `BEGIN IMMEDIATE` for SQLite. Both
  targets have transactional DDL — §2.10 verified this on SQLite directly rather
  than trusting Flyway's FAQ, which omits it — so a failed migration leaves no
  partial schema on either.

### 2.4 The invariant

```
  A.  fingerprint(model)  ==  fingerprint(snapshot)     — code agrees with migration history
  B.  ledger.latest.fingerprint  ==  fingerprint(snapshot)  — the database has run that history
  ─────────────────────────────────────────────────────
  ⇒   the database is in sync with the data types
```

`A` is checkable with **no database at all** — in CI, at compile time, in a
pre-commit hook. `B` costs one `SELECT` at boot. Together they are the proof the
requirement asked for, and neither requires introspecting the live schema.

Introspection (`information_schema` on Postgres,
`SELECT type, name, sql FROM sqlite_schema` on SQLite) is the **deep** check. It
catches the one thing `A ∧ B` cannot: someone ran DDL by hand. It is a separate
command (`eezo db verify --deep`), not on the boot path, because it costs a
round trip per table and its failure mode is operational rather than a code bug.

---

## 3. The workflows

### 3.1 A new case class that is supposed to be a table

1. `eezo g model Book title:String author:Id[Author] published:Instant`, or write
   the file by hand. The generator writes the **complete derives clause** (§4) into
   `app/models/Book.scala`, because §0.1 makes adding to it later expensive.
2. The sbt source generator picks the file up and adds `Book` to `EezoSchema`.
3. The dev server computes `diff(snapshot, model)` = `CreateTable(book)`. Purely
   additive, so it applies to the dev database immediately and reports it in the
   reload banner. The app is usable in the same reload cycle.
4. When you are ready to commit: `eezo g migration add_book` writes
   `migrations/0007__add_book.sql` (up and down), rewrites `migrations/schema.json`,
   and stamps the new fingerprint into the migration header. Commit the model, the
   migration and the snapshot together — they are one change.

The important property is that step 3 never blocks on step 4. You do not have to
think about migrations while you are still deciding what the model is.

### 3.2 Modifying an existing case class

The differ classifies every change into exactly one of three buckets, and the
bucket determines what dev mode is allowed to do.

**Additive — applied automatically in dev, generated without prompting.**

| Change | Emitted |
|---|---|
| add `Option[X]` field | `ALTER TABLE … ADD COLUMN … NULL` |
| add non-Option field with a companion default | `ADD COLUMN … NOT NULL DEFAULT …` |
| add a table | `CREATE TABLE` |
| add an index, unique or check | `CREATE INDEX` / etc. |
| widen `Int` → `Long` | Postgres `ALTER … TYPE bigint USING (col::bigint)`; SQLite no-op (its INTEGER is already 64-bit, §2.2) |

One trap `migrations.md` §2.9 found and skiff did not document: SQLite's
`ADD COLUMN` forbids a parenthesised default, so any default that renders as an
expression works in `CREATE TABLE` and fails in `ALTER TABLE`. The renderer must
emit the three-step expand (add nullable → backfill → set not null) in that case,
and the portability linter (§4.3 of that document) must catch it if a human writes
it by hand.

**Ambiguous — the CLI asks, once, and records the answer in the migration file.**

```
$ eezo g migration rename_book_title
  book.title is gone and book.name is new.
  Did you rename `title` to `name`?  [y/N] y

  wrote migrations/0008__rename_book_title.sql
```

and the generated file carries the decision:

```sql
-- eezo:rename book.title -> book.name
ALTER TABLE book RENAME COLUMN title TO name;
```

The case class carries nothing. This is the whole answer to "keep the case classes
clean of annotations when changes happen": the intent lives with the event, in a
reviewable file, and is never read again after the migration runs.

The same prompt shape covers a table rename and a type change that could be either
a widen or a re-type.

**Destructive — never applied automatically, in any mode.**

Dropping a column, dropping a table, narrowing a type, adding `NOT NULL` without a
default, and dropping a unique constraint that data may now violate. These are
generated only under `eezo g migration --allow-destructive`, and they are marked in
the file so the runner can refuse to replay them casually — following skiff's
`downFaithful` classification and its `-- skiff:unfaithful` marker
(`migrations.md` §4.2), which `Migrations.rollbackLast` refuses to execute without
`--force`.

**Do not implement the twelve-step SQLite table rebuild.** `migrations.md` §8 is
categorical: make it unnecessary by keeping generated migrations additive and
requiring an explicit flag for destructive ones. If a rebuild ever becomes
unavoidable, follow SQLite's own ordering (new table under a temporary name,
renamed into place) and not Liquibase's, which SQLite's documentation explicitly
warns "can corrupt references to that table in triggers, views, and foreign key
constraints."

### 3.3 Why the migration file is the right annotation store

Because the alternative is worse in a way that compounds. A `@RenamedFrom("title")`
annotation on the case class is correct exactly once, at the moment the migration
runs, and then becomes permanent noise that every future reader has to decide
whether to trust. Ten renames over two years is ten dead annotations. The migration
file is append-only history, it is already the thing that gets reviewed, and after
it runs nobody has to read it.

---

## 4. Not producing destructive actions in dev

The rule is one sentence: **dev mode only ever expands.**

The reason it works is that dropping is never urgent. A column the code no longer
reads costs nothing but disk. So the dev loop can be unconditionally safe without
being unconditionally annoying:

- Renames are applied as *add the new column, leave the old one*. Both exist, the
  app runs, nothing is lost. The rename becomes real at `eezo g migration` time.
- Removing a field from a case class leaves the column in place. The dev banner
  lists it as deferred.
- A type narrowing is refused; the old column stays and the banner explains.

That accumulation is not debt, it is exactly the expand half of expand/contract,
which is the same discipline you want for a zero-downtime production migration
anyway. Reconcile it deliberately with `eezo db contract`, which shows what will be
dropped and asks.

Around that, four settings that come straight from the research:

1. **`PRAGMA foreign_keys = ON` on every SQLite connection checkout.**
   `migrations.md` §2.8 measured that it is off by default in
   `org.xerial:sqlite-jdbc`, which makes every `references` clause in every
   migration decorative in dev and enforced in prod. That document calls it
   "arguably the single most valuable finding in this document for the one-file
   claim." It belongs in the connection pool and it is not negotiable.
2. **`STRICT` on every SQLite table** (§2.10). Turns silent affinity coercions into
   loud dev-time errors and forces the vocabulary toward the portable subset. It
   also happens to make the type mapping in §1.2 the only legal one, which is a
   useful way to enforce a design rule with the database instead of with a review
   comment.
3. **A single rolling WIP migration**, regenerated wholesale rather than appended
   to, so a morning of iterating on a model does not leave forty junk files.
4. **`eezo db reset` is the only destructive dev command**, it is a word rather
   than a flag, and it confirms before running. It deletes the dev database and
   replays every migration from empty — which doubles as a continuous check that
   the migration history actually reproduces the snapshot.

The dev pool needs one more thing that is easy to miss: `db-query-layer.md` §5.3
records that skiff had to reflect into Magnum's `private[magnum]` `DbTx`
constructor because SQLite with a pool of one deadlocks when a handler holding a
read connection opens a transaction. The fix is to hand handlers a `Transactor`
rather than a live `DbCon`, or to size the dev pool above one with WAL. Do not ship
`setAccessible(true)`.

---

## 5. Verifying the sync in production

### 5.1 In CI, before anything is built

`eezo check` — one command, fails the build:

1. `fingerprint(model) == fingerprint(snapshot)`. This is the gate that stops a
   changed case class from reaching main without a migration. It needs no database.
2. Replay every migration from empty against `jdbc:sqlite::memory:` and confirm the
   result matches the snapshot. Milliseconds.
3. **Down round-trip verification.** `migrations.md` §4.2 found that *no mainstream
   migration tool checks that a down migration restores the prior schema*, and that
   eezo can, cheaply: apply up to N-1 against throwaway SQLite, snapshot
   `sqlite_schema`, apply N then N's down, snapshot again, compare. "This would be a
   genuine differentiator, it is maybe fifty lines, and it runs in milliseconds."
   Run it in CI, not on every dev reload.
4. **Portability lint** on hand-written migrations: `alter column`, `varchar(`,
   `serial`, `timestamptz`, `now()`, `autoincrement`, `uuid`, unnamed
   `create index`, and a parenthesised default on `ADD COLUMN`. §3.2 of that
   document: "No existing tool does this. It converts the one-file claim from a
   discipline into a check, and it costs almost nothing at startup."
5. Render every migration for **both** dialects and parse-check the output. Where a
   Postgres service container is available, apply for real.

### 5.2 At boot in production

In order:

1. Verify the ledger's raw-file checksums against the migration files on disk.
   Refuse to start on a mismatch — an already-applied migration whose file changed
   is a deploy that cannot be reasoned about.
2. Take the advisory lock, apply pending migrations in a transaction, record each,
   release.
3. **Compatibility check, not an equality check.** Compare the model's tables and
   columns against the `snapshot` JSON recorded in the ledger's latest row, and
   require that every table and column the model needs exists with a compatible
   type and nullability. Refuse to serve if not.

The distinction in step 3 matters and is easy to get wrong. Equality
(`fingerprint(model) == ledger.fingerprint`) breaks rolling deploys: during a
rollout the old pods carry an older model than the freshly migrated database, and
under equality every one of them would refuse to start. Satisfiability — model ⊆
database — is the correct predicate at boot, and it is exactly what makes
expand/contract safe. Equality stays where it belongs, in CI (§5.1 step 1), where
old and new are not simultaneously live.

Because the ledger carries per-column fingerprints, the failure message can name
the difference:

```
eezo: the database does not satisfy this build's data types.

  book.published_at
    code expects   INTEGER NOT NULL
    database has   INTEGER NULL

  Applied migrations end at 0007__add_book.sql (fingerprint 2b1f…).
  This build expects at least fingerprint 9a3c…, produced by 0008__backfill_published.sql,
  which is not in the database.

  Run `eezo db status` on the host to see pending migrations.
```

That is a designed artefact and worth iterating on the way `capture-checking.md`
§10 says the `@implicitNotFound` string is worth iterating on. It is the message
somebody reads at 3 a.m.

### 5.3 As part of deploy

`eezo deploy` runs `eezo check` locally before it transfers anything. Per
`deploy-target.md` §5, the warm redeploy budget is 10–35 s of which the largest and
least controllable term is `sbt stage`; a schema check that needs no database adds
milliseconds and removes the single worst deploy failure — shipping code whose
model no longer matches the migrations.

---

## 6. Which typeclasses ship on day one

The short answer is **five, and not "all of them"**, for two reasons that are
already measured. Each entry in a `derives` clause costs roughly 2–2.6 ms per model
of clean-build compile time (`build-reload.md` §4.5), and a real one costs far more
— Magnum's `DbCodec` was measured at +41.5 ms per entity (`db-query-layer.md`
§3.2). More importantly, every typeclass is a permanent public API surface and,
per §0.1, one you cannot cheaply add later.

### Day one

**`Table`** — §1. Foundational. Subsumes `DbCodec`, which users never name.

**`Json`** — encode and decode. Needed by HTTP bodies and by the LiveView wire
protocol, so it is load-bearing twice. Kept **separate from `Table`** deliberately:
if `Table` implied `Json`, then the default state of every row type is
wire-serialisable, and the first `password_hash` field anyone adds is one careless
handler away from the response body. Making it a second word in the clause is a
one-time cost and a permanent, greppable statement of intent.

**`Form`** — HTML form decoding, with per-field errors that a LiveView can render.
`build.sbt` already puts `live` in the dependency chain of `derives`, and a LiveView
framework without form derivation is a demo, not a framework. Skiff shipped
`derives DbCodec, Table, Form, AdminResource` in its production templates
(`db-query-layer.md` §6.1), which is direct evidence about what the set converges
to.

**`Api`** — the CRUD endpoint set. See §6.2 for the shape, which is not quite what
was asked for.

**`Config`** — `case class AppConfig(port: Int, dbUrl: String) derives Config`,
read from `app.conf` and the environment. Small, self-contained, and the framework
needs one internally regardless. Cheap to ship, expensive to retrofit once users
have written their own.

So the clause a generated model carries is:

```scala
case class Book(...) derives Table, Json, Form, Api
```

Four entries, written once by `eezo g model`, never edited. That is the design
working as intended.

### Explicitly not day one

`Show`/`Debug` (low value, and `toString` on a case class is already fine),
`Eq`/`Ord` (Scala has `CanEqual` and `Ordering`), `Csv`, `Arbitrary`/`Gen` for the
testkit (real value, but it belongs to the testkit's own design), `OpenApi` (derive
it from `Api` later, not from the model), `Diff` (LiveView's diff is over the node
tree, not over user models — do not conflate them), `Cache`, `Audit`.

### Explicitly never

**A single `derives Entity` umbrella that means all of them.** It is tempting given
§0.1, and it is wrong: it makes wire-serialisability the default (see `Json` above),
it cannot be partially opted out of, and the day someone wants `Table` without
`Api` the umbrella has to be unbundled, which is precisely the O(dependents)
clause edit it was invented to avoid.

### 6.2 On `derives Endpoints`

The request was `case class User(...) derives Endpoints`, autogenerating "get all
Users" and "insert a User". I want to build that, with one change, and the change
is worth the paragraph.

The concern: HTTP exposure is not a property of the data type. The path, the auth
policy, the pagination defaults, and above all *whether this type is exposed at
all* are properties of the application. A `derives` clause that mounts routes means
you cannot answer "what does this service expose?" by reading one file, and adding
a field to a model silently changes a public API. That is the failure mode that
makes scaffolding frameworks feel magical for a week and unmaintainable for a year.

The change: **`derives Api` produces a description, and mounting is one explicit
line.**

```scala
// app/models/User.scala
case class User(id: Id[User], name: String, email: String) derives Table, Json, Form, Api

// app/api/users.scala   — file-based routing, per build.sbt
val routes = mount("/users", Api[User])
  .auth(requireAdmin)
  .paginate(default = 50, max = 200)
  .except(Delete)
```

`Api[User]` is a value describing five endpoints — `GET /`, `GET /:id`, `POST /`,
`PUT /:id`, `DELETE /:id` — with their codecs, parameter parsing and status codes
already derived. You get everything the request asked for: the endpoints are
autogenerated from the data type, and adding a field to `User` changes the request
and response bodies with no other edit.

What you additionally get is that `grep -r mount app/api` is a complete and
accurate list of your public surface, auth is attached where a reviewer will see
it, and `eezo routes` can print the whole table because it is data, not
annotations. It costs one line per exposed model.

If you want the shorter spelling anyway, `mount(Api[User])` can default the path to
`/users` — pluralising the *URL* is safe in a way that pluralising the *table name*
is not (§1.6), because the URL is a string the user is looking at.

### 6.3 Rules for writing the derivations

Collected from `build-reload.md` and `db-query-layer.md` so they are in one place:

- No `transparent inline`, and never chained (scala3#25728 is open and exponential).
- `inline def derived` must delegate to a non-inline factory rather than return an
  anonymous class, or the class is duplicated at every inline site.
- Concrete, named result types everywhere. Derived instances must not have inferred
  or refined types.
- The generated registry emits a plain `val`, never a family of `given`s.
- Keep the capability traits (`DB`, `Tx`) in their own dependency-free module, per
  `capture-checking.md` §10, so a future capture-checking experiment is confined to
  one compilation unit. `Tx <: DB`, each with a hand-written `@implicitNotFound`
  message; §8.1 there shows what that message should look like and §5.2 of
  `db-query-layer.md` shows why the `write` guard
  (`extension (f: Frag) def write(using Tx): Int`) is non-optional — Magnum's own
  `Update.run` takes `DbCon` and will silently auto-commit outside a transaction.
- One measurement to take before this ships: `build-reload.md` §4.5 notes that
  editing an `inline def derived` body invalidates every expansion site, so an edit
  to eezo's own `Table.derived` recompiles every model file in the test app, each
  re-deriving. At Magnum's +41.5 ms per entity that is ~1.7 s on a forty-model app,
  paid by whoever is hacking on the derivation. Lay eezo's own repo out knowing
  that.

---

## 7. The CLI

Design rules, in order:

1. There is **one command you type all day**, and it is `eezo dev`.
2. Nothing has to be remembered for the database to be correct in dev. The dev
   server does it.
3. There is **one command CI runs**, and it is `eezo check`.
4. There is **one command that ships**, and it is `eezo deploy`.
5. Destructive things are a *word*, never a flag you can forget: `eezo db reset`,
   `eezo db contract`, `eezo g migration --allow-destructive`.
6. Typing a bare noun lists its verbs. `eezo db` prints the subcommands; `eezo g`
   prints the generators. Nothing else needs to be memorised.

### The full surface

| Command | When | What |
|---|---|---|
| `eezo new <name>` | once per project | scaffold: app, models dir, migrations dir, `app.conf`, Caddyfile, Dockerfile escape hatch |
| **`eezo dev`** | **all day** | resident sbt, debounced compile, classloader swap, additive dev migration, browser reload, itemised timing banner |
| `eezo g model <Name> <field:Type>…` | new table | model file with the full derives clause, and the migration |
| `eezo g migration <name>` | before commit | diff snapshot → model, prompt on ambiguity, write up + down, update snapshot |
| `eezo g page <path>` | new screen | LiveView page + route file |
| `eezo g api <Model>` | expose a model | the one-line mount in `app/api/` |
| **`eezo check`** | **CI** | §5.1: fingerprint, replay, down round-trip, portability lint, both-dialect render |
| `eezo db status` | when confused | pending migrations, ledger tail, drift summary |
| `eezo db migrate` | manual/prod | apply pending under lock |
| `eezo db verify [--deep]` | after an incident | §2.4; `--deep` introspects the live schema |
| `eezo db contract` | occasionally | apply the deferred destructive changes, showing each and asking |
| `eezo db reset` | when fed up | drop the dev DB and replay from empty; confirms |
| `eezo db console` | debugging | `psql`/`sqlite3` with the right URL already resolved |
| `eezo routes` | debugging | print the mounted route table, including derived `Api` endpoints |
| **`eezo deploy`** | **shipping** | §5.3; 10–35 s warm, per `deploy-target.md` §5 |
| `eezo deploy --bootstrap` | once per host | 3–6 min cold provision: JRE, Postgres, Caddy, systemd unit, certificate |

Fifteen commands, of which four are muscle memory (`dev`, `g migration`, `check`,
`deploy`) and the rest are discoverable by typing `eezo db` or `eezo g` and reading.

Three notes on the shape:

- `eezo` is a real binary that owns the resident sbt session, rather than a set of
  sbt tasks the user invokes. `build-reload.md` §1.2 measured a cold `sbt compile`
  at 1692 ms to decide there is nothing to do and 3059 ms with one field changed —
  56% and 102% of the three-second budget respectively, before any eezo work. A
  process that shells out to `sbt` per command has already lost. Every command
  should also exist as an sbt task for people who live in sbt, but the CLI is the
  documented path.
- `eezo dev` should print an **itemised** reload budget, not a total —
  `build-reload.md` §11.2 recommends exactly this, in the style of Quarkus's
  `Live reload total time` but broken out. It converts the three-second promise into
  a standing measurement, and it is the only way anyone will notice when
  application initialisation inside the fresh classloader starts costing a second.
- `deploy` and `deploy --bootstrap` being two shapes of one command rather than two
  commands is deliberate; `deploy-target.md` §1 is emphatic that no target
  provisions cold in sixty seconds and that the two-command shape should be said out
  loud rather than discovered.

---

## 8. Module layout, and one thing `build.sbt` is missing

The schema model itself — `Column`, `ColType`, `TableSpec`, `Diff`, the per-dialect
`Renderer`, the runner and the ledger — belongs in **`db`**, not in `derives`.
`build.sbt:70` already says db owns "migrations, DDL per dialect", which is right.
Only the *derivation* of a `TableSpec` from a `Mirror` belongs in `derives`
(`build.sbt:76`).

This matters because the CLI's `check`, `g migration` and `db verify` need the
schema model and the differ but must not depend on the whole derivation chain, and
because the migration runner has to work on a schema loaded from JSON — a snapshot
of a model that this build may not contain.

Missing: an **`sbt-eezo` plugin project**. The registry source generator
(§2.1), the dev-server command and the CLI-to-sbt bridge all require it, and
`build-reload.md` §3.3 makes `Compile / sourceGenerators` the deciding argument for
sbt over scala-cli in the first place. It is not one of the eight modules today.

---

## 9. What still needs a decision or a measurement

| Item | Why it is open |
|---|---|
| `Instant` as epoch millis on both dialects (§1.2) | `migrations.md` §2.3 argues for it and it costs native Postgres timestamp operators. Needs an explicit sign-off, not a default |
| Whether `Api` is a derives entry or a call site (§6.2) | I recommend derives-for-description, explicit mount. It is a taste call with real consequences for how a service's public surface is audited |
| Registry by source glob vs. explicit list (§2.1) | Globbing `app/models/*.scala` is convention-over-configuration and fragile at the edges. Measure how it behaves when a model is defined in a sub-package |
| Cost of `Table.derived` on a realistic model | Magnum's `DbCodec` alone was +41.5 ms per entity. eezo's `Table` does strictly more. Measure before promising anything about clean-build time on a forty-model app |
| Model registry construction time inside a fresh classloader | `build-reload.md` §10 calls this **the** unmeasured term in the whole reload budget. The registry is a `List` of already-derived values, so it should be microseconds — but it has never been measured, and neither has the connection-pool re-acquisition next to it |
| Down round-trip check on Postgres | §5.1 step 3 is cheap on SQLite. Whether it is worth a service container in CI for the Postgres side is a cost question nobody has priced |
