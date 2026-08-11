# Session handoff: eezo derivation design

Written 2026-08-11 to carry an in-progress design conversation into a new session.
Read this, then `design/derivation.md`.

---

## 1. Where you are

eezo is a Scala 3 web framework (`/home/daniel/eezo`, branch `main`). Its thesis:
direct style, the case class is the source of truth, deploy with one command. The
repo currently has a build skeleton with eight empty modules (`core`, `http`, `db`,
`live`, `derives`, `auth`, `testkit`, `cli`) and six long research documents under
`research/`. No framework code exists yet.

We are designing **the typeclass derivation module and the schema-sync story**:
what `case class Book(...) derives Table` means, and how it proves the database
matches the data types.

**Scope narrowed at point 7: Postgres only.** SQLite is not dropped — the design
leaves room for it (nothing PG-specific may become an `Expr` node), but no SQLite
work happens now. Sections 1–6 below were written when both were in scope; the
SQLite-flavoured parts of §5 are dormant, not wrong.

### Files

| File | Status |
|---|---|
| `design/derivation.md` | **authoritative.** Settled decisions 1–6, open agenda 7–13 |
| `design/HANDOFF.md` | this file |
| `research/derivation-design.md` | **stale.** My first-pass draft, wrong in six places (listed in `design/derivation.md` §3). Do not build from it |
| `research/*.md` | six prior investigations, all measured, all trustworthy |

### The original ask, from the user's first message

Three questions, still the frame:

1. `case class Book(...) derives Table` should prove the DB is in sync with the
   data type. Covering: a new case class, generating migrations when one changes,
   keeping case classes free of annotations, verifying sync at production deploy,
   and never doing anything destructive in dev.
2. Which typeclasses ship day one — `Table`, codecs, `Json`, `Endpoints`, others?
   The user explicitly wants `case class User(...) derives Endpoints` to generate
   sensible CRUD HTTP endpoints.
3. What CLI commands, as a minimal set requiring the least to remember or type.

---

## 2. How this conversation has been working

The user proposed a design (dev watcher auto-applies additive changes, `eezo db
freeze` squashes into one migration, generated migrations are uneditable like
`package-lock.json`, `eezo deploy` gates on fingerprints, derivation enables a
typed query DSL). I raised seven objections. We are working them **one at a time**,
in order, and the user replies with accept / reject / refine.

**The consistent pattern: the user cuts invented machinery, and is usually right.**
Four of my seven points were over-built and got trimmed to nothing or near it — an
interactive prompt state machine, a change journal, data-migration sub-files with
deploy-halting stubs, and merge-conflict tooling. Twice the trimming *improved* the
design (dropping the journal removed a source of wrong questions; refusing merge
tooling forced a better, merge-immune deploy gate). Default to the smaller design
and make the user argue for more, not less.

Points 1–7 are settled and recorded. **Point 8 is where to resume** — it has not
been presented yet.

One further habit, established at point 7: **this design is now checked, not
reasoned about.** The DSL surface was prototyped and compiled against Scala 3.8.4 /
JDK 25 before being written down, which caught two of my own claims being wrong (see
§4). `scala-cli` is on PATH; a file with `//> using scala 3.8.4` and
`//> using jvm 25` compiles in seconds. Do this for anything that hinges on what the
compiler actually does.

---

## 3. Settled — see `design/derivation.md` for full text

1. **The dev watcher never prompts.** It reports actions (`+ ~ ? !`), not
   interpretations. Ambiguity is decided once, at freeze. Licensed by: *the dev
   database's physical schema is not production's; migrations derive from the
   model, never from the dev database.*
2. **No journal.** `freeze` emits `diff(baseline, model)` and shows only final
   diffs.
3. **Generated vs hand-written**, not schema vs data. Generated files uneditable,
   hand-written files yours, both checksummed. Retire-don't-drop already leaves the
   window for a backfill migration.
4. **Non-null is the user's responsibility.** Type says non-null → column is
   `NOT NULL` → fails at apply if rows exist. `-Yexplicit-nulls` makes `Option` the
   only spelling of nullable.
5. **Retirement relaxes exactly three things**: `DROP NOT NULL`,
   `CHECK (col IS NOT NULL)`, `UNIQUE NULLS NOT DISTINCT`. Everything else is
   vacuous on NULL. Creates a hard floor of SQLite ≥ 3.53.0.
6. **Merges are not eezo's problem**, so nothing generated goes in git. No
   `schema.json` (baseline = replay the migrations into `:memory:`), no fingerprint
   headers. Deploy gate is `sha256(file) == ledger.checksum` +
   `fingerprint(model) == fingerprint(baseline)`. Git hook is a check, never an
   action.
7. **The query DSL.** `Table[Book].where(_.author == "JK Rowling")
   .orderBy(_.datePublished).desc`, compiled and run before being written down.
   Selector is `NamedTuple.Map[NamedTuple.From[A], Col]`; `==`/`!=` are overloaded
   on `Col[A]` so there is no `===`; `.desc` outside the lambda via
   `Sorted[A] <: Query[A]`; **Postgres only for now**, with nothing PG-specific
   allowed as an `Expr` node; `Frag` is the compile target and eezo owns the
   surface; `.as[T]` forced and label-matched; `Table[A] <: Row[A]`; reads take
   `DB`, writes take `Tx`, `Tx <: DB`.

---

## 4. Two things I got wrong at point 7, both caught by compiling

Recorded because the failure mode is general: I asserted compiler behaviour from
memory and was wrong twice in one turn.

- **I proposed `===`**, assuming `==` could not be overloaded. It can.
  `def ==(that: A): Col[Boolean]` wins overload resolution over `Any.==`, and gives
  a *better* error than `===` would (`Found: (3 : Int) Required: String`, pointing at
  the literal). `!=` must be overloaded alongside it or you get a misleading
  "cannot be compared with == or !=" message.
- **I proposed the wrong selector type.**
  `NamedTuple[MirroredElemLabels, Tuple.Map[MirroredElemTypes, Col]]` as a type
  member of `Table[A]` does not compile — the path-dependent member does not reduce
  at the use site. `NamedTuple.Map[NamedTuple.From[A], Col]` does, and is better:
  the names are the case class's own, not a copy that can drift.

Both are now written up in `design/derivation.md` §7.1–7.2, including the
non-compiling formulation, so nobody retries it.

---

## 5. Still open, in order

**8. What `Table[A]` actually is.** *(resume here — not yet presented)* CRUD on it,
or a separate `Repo`? Note §7 already fixed part of the answer: `Table[A]` is a
plain trait with no type members (the selector is a free-standing `Columns[A]` type
alias), and it is a subtype of `Row[A]`. The `Id[A]`
proposal: `id: Id[Book]` *is* the primary key, `author: Id[Author]` *is* the
foreign key, with no annotation — types don't unify, so you can't pass an
`Id[Author]` where an `Id[Book]` goes. How insert works before an id exists
(sentinel vs a second case class; note `Repo`'s creator-class approach was measured
at double the compile cost and skiff barely used it).

**9. Where non-type metadata lives.** Indexes, uniques, checks, `ON DELETE`,
column-name overrides, constant defaults. Proposal: a `Table.Refined[Book]`
companion block providing **no given** — a given there would make editing your
index list an O(dependents) recompile. Open: how the derivation reads it without a
companion-ordering hazard (leaning toward a runtime merge in the generated
registry, not a macro lookup).

**10. The typeclass roster.** Proposed `derives Table, Json, Form, Api` (+ `Row`
from §7, + `Config`). Whether `Json` is bundled into `Table` (I say no — bundling
makes every row wire-serialisable by default, and the first `passwordHash` field is
one careless handler from the response body). Whether `derives Endpoints` mounts
routes or only describes them (I say describes; mounting is one explicit line so
`grep mount` is a complete list of the public surface — **the user asked for the
mounting version and has not yet ruled on this**). The write-once rule: the
generator writes the full clause up front because editing it later is the one
O(project-size) edit.

**11. Model discovery.** How eezo knows `Book` is a table. sbt `sourceGenerators`
emitting a plain `val` registry (**never `given`s**), globbing `app/models/`. This
needs an `sbt-eezo` plugin project that does not exist in the current eight
modules — worth raising as its own issue.

**12. The type mapping.** Which Scala types are supported. `Instant` is **settled**
by §7.4 — native `timestamptz`, not epoch-millis, now that Postgres is the only
target. Still proposed: no `BigDecimal` (use an opaque `Money` over `Long` minor
units), flat models only (nested case classes make derivation superlinear in depth),
`LocalDate` is fine (it has no timezone; `LocalDateTime` is the trap).

**13. Dev auto-apply safety gate.** Must refuse structurally when the database URL
is not the configured dev one. Someone will point `eezo dev` at staging.

**Not yet revisited:** the CLI surface from question 3 of the original ask. A draft
is in the stale `research/derivation-design.md` §7 (fifteen commands, four of them
muscle memory: `dev`, `g migration`, `check`, `deploy`), but it predates everything
settled above and needs a pass.

---

## 6. Measured facts that must not be re-derived

Everything below is in `research/`, was measured on real hardware, and is load
bearing. Re-litigating any of it wastes the user's time.

Compiled and run on Scala 3.8.4 / JDK 25 at point 7 (`design/derivation.md` §7):

- **`==` and `!=` can be overloaded on a `Col[A]`.** No `===` needed. Overload
  resolution prefers them over `Any.==`; a wrong literal type errors at the literal.
- **`NamedTuple.Map[NamedTuple.From[A], Col]` reduces at the use site. A
  path-dependent `type Columns` member of `Table[A]` does not.**
- **`.orderBy(_.x).desc` needs `Sorted[A] <: Query[A]`**, and cannot coexist with
  `.orderBy(_.x.desc)` — overloaded methods taking lambdas break inference.

Measured earlier, in `research/`:

- **Editing a `derives` clause is the only edit whose cost is O(project size).**
  Adding a *field* recompiles 1 file (129 ms at 504 sources); adding a *typeclass to
  the clause* recompiles 401 files (766 ms), extrapolating to ~2.7 s at 1600
  dependents. Cause: one branch in Zinc's `MemberRefInvalidator` — any modified name
  in `UseScope.Implicit` disables name hashing and invalidates all member-reference
  dependents. `build-reload.md` §1.1, §4.3.
- **Generated code must emit plain `val`s, never `given`s**, for the same reason.
  `build-reload.md` §3.3.
- **No chained `transparent inline`** — [scala3#25728](https://github.com/scala/scala3/issues/25728),
  open, ~doubles compile time per link (1.0 s at 10, ~90 s at 15). `build-reload.md` §8.2.
- **`inline def derived` must not return an anonymous class** — it is duplicated at
  every inline site. `db-query-layer.md` §9.
- **Depend on Magnum's dialect-free half only.** `db-query-layer.md` §8.1–8.3.
- **eezo owns the migration runner.** Liquibase is FSL-licensed and its SQLite
  support is broken; Flyway Community lacks undo and diff. Typed schema model
  rendered per dialect is the primary mechanism; placeholders are the escape hatch
  for hand-written SQL. `migrations.md` §8.
- **`PRAGMA foreign_keys = ON` on every SQLite connection checkout** — off by
  default in the JDBC driver, which makes every `references` clause decorative in
  dev and enforced in prod. Called the single most valuable finding for the
  one-file claim. `migrations.md` §2.8.
- **Emit `STRICT` on SQLite tables.** Turns silent affinity coercions into loud
  dev-time errors. `migrations.md` §2.10.
- **Checksum the raw migration file, never the resolved SQL.** `migrations.md` §3.1.
- **Postgres advisory lock, `BEGIN IMMEDIATE` on SQLite.** Both targets have
  transactional DDL (verified on SQLite directly). `migrations.md` §6.2, §2.10.
- **Jetty 12.1 + JDK 25 floor.** JEP 491 (JDK 24) removes `synchronized` pinning;
  on JDK 21 a blocking call inside an uncontended `synchronized` block costs 16x.
  `http-server.md` §1.2.
- **Plain `using` + `@implicitNotFound` capability traits, not capture checking.**
  `Tx <: DB`. `capture-checking.md` §9.
- **VPS + rsync + systemd + Caddy for deploy**, `eezo deploy` (10–35 s warm) and
  `eezo deploy --bootstrap` (3–6 min cold). `deploy-target.md` §7.
