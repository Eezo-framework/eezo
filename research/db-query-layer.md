# Research: Scala 3 database and typed query layer

Resolves [rcardin/eezo#4](https://github.com/rcardin/eezo/issues/4). Part of #1.

Date of investigation: 2026-07-27. Every version number, release date and commit
count in this document was verified against Maven Central `maven-metadata.xml` or
the GitHub API on that date, not from memory. All timings are measured on this
machine (Apple Silicon, JDK 26 Temurin, Scala 3.8.4, scala-cli 1.15.0) and the
harnesses are preserved (Appendix A).

---

## 1. The findings that reframe the question

**First: Quill is not a candidate. It is abandonware with a live download count.**
The last commit to `zio/zio-quill` master is **2025-08-06**, and it is a Scala
Steward dependency bump. The last commit by a human is **2025-07-21**. In the
twelve months to today the repository received **four commits, all from bots**
(`zio-scala-steward[bot]` and `dependabot[bot]`). The last published artifact,
`quill-jdbc_3` **4.8.6**, went to Maven Central on **2024-10-30**, twenty one
months ago. Meanwhile 291 non pull request issues and 66 pull requests are open.
This is not a slow project, it is a stopped one, and the reputation for slow
compiles turns out to be the *second* worst thing about it.

**Second: the compile cost question has two answers and the ticket is asking for
the wrong one.** Clean build cost differs across candidates by up to 4.5x, and
that number is genuinely alarming on paper. But eezo's constraint is edit to
visible reload, which is a *warm incremental* recompile, and there the whole field
except Quill lands between 0.32 s and 0.75 s even when a case class change
invalidates twenty dependent handler files. The number that actually disqualifies
is the fan out one, and it disqualifies exactly one candidate: changing a case
class that twenty handlers use costs **2.68 s median with Quill** against 0.38 s
baseline. That alone consumes eezo's entire three second budget before the JVM
restarts.

**Third, and this is the finding that should change eezo's plan: no library in
this survey gives you "SQLite in dev, Postgres in prod, same code" as a property.
Every one of them either fixes the dialect at compile time or refuses one of the
two databases outright.** Magnum bakes the dialect into an `@Table` annotation
constant; the mismatch then surfaces at *runtime*, and in the worst case as a bare
`UnsupportedOperationException` **with a null message** (§6.3, measured). ScalaSql
picks the dialect by import. Skunk cannot speak to SQLite at all. This is not a
gap eezo can shop its way out of. It is work eezo has to do itself, and
`research/migrations.md` already committed to owning the schema side of exactly
this problem. The query side needs the same treatment.

**Fourth: skiff already ran this experiment and the answer is in its source.**
Skiff shipped on Magnum. Across skiff's own modules there are **224 uses of the
`sql"…"` interpolator and 2 uses of `Repo[…]`**. Skiff used almost none of
Magnum's repository or query machinery. What it used was three things: `DbCodec`
derivation, the `Frag` / `sql` interpolator, and the `DbCon` / `DbTx` context
types. On top of that it wrote its own typed query builder
(`modules/derives/src/main/scala/skiff/query/Query.scala`, 329 lines) that
desugars to `Frag`. That is the real shape of the answer, and it is much smaller
than "pick a query library".

---

## 2. Comparison table

| | **Magnum** | **ScalaSql** | **plain JDBC + eezo layer** | **Doobie** | **Skunk** | **Quill / protoquill** | **ldbc** |
|---|---|---|---|---|---|---|---|
| **Latest release** | 2.0.0-M3 | 0.3.1 | n/a | 1.0.0-RC12 | 2.0.0-RC2 | 4.8.6 | 0.7.0 |
| **Released on** | 2026-04-02 | 2026-04-30 | n/a | 2026-02-21 | 2026-07-14 | **2024-10-30** | 2026-06-02 |
| **Stable release ever?** | **no**, milestone | **no**, 0.x | n/a | **no**, RC since 2019 | **no**, RC | 4.8.6 is stable | **no**, pre 1.0 |
| **Licence** | Apache-2.0 | MIT | n/a | MIT | MIT | Apache-2.0 | MIT |
| **Commits, 12 mo** | 12 | 20 | n/a | 294 | 249 | **4, all bots** | 1364 |
| **Distinct authors, 12 mo** | 4 | 7 | n/a | 15 | 16 | **2, both bots** | 4 (2 are one person) |
| **Bus factor** | **1** (A. Nagro, 10/12) | **1** (Li Haoyi + N. Glushchenko) | eezo | 2 to 3 | **1** (M. Pilquist, 145/249) | **0** | **1** (takapi327, 1237/1364) |
| **Open issues (non PR)** | 14 | 23 | n/a | 128 | 78 | **291** | 2 |
| **Scala 3 native codebase** | **yes** | **yes** | yes | yes | yes | **no**, 13 `_2.13` jars on the runtime classpath | yes |
| **Effect model** | **direct, Loom ready** | **direct, blocking** | **direct** | cats-effect + fs2 | cats-effect + fs2 | ZIO / cats / sync contexts | cats-effect 3 |
| **Maps to `using Tx`** | **trivially**, `transact` is already `DbTx ?=> A` (§5.1, measured) | needs a wrapper, `db.transaction { d => … }` passes a value | eezo defines it | **hostile**, §4.3 measured | **hostile**, `Resource[IO, Session]` | context object, per query `ctx.run` | hostile |
| **Postgres** | yes | yes | yes | yes | yes, only | yes | **no** |
| **SQLite** | yes | yes | yes | yes | **no** | yes | **no** |
| **Dialect chosen** | `@Table(PostgresDbType, …)`, **compile time constant** | `import scalasql.PostgresDialect.*`, **compile time import** | eezo's choice | runtime, driver | n/a | context class, **compile time** | n/a |
| **Dialect mismatch surfaces** | **runtime**, sometimes as a null message `UnsupportedOperationException` (§6.3) | runtime | wherever eezo puts it | runtime | n/a | compile time SQL, runtime failure | n/a |
| **Row codec from case class** | `derives DbCodec`, Mirror + macro | `SimpleTable[T]` macro on companion | eezo writes it | `derives Read, Write` | **hand written**, `varchar *: int4` | macro at use site, no codec type | `derives Table` |
| **Coexists with `derives Table, Form, Resource`** | **yes, verified running** (§6.1) | **yes with `scalasql-simple`**, no with the classic `Table[T[_]]` API (§6.1, both verified) | yes | yes | n/a | yes, contributes nothing | untested |
| **Joins** | **no DSL**, raw SQL or a view | **yes**, typed | raw SQL | raw SQL | raw SQL | **yes**, for comprehension |  yes |
| **Aggregates / subqueries** | raw SQL | **yes** | raw SQL | raw SQL | raw SQL | **yes** | yes |
| **`IN` list of Scala values** | manual `Frag` | **trap**, §6.4 | eezo's job | raw SQL | raw SQL | `liftQuery(xs).contains` | yes |
| **Upsert** | raw SQL | **yes**, `onConflictUpdate` | raw SQL | raw SQL | raw SQL | `onConflictUpdate` | yes |
| **`RETURNING`** | `.returning[T]`, `.returningKeys` | **yes**, `.returning(_.id)` | raw SQL | raw SQL | raw SQL | yes | yes |
| **Own write guard** | **none.** every write takes `DbCon` (§5.2) | none | eezo's | none | none | none | none |
| **Jars on classpath** | **3** | 10 | 1 driver | 14 | 36 | **44** | many |
| **Classpath MB** | **9.2** (8.75 of it is `scala-library`) | 10.3 | ~0.5 | 27.8 | 30.4 | **59.3** | n/a |
| **Clean compile, 40 entities** | 3.69 s (codec) / 5.12 s (+`Repo`) | 3.20 s | 1.61 s | 2.42 s | n/a | **6.29 s** | n/a |
| **Warm reload, 20 dependents** | **0.67 s** | 0.75 s | **0.32 s** | 0.62 s | n/a | **2.68 s** | n/a |
| **One 6 way join, compile** | +0.53 s over baseline | +0.86 s | +0.00 s | n/a | n/a | **+1.67 s** | n/a |

---

## 3. Compile cost, measured

The ticket calls edit to visible reload under three seconds existential and asks
for measurement in preference to reputation, so everything in this section is
measured on this machine today. Published numbers appear only in §3.5, as
corroboration.

### 3.1 Method

Three rigs, all Scala 3.8.4 on JDK 26 (Temurin 26+35), scala-cli 1.15.0.

1. **Clean compile, single file, N entities.** One `Bench.scala` declaring N case
   classes plus, per entity, a "find all", a "find by name" and a "count" query in
   the library's idiomatic style. N in {0, 5, 10, 20, 40}. Compiled with
   `scala-cli compile <dir> --server=false -O -Yprofile-enabled` after deleting
   `.scala-build`. The figure reported is the **sum of per phase `run ns` from the
   compiler's own profiler**, which excludes JVM startup and scala-cli overhead.
   This is the same instrument and the same reporting convention as
   `research/capture-checking.md` §5, so the numbers are comparable across the two
   documents.
2. **Warm incremental, 20 files, edit one leaf.** Twenty `Model*.scala`, one
   entity each. Compile once to warm the build server, then five times: modify
   `Model0.scala` (append a unique comment line) and wall clock
   `scala-cli compile`.
3. **Warm incremental, fan out.** The same twenty model files plus twenty
   `H*.scala` handler files, every one of which references `Ent0`. Then five
   times: add a field to `Ent0`, wall clock the recompile, revert. Verified that
   this genuinely invalidates: renaming a field of `Ent0` produces errors in **20
   distinct files**, so the dependents really do recompile.

Magnum is measured in two shapes because they cost very differently and skiff's
evidence says the cheap one is what gets used: `magnum` is
`derives DbCodec` plus `sql"…"` queries; `magnum+Repo` adds the `EntCreator`
companion case class and `Repo[EC, E, Long]` that Magnum's repository API
requires.

### 3.2 Clean compile, sum of compiler phase wall clock, ms

| N entities | baseline | JDBC | Doobie | ScalaSql | Magnum | Magnum + `Repo` | **Quill** |
|---|---|---|---|---|---|---|---|
| 0 | 105.2 | 120.2 | 154.6 | 133.9 | 123.5 | 126.9 | **716.6** |
| 5 | 809.8 | 856.2 | 1321.7 | 1546.8 | 1634.9 | 2046.0 | **2658.9** |
| 10 | 940.1 | 1018.9 | 1545.5 | 1847.5 | 2085.1 | 2635.2 | **3492.3** |
| 20 | 1150.2 | 1377.8 | 1902.6 | 2434.5 | 2749.4 | 3590.5 | **4622.4** |
| 40 | 1414.1 | 1611.9 | 2423.0 | 3201.5 | 3691.7 | 5122.0 | **6294.3** |

Marginal cost per entity, from the 5 to 40 slope, and the part of it that is the
library's rather than the compiler's:

| | ms per entity | over baseline |
|---|---|---|
| baseline | 17.3 | 0.0 |
| JDBC | 21.6 | +4.3 |
| Doobie | 31.5 | +14.2 |
| ScalaSql | 47.3 | +30.0 |
| Magnum | 58.8 | +41.5 |
| Magnum + `Repo` | 87.9 | +70.6 (over two case classes) |
| **Quill** | **103.9** | **+86.6** |

Two things stand out. **Quill has a fixed floor of about 610 ms**: at zero
entities, a file whose only content is `object Ctx extends PostgresJdbcContext(…)`
costs 716.6 ms against a 105.2 ms empty baseline. That tax is paid by every file
that touches the database, regardless of how many queries it contains. Nothing
else in the field has a floor worth measuring.

Second, **`Repo[…]` roughly doubles Magnum's marginal cost**, and skiff's usage
data says almost nobody reaches for it. In the Magnum harness the compiler's
`inlining` phase alone is 810 ms of a 2046 ms total at five entities; that phase
is where `Repo`'s compile time CRUD generation happens.

An earlier version of this measurement had Quill looking almost free, at 1643 ms
for 40 entities. That harness was wrong, and the salvaged rig in
`research/harnesses/bench/quill/Bench.scala` has the same defect: it wrote
`inline def all = quote { query[Ent] }` and never called `ctx.run`. **Quill's
macro does its work at `run`, not at `quote`.** With `Ctx.run(…)` present the
compiler starts emitting `[info] Quill Query (compiled in 2ms): SELECT …` per
query and the cost appears. Anyone repeating this must call `run`.

### 3.3 Warm incremental, edit one leaf file, seconds

| | 1 | 2 | 3 | 4 | 5 | median |
|---|---|---|---|---|---|---|
| baseline | 0.39 | 0.37 | 0.36 | 0.35 | 0.34 | **0.36** |
| JDBC | 0.36 | 0.37 | 0.37 | 0.38 | 0.38 | **0.37** |
| Magnum | 0.44 | 0.44 | 0.43 | 0.42 | 0.42 | **0.43** |
| Doobie | 0.42 | 0.42 | 0.43 | 0.46 | 0.46 | **0.43** |
| ScalaSql | 0.49 | 0.48 | 0.50 | 0.47 | 0.50 | **0.49** |
| Quill | 0.65 | 0.70 | 0.65 | 0.67 | 0.64 | **0.65** |

**Every candidate passes comfortably.** The whole field is inside 0.7 s. On this
measurement the clean build differences essentially vanish, exactly as the
capture checking investigation found for a different feature. If the only edit
shape that mattered were "change a query", the compile cost section of this ticket
would be a non issue.

### 3.4 Warm incremental, change a case class twenty handlers depend on, seconds

| | 1 | 2 | 3 | 4 | 5 | median |
|---|---|---|---|---|---|---|
| JDBC | 0.30 | 0.30 | 0.34 | 0.32 | 0.32 | **0.32** |
| baseline | 0.40 | 0.38 | 0.36 | 0.38 | 0.39 | **0.38** |
| Doobie | 0.64 | 0.60 | 0.62 | 0.58 | 0.64 | **0.62** |
| Magnum | 0.64 | 0.65 | 0.67 | 0.72 | 0.84 | **0.67** |
| ScalaSql | 0.81 | 0.75 | 0.77 | 0.72 | 0.72 | **0.75** |
| **Quill** | 2.81 | 2.91 | 2.65 | 2.68 | 2.29 | **2.68** |

**This is the number that decides the ticket.** "Change a case class, see the app
reload" is *the* eezo edit, because "the case class is the source of truth" means
the case class is what users edit. Magnum costs 0.29 s over baseline for it,
ScalaSql 0.37 s, Doobie 0.24 s. Quill costs **2.30 s over baseline**, and eezo's
entire budget from keystroke to a repainted browser is 3 s, of which the compiler
is only the first stage. Quill fails this on compile time alone even if it were
maintained.

### 3.5 One complex query, and the corroboration

A single file with six case classes and one six way join (four inner, two left),
which is the shape [zio-quill#2807](https://github.com/zio/zio-quill/issues/2807)
complains about. Three clean runs each, sum of compiler phase ms:

| | run 1 | run 2 | run 3 | median | over baseline |
|---|---|---|---|---|---|
| baseline (6 case classes, join as a string) | 721.0 | 721.4 | 723.9 | 722 | 0 |
| Magnum (raw SQL join, `derives DbCodec`) | 1257.7 | 1248.3 | 1254.4 | 1253 | **+531 ms** |
| ScalaSql (typed join DSL) | 1561.2 | 1599.4 | 1590.8 | 1584 | **+862 ms** |
| Quill (for comprehension join) | 2381.1 | 2390.0 | 2407.4 | 2393 | **+1671 ms** |

**One Quill query costs 1.67 seconds of compiler time.** That is not an
extrapolation, it is the measurement, on protoquill 4.8.6 and Scala 3.8.4, today.

It matches the primary source almost exactly. In
[zio-quill#2807](https://github.com/zio/zio-quill/issues/2807), a user with "32
database tables … 134 SELECTs of various complexities, 39 INSERTs, 25 DELETEs and
14 UPDATEs" reports the project "takes a little over two minutes to compile on a
reasonable desktop computer, and between five and six minutes on our CI server",
and, of one seven way join, "**an example of a function containing a query that
takes a little under two seconds to compile**". That report is against Scala 2
Quill (`quill-jdbc-monix_2.13`), so it is not directly evidence about protoquill;
my Scala 3 measurement is, and it agrees.

[zio-quill#2737](https://github.com/zio/zio-quill/issues/2737), "Compilation time
issue when having a lot of requests in a project", **open since 2023-04-28**, has a
second user reporting "We have invested quite a lot of time to migrate from Doobie
to pure Quill queries and now the compilation times are really slow", and a Quill
contributor writing "Lots of people are suffering from these compilation times."
The documented workaround is to replace `MappedEncoding` with explicit
`Encoder`/`Decoder` pairs, which one reporter measured at "a ~30% reduction in
compile time" while another found it "did not improve significantly and I started
getting runtime errors from Quill". The issue is still open.

### 3.6 Compile cost verdict

**Not disqualifying for Magnum, ScalaSql, Doobie or plain JDBC. Disqualifying for
Quill.** Magnum's cost is real (+41.5 ms per entity on clean builds, +0.29 s on the
fan out edit) but it never approaches the budget, and roughly half of it can be
avoided by not using `Repo`, which skiff's own source says nobody does anyway.
Confidence: **high**, three independent rigs, low run to run variance, and the one
published report that exists agrees with the measurement.

---

## 4. Direct style fit, per candidate

eezo has no `IO[A]` in its user facing API. Handlers are plain methods on JDK 21
virtual threads. The question for each candidate is what wrapping costs.

### 4.1 Magnum: zero cost, because it was designed for this

Magnum's README states the design goal outright: **"Easy to use, Loom-ready API
(no Futures or Effect Systems)"**
([README](https://github.com/AugustNagro/magnum)). That is not marketing. Its
entry points, verified against the 2.0.0-M3 jar, are:

```
public <T> T connect (Transactor, Function1<DbCon, T>);
public <T> T transact(Transactor, Function1<DbTx,  T>);
public class DbTx extends DbCon
```

In Scala source those `Function1` parameters are **context functions**
(`DbCon ?=> T`), which is why the pass through in §5.1 is a one liner. There is no
runtime, no pool of eezo's own, no `unsafeRunSync`, no thread handoff, and no
stack frames between the handler and JDBC. A `magnum-zio` module exists for people
who want effects; it is opt in and eezo simply never depends on it.

**Verdict: perfect fit.** Confidence: **high**, verified by running eezo's exact
planned API shape (§5.1).

### 4.2 ScalaSql: direct and blocking, but the handle is a value not a context

ScalaSql is direct style too. Its transaction scope is `db.transaction { d => … }`
where `d: DbApi` is an ordinary value parameter, not a context parameter. Turning
that into eezo's `transact[A](body: Tx ?=> A): A` is a two line wrapper
(`db.transaction(d => body(using d))`), which is trivial but is a wrapper, unlike
Magnum's pass through. ScalaSql 0.3.0 added an "FP-friendly resource-block API"
([PR #107](https://github.com/com-lihaoyi/scalasql/pull/107), Ivan Klass,
2026-02-04) which is orthogonal.

**Verdict: good fit, one line of glue.** Confidence: **high**.

### 4.3 Doobie: possible, expensive, and the naive version is silently wrong

This is worth spelling out because "we could just call `unsafeRunSync`" is the
kind of thing that sounds fine until someone loses a write.

**Performance is not the problem.** Measured, 5000 queries issued from 5000
virtual threads against SQLite through a HikariCP pool of 8, warm:

```
plain JDBC, direct style                     n=5000   total=  77.2 ms   per-op= 0.015 ms
doobie .transact(xa).unsafeRunSync()         n=5000   total= 238.6 ms   per-op= 0.048 ms
overhead factor = 3.09x
```

A 3.09x constant factor and +33 µs per query. **No pinning was observed**: 5000
virtual threads completed in 239 ms, so `unsafeRunSync`'s latch parks and unmounts
rather than blocking a carrier. For a web app issuing a handful of queries per
request this cost is invisible next to HTTP. Effect monad overhead is a red
herring.

**Transaction scoping is the problem, and it fails silently.** Measured, three
attempts at eezo's `transact[A](body: Tx ?=> A): A`:

```
attempt 1: naive direct-style block, second statement throws
  caught: handler failed after the writes
  rows after 'failed transaction' = 2   (0 means atomic, 2 means NOT atomic)

attempt 2: own the java.sql.Connection, use Transactor.fromConnection
  caught: handler failed
  rows after attempt 2 = 4   (should still be 2 if attempt 2 rolled back)
```

Attempt 1 is what a reasonable person writes: several `.transact(xa).unsafeRunSync()`
calls inside one block. Each one takes its own connection and commits its own
transaction, so a later throw rolls back nothing. Attempt 2, holding the
`java.sql.Connection` yourself and calling `conn.rollback()`, **still does not
work**, because doobie's default `Strategy` commits inside every `.transact` call
before your rollback can run.

What does work:

```scala
val raw: Transactor[IO] = Transactor.fromConnection[IO](conn, None)
val cxa: Transactor[IO] = Transactor.strategy.set(raw, Strategy.void)  // no per-call commit
// … own setAutoCommit(false), own commit(), own rollback()
```

```
  rows after Strategy.void + manual rollback = 0   (0 = atomic, correct)
```

So it is achievable, by taking the connection, the auto commit flag, the commit
and the rollback away from doobie. At that point doobie is contributing `Read` /
`Write` derivation and an SQL interpolator, and eezo is carrying cats-core,
cats-free, cats-effect, cats-effect-kernel, cats-effect-std, cats-mtl, fs2-core,
scodec-bits and typename (14 jars, 27.8 MB) plus a live `IORuntime` to get them.

**Diagnostics are also bad.** The error a direct style user hits when inference on
`M` fails is verbatim:

```
Ambiguous given instances: both value WeakAsyncNClobIO in trait Instances and
value WeakAsyncBlobIO in trait Instances match type cats.Applicative[M] of a
context parameter of method noop in object LogHandler
```

and when an IO fails inside the interpreter the stack the user sees is padded with
fiber trace frames:

```
at flatMap @ doobie.WeakAsync$$anon$1.flatMap(WeakAsync.scala:30)
at tailRecM$$anonfun$1 @ doobie.util.transactor$Transactor$$anon$4.apply$$anonfun$3(transactor.scala:169)
at flatMap @ doobie.WeakAsync$$anon$1.flatMap(WeakAsync.scala:30)
…
```

**Verdict: technically possible, strategically wrong.** A framework whose pitch is
"no effect monad" would ship cats-effect in its core and hand users
`WeakAsyncNClobIO` error messages. Confidence: **high** on all three measurements.

### 4.4 Skunk: not a candidate for eezo, on three independent counts

Skunk is "a data access library for Scala + Postgres"
([README](https://github.com/typelevel/skunk)). It is a native Postgres wire
protocol client, not a JDBC one.

1. **No SQLite, ever.** There is no adapter to write. eezo's dev story dies.
2. **The handle is a `Resource[IO, Session[IO]]`**
   ([tutorial](https://typelevel.org/skunk/tutorial/Query.html)), so there is no
   `java.sql.Connection` to own, and everything in §4.3 gets worse rather than
   better. eezo would keep a live `IORuntime` for the lifetime of the process and
   allocate and release cats-effect `Resource`s across handler boundaries.
3. **Codecs are written by hand and are positional.** From the official tutorial:

   ```scala
   val extended: Query[String, Country] =
     sql"""
       SELECT name, code, population
       FROM   country
       WHERE  name like $text
     """.query(varchar *: bpchar(3) *: int4)
        .to[Country]
   ```

   `.to[Country]` maps a *tuple* to a case class positionally. The column list
   `varchar *: bpchar(3) *: int4` is spelled out by the programmer and has to be
   kept in sync with `Country` by hand. That is the exact opposite of "the case
   class is the source of truth".

Skunk is healthy (249 commits and 16 authors in twelve months, 2.0.0-RC2 shipped
2026-07-14) and it is the right tool for a Postgres only, cats-effect native
application. eezo is neither. **Verdict: disqualified.** Confidence: **high**.

### 4.5 Quill: disqualified before direct style is even reached

For completeness: Quill's JDBC contexts are synchronous and would fit direct style
fine. `PostgresJdbcContext.run(q)` returns a `List[T]`, not an effect. Direct style
is not why Quill loses. It loses on §1 (unmaintained) and §3.4 (2.68 s fan out
recompile), either of which is on its own fatal. **Verdict: disqualified.**
Confidence: **high**.

### 4.6 ldbc: disqualified on database support

ldbc is "Pure functional JDBC layer with Cats Effect 3 and Scala 3" and its
documentation covers **MySQL only** ([repo](https://github.com/takapi327/ldbc)).
No Postgres, no SQLite. It is also pre 1.0 with an explicit "New versions are not
binary compatible with prior versions" warning, and its 1364 commits in twelve
months come almost entirely from one person under two author names (takapi327 /
Takahiko Tominaga, 1237 of 1364). Prolific, but a bus factor of one on a library
that cannot talk to either database eezo targets. **Verdict: disqualified.**
Confidence: **high**.

---

## 5. Magnum against eezo's actual plan

`research/capture-checking.md` settled on plain context parameters over
`@implicitNotFound` capability traits, with scope constructors shaped
`def transact[A](body: Tx ?=> A): A`. This was tested by writing that code.

### 5.1 The scope constructor is a pass through

Compiled and run, in full:

```scala
type DB = mag.DbCon
type Tx = mag.DbTx

final class Database(ds: javax.sql.DataSource):
  private val xa = mag.Transactor(ds)
  def read[A](body: DB ?=> A): A     = xa.connect(body)
  def transact[A](body: Tx ?=> A): A = xa.transact(body)

case class Note(id: Long, title: String, body: String)
  derives mag.DbCodec, Table, Form, Resource

object Notes:
  def all(using DB): Vector[Note] =
    sql"select id, title, body from note".query[Note].run()
  def add(t: String, b: String)(using Tx): Int =
    sql"insert into note(title, body) values ($t, $b)".update.run()
```

Real output:

```
read:      Vector(Note(1,hello,world))
derives:   note List(id, title, body) /notes
rolled back: boom
after:     Vector(Note(1,hello,world))
guard:     false
```

`xa.connect(body)` and `xa.transact(body)` are **literal pass throughs**, because
Magnum's `connect` and `transact` already take `DbCon ?=> T` and `DbTx ?=> T`.
Rollback propagates through the scope constructor correctly. And `guard: false` is
`scala.compiletime.testing.typeChecks` on
`def bad(using DB): Int = Notes.add("x","y")`, confirming that **a write reached
from a read only context does not compile**, because `Tx = DbTx` is a strict
subtype of `DB = DbCon`.

That is as close to zero adaptation as a third party library can get to a
framework's planned API.

### 5.2 But Magnum enforces no write guard of its own, and eezo must add one

From the 2.0.0-M3 jar, every write method takes `DbCon`, not `DbTx`:

```
com.augustnagro.magnum.Update:  public int run(DbCon);
com.augustnagro.magnum.Repo:    public void insert(EC, DbCon);
                                public void delete(E, DbCon);
                                public void truncate(DbCon);
                                public E insertReturning(EC, DbCon);
```

So `sql"delete from note".update.run()` compiles and silently auto commits under a
plain `using DB`. The guard in §5.1 held only because *my* `Notes.add` declared
`(using Tx)`.

Skiff hit this and documented it in
`/Users/rcardin/Documents/Repositories/skiff/modules/db/src/main/scala/skiff/db/db.scala`:

> "Magnum 1.x relaxed `.update.run()` to accept any `DbCon`, so on its own it
> would silently auto-commit outside a transaction. `.write` re-asserts the
> compile-time guarantee by requiring `using Tx`; the diagnostic on the missing
> `using` names the `Tx` type alias."

Its fix is four lines:

```scala
extension (frag: Frag) {
  def write(using Tx): Int = frag.update.run()
}
```

**Still true in 2.0.0-M3.** eezo needs the same extension, and it needs its
generated repository code to declare `using Tx`. Cost: trivial. Risk if forgotten:
silent loss of transactionality, the same class of bug as §4.3 attempt 1.

### 5.3 One thing skiff needed that Magnum would not give it

`modules/db/src/main/scala/skiff/db/MagnumInternals.scala` reflectively invokes
`DbTx`'s constructor:

> "Magnum 1.x marks the constructor `private[magnum]`, which is enforced by the
> Scala compiler but not the JVM. We need to elevate an existing read connection
> (handed to the request handler by `connect(ds)`) to a write connection without
> checking another connection out of the pool, otherwise SQLite (pool of one)
> deadlocks the moment a request opens a `transaction { ... }`."

This is a genuine sharp edge and it is **SQLite specific**, which makes it eezo's
problem too since SQLite is the dev database. In 2.0.0-M3 `DbTx(Connection, SqlLogger)`
is still not public API (`javap` shows the constructor, but the JVM does not
enforce Scala's `private[magnum]`). Two ways out that do not need reflection:
give the handler a `Transactor` rather than an already open `DbCon` and let
`transact` open its own connection, or open dev mode SQLite with a pool larger
than one using WAL. Either is preferable to shipping `setAccessible(true)` in a
framework. Flag for the runtime ticket.

Confidence on §5.1: **high**, executed. On §5.2: **high**, read from the jar. On
§5.3: **medium**, skiff's constraint is documented and credible but I did not
reproduce the SQLite deadlock.

---

## 6. Derivation, dialects and the DSL, measured

### 6.1 The `derives` chain: Magnum yes, ScalaSql yes only via `scalasql-simple`

eezo #19 wants `derives DbCodec, Table, Form, Resource` on one case class. This
was tested, not assumed.

**Magnum passes.** Compiled and ran, with three user defined `Mirror` based
typeclasses:

```scala
case class User(id: Long, name: String, email: String) derives DbCodec, Table, Form, Resource
```
```
Table:      user
Form:       List(id, name, email)
Resource:   /users
DbCodec:    List(-5, 12, 12) queryRepr=?, ?, ?
```

Magnum uses `def derived` (changed from `given derived` in
[2.0.0-M2 PR #113](https://github.com/AugustNagro/magnum/pull/113)) driven by a
`Mirror` plus a macro, and it sits in a `derives` list beside anything else
without complaint. Skiff confirms this at scale: every template model is written
`derives DbCodec, Table, Form, AdminResource`.

**ScalaSql's classic API fails.** The `Table[T[_]]` API needs a higher kinded case
class, and a user `derives` clause cannot attach to it:

```scala
case class User[T[_]](id: T[Int], name: T[String], email: T[String]) derives Form
```
```
error: User cannot be unified with the type argument of Form
```

The workaround is a hand written `given Form[User[Sc]] = Form.derived[User[Sc]]`
in the companion, which compiles and runs but is not a `derives` clause and is not
"the case class is the source of truth". **This would have been a serious mark
against ScalaSql, and the `scalasql-simple` module removes it.** With
`com.lihaoyi::scalasql-simple` (a separate artifact; it is not on
`scalasql`'s classpath by default) the case class is plain:

```scala
case class User(id: Int, firstName: String, email: String) derives Form
object User extends SimpleTable[User]
```
```
derives Form: List(id, firstName, email)
```

The table binding moves to `object User extends SimpleTable[User]`, which is not a
`derives` clause but does coexist with one. This uses Scala 3 named tuples and
therefore needs a recent compiler.

**Doobie passes** (`derives Read, Write` compiled in the benchmark harness).
**Quill contributes nothing to the derives clause** because it has no codec type
at all, so a user's own chain works trivially. **Skunk has nothing to derive.**

Confidence: **high**, all four executed.

### 6.2 Derivation cost per case class

From §3.2, the marginal cost of one entity with its three queries over the plain
case class baseline: Doobie +14.2 ms, ScalaSql +30.0 ms, Magnum +41.5 ms,
Magnum with a `Repo` and a creator class +70.6 ms, Quill +86.6 ms. For a
realistic application of 30 entities, Magnum's derivation costs about 1.2 s of
clean build. That is worth knowing for CI but it is amortised to nothing by the
incremental compiler (§3.3, §3.4).

### 6.3 SQLite and Postgres from one codebase: nobody offers this

**Magnum fixes the dialect at compile time.** The 2.0.0-M3 jar contains exactly
six: `PostgresDbType`, `MySqlDbType`, `SqliteDbType`, `H2DbType`, `OracleDbType`,
`ClickhouseDbType`. The choice is an annotation argument:
`@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)`. You cannot vary it by
environment without recompiling.

What happens when they disagree, measured:

**Postgres annotated code run against SQLite.** Reads work, `repo.count` and
`repo.findAll` work, and then:

```
raw sql read:      Vector(User(1,ada,ada@x.io))
repo.count:        1
repo.findAll:      Vector(User(1,ada,ada@x.io))
insertReturning FAILED: com.augustnagro.magnum.SqlException: Error executing query:
INSERT INTO user (first_name, email) VALUES (?, ?)
With message:
java.sql.SQLException: column 2 out of bounds [1,1]
insert:            ok, count=3
```

**Correctly annotated for SQLite, the same call is worse:**

```
insert ok, count = 1
insertReturning THREW: java.lang.UnsupportedOperationException / msg=None
  cause: None
```

A bare `UnsupportedOperationException` **with a null message and no cause**, at
runtime, from a call that compiled. Magnum removed SQLite's `insertReturning` in
[2.0.0-M2 PR #104](https://github.com/AugustNagro/magnum/pull/104) but left the
method on `Repo`, so the removal is invisible to the type checker. "Insert a row
and get its generated id back" is the single most common write a web application
performs, and it is precisely the operation that does not port.

**The portable subset is real and it is what skiff used.** `derives DbCodec` needs
no `@Table` at all, verified: a case class with only `derives DbCodec` compiles and
`sql"…".query[T].run()` works on SQLite. `Repo[…]` is what demands the annotation:

```
error: p2.Plain must have @Table annotation
```

So Magnum splits cleanly into a dialect free half (`DbCodec`, `Frag`, `sql`,
`DbCon`/`DbTx`) and a dialect bound half (`@Table`, `Repo`, `Spec`). **Skiff used
the dialect free half, 224 `sql"…"` sites to 2 `Repo[…]` sites**, and independently
built `skiff/db/schema/PortableSql.scala` to paper over the DDL differences with
a five placeholder vocabulary (`${skiff_pk}`, `${skiff_long}`,
`${skiff_now_millis}`, `${skiff_ts}`, `${skiff_now_ts}`). Its own comment states
the general result:

> "Two constructs in a `create table` have *no* shared spelling across SQLite and
> Postgres … There is therefore no single raw-SQL form a developer can write once
> and run on both."

Skiff also had to write its own `DbCodec[Instant]` because Magnum ships none, and
that codec **sniffs the dialect at runtime**:

```scala
val postgres =
  try ps.getConnection.getMetaData.getDatabaseProductName.toLowerCase.contains("postgre")
  catch { case _: Throwable => false }
```

Magnum 2.0.0-M2 added `LocalDate`, `LocalTime` and `LocalDateTime`
([PR #110](https://github.com/AugustNagro/magnum/pull/110)) but `Instant` is still
not among them, so eezo inherits this gap.

**ScalaSql picks the dialect by import** (`import scalasql.PostgresDialect.*`),
which is the same compile time commitment wearing different clothes, and its
dialect specific operations (`onConflictUpdate`, `FOR UPDATE SKIP LOCKED`) are
methods that only exist under the right import, so *some* mismatches do become
compile errors. That is genuinely better than Magnum's runtime `UnsupportedOperationException`,
and it is ScalaSql's strongest single argument.

Confidence: **high** on the SQLite results, which were executed. **Medium** on the
Postgres side: Docker was not running on this machine and no live Postgres was
available, so the Postgres behaviour is inferred from the annotation semantics,
the jar contents and skiff's shipped code rather than observed.

### 6.4 DSL expressiveness, and where each one drops to raw SQL

**Magnum has no join DSL.** Its README's advice for multi table queries is
explicit: "If you need to perform joins to get the data needed, first create a
database view." Anything beyond single table CRUD plus `Spec` filtering is raw
SQL. What it does give you is good raw SQL: the interpolator produces a `Frag`,
`Frag`s embed in other `Frag`s (since
[2.0.0-M1 PR #51](https://github.com/AugustNagro/magnum/pull/51)), and
`.returning[T]` / `.returningKeys` exist on the jar. `Spec` covers dynamic
`WHERE`, seek and offset pagination, and sorting:

```scala
val spec = Spec[User]
  .where(sql"first_name ILIKE $partialName")
  .where(lastNameOpt.map(ln => sql"last_name = $ln").getOrElse(sql""))
  .seek("id", SeekDir.Gt, idPosition, SortOrder.Asc)
  .limit(10)
```

For an `IN` list of Scala values you build the `Frag` yourself. Verified working:

```scala
val holes = Frag(ids.map(_ => "?").mkString(","), ids,
  (ps, pos) => { ids.zipWithIndex.foreach((v, i) => ps.setLong(pos + i, v)); pos + ids.size })
sql"… WHERE f.id IN ($holes)"
```

That is about as much ceremony as it sounds, and it is a thing eezo should provide
once rather than make every user write.

**ScalaSql is genuinely expressive.** All of these were executed against SQLite
and returned correct results:

```
select:    Vector(User(1,ada,ada@x.io), User(2,grace,g@x.io))
filter:    Vector(User(1,ada,ada@x.io))
count:     2
join:      Vector((ada,ada@x.io), (grace,g@x.io))
agg:       (2,3)
subquery:  Vector(ada, grace)
upsert:    1
returning: Vector(3)
sqlText:   SELECT user0.id AS id, user0.first_name AS first_name, user0.email AS email
           FROM user user0 WHERE (user0.first_name = ?)
```

Two real problems found while doing it.

**A silent wrong result on `IN` lists.** This compiles, runs, and returns the
wrong answer:

```scala
d.run(User.select.filter(u => Seq(1, 2, 3).contains(u.id)).map(_.firstName))
// => Vector()      with ids 1 and 2 present in the table
```

The rendered SQL is:

```
SELECT user0.first_name AS res FROM user user0 WHERE ?
```

Scala's own `Seq.contains(elem: Any): Boolean` matches `u.id: Expr[Int]`, evaluates
to `false`, and gets lifted as a bound literal. ScalaSql's `.contains` is defined
on a *`Select`*, not on a Scala collection: the library's own test suite documents
it as "ScalaSql's `.contains` method translates into SQL's `IN` syntax … here
checking if a **subquery** contains a column"
([SelectTests.scala](https://github.com/com-lihaoyi/scalasql/blob/main/scalasql/test/src/query/SelectTests.scala)).
There appears to be **no typed literal `IN` list operator**, and the obvious
spelling silently produces a false predicate. This is worse than a missing feature.

**The join DSL is fiddly.** Getting one six way join to compile took **seven
compile error iterations**. Inner joins flatten the tuple (`(A, B)`, then
`(A, B, C)`, then `(A, B, C, D)`), but the first `leftJoin` **nests** it
(`((A, B, C, D), JoinNullable[E])`), and a `JoinNullable` will not compare with
`===` until it is passed through `scalasql.core.JoinNullable.toExpr`, which is not
exported by the dialect import. Final working form:

```scala
Family.select
  .join(Rel)((f, r) => f.id === r.familyId)
  .join(Product)((t, p) => t._2.productId === p.id)
  .join(Supplier)((t, s) => t._3.supplierId === s.id)
  .leftJoin(CatRel)((t, cr) => t._3.id === cr.productId)
  .leftJoin(Cat)((t, c) => JoinNullable.toExpr(t._2.map(_.catId)) === c.id)
  .map { case (((f, r, p, s), cr), c) =>
    (f.name, p.label, s.internalId, JoinNullable.toExpr(c.map(_.name))) }
```

Against the same query in Magnum, which is a SQL string. For a framework selling
"boring and obvious", the SQL string is easier to read, easier to teach, and 331 ms
cheaper to compile (§3.5).

**Quill is the most expressive of the three** (for comprehension joins, `liftQuery(xs).contains` for
`IN` lists, compile time SQL generation printed into the build log). It is also
the only one where the SQL is checked at compile time. None of that survives §1.

Confidence: **high** on everything executed. **Medium** on "no typed literal `IN`
list in ScalaSql": I found none in the tests or the jar, but absence of evidence.

### 6.5 Overlap with eezo's migration runner

`research/migrations.md` commits eezo to owning a typed schema model rendered per
dialect. The overlap is narrow but real: **Magnum's `@Table(dbType, SqlNameMapper.CamelToSnakeCase)`
and ScalaSql's `SimpleTable` both own table and column naming**, which is
knowledge eezo's schema model also has. If they disagree, DDL and DML diverge and
nothing catches it. Two consequences:

- eezo's `derives Table` must be the single source of naming, and whatever the
  query layer needs must be *generated from it*, not written next to it.
- This is another argument for the dialect free half of Magnum. `DbCodec` carries
  no naming at all; `@Table` and `Repo` do. Avoiding them avoids the overlap
  entirely.

Neither ScalaSql nor Magnum wants to own migrations, so there is no conflict on
the DDL side.

---

## 7. Maintenance health, in detail

Twelve month windows are 2025-07-27 to 2026-07-27, from the GitHub commits API on
the default branch.

| | commits | authors | top author share | last human commit | open issues |
|---|---|---|---|---|---|
| **Magnum** | 12 | 4 | 10/12 August Nagro | 2026-04-02 | 14 |
| **ScalaSql** | 20 | 7 | 11/20 Li Haoyi | 2026-05-18 | 23 |
| **Doobie** | 294 | 15 | 120/294 Jacob Wang | 2026-07-27 | 128 |
| **Skunk** | 249 | 16 | 145/249 Michael Pilquist | 2026-07-27 | 78 |
| **ldbc** | 1364 | 4 | 1237/1364 one person | 2026-07-25 | 2 |
| **Quill** | **4** | **2, both bots** | n/a | **2025-07-21** | **291** |

**Magnum's bus factor is one and its cadence is low.** Twelve commits in a year,
ten of them from August Nagro, with a four month gap between 2025-10-18 and
2026-03-17. The project is 282 stars, Apache-2.0, and has been on `2.0.0-M` for
eighteen months without a stable 2.0. This is the honest risk in the
recommendation, and it is not small. Two things temper it: **Magnum has zero
transitive dependencies** (the entire classpath is `magnum_3-2.0.0-M3.jar` at
**0.46 MB** plus the Scala library), so it cannot rot from underneath, and the
part eezo depends on is small enough to fork or reimplement. Adam Warski (of Ox
and softwaremill) contributed in the window, which suggests it is on the radar of
the direct style community.

**ScalaSql's bus factor is also one**, though it lives under `com-lihaoyi`, is on
0.x with an explicit Scala 2 deprecation in 2026-04, and had four distinct
contributors land features in the last six months.

**Doobie and Skunk are the two genuinely healthy projects here**, and both are
disqualified for reasons that have nothing to do with health.

Confidence: **high**, all counted from the API.

---

## 8. Recommendation

### 8.1 Build on Magnum, but only on the half of it that has no dialect in it

**Depend on `com.augustnagro::magnum` for exactly four things:**

1. `DbCodec` and its `derives` support. It composes with eezo's chain, verified
   running (§6.1), and it is the single hardest thing on this list to write well.
2. `Frag` and the `sql"…"` interpolator, including `Frag` embedding.
3. `DbCon` and `DbTx`, aliased to eezo's `DB` and `Tx`.
4. `Transactor`, `connect` and `transact`, used as literal pass throughs for
   eezo's `read` and `transact` scope constructors (§5.1).

**Do not use `@Table`, `Repo`, `ImmutableRepo` or `Spec`.** They are where the
compile time dialect constant lives (§6.3), where the naming knowledge that
duplicates eezo's schema model lives (§6.5), where half the compile cost lives
(§3.2, +29 ms per entity), and where the SQLite `UnsupportedOperationException`
lives. Skiff reached the same conclusion by use rather than by argument: 224 to 2.

Total dependency footprint: **one 0.46 MB jar with no transitive dependencies.**

### 8.2 What eezo writes itself

- **`type DB = mag.DbCon`, `type Tx = mag.DbTx`, and `Database#read` / `Database#transact`.**
  Six lines, verified working (§5.1). Note that `@implicitNotFound` cannot be
  attached to a type alias, so the friendly "add `(using DB)`" message needs
  either a thin wrapper type or a compiler plugin free alternative; decide this in
  the capture checking follow up, not here.
- **A `write` guard.** `extension (frag: Frag) def write(using Tx): Int = frag.update.run()`,
  because Magnum's own `Update.run` takes `DbCon` and will silently auto commit
  (§5.2). Non optional.
- **`DbCodec[Instant]` and `DbCodec[UUID]` bridging SQLite text and Postgres
  `timestamptz`.** Skiff's implementation is the reference for the shape of the
  problem, including the fact that the encoder has to know which database it is
  talking to.
- **Generated CRUD from `derives Table`, emitting `Frag`s.** This replaces
  Magnum's `Repo`, is generated from eezo's own schema model so naming cannot
  diverge, and is where the SQLite / Postgres difference gets absorbed: the
  generator emits `RETURNING id` for Postgres and `last_insert_rowid()` for
  SQLite behind one signature. **This is the single most important piece of the
  recommendation**, because it is the only place the portability promise can
  actually be kept.
- **A small typed predicate builder over `Frag`**, in the shape of skiff's
  `Query.scala`: `Query[T].where(_.status === "open").and(_.orgId === id).orderBy(_.createdAt.desc).limit(20)`,
  with column selectors resolved by a `transparent inline` macro against the case
  class. Critically, **include a first class `IN` list**, since it is the one
  thing Magnum makes ceremonial (§6.4) and the one thing ScalaSql gets silently
  wrong (§6.4).
- **Nothing resembling a join DSL.** Joins are raw SQL with a `derives DbCodec`
  row class. That is what skiff did, it is 331 ms per query cheaper than ScalaSql
  and 1140 ms cheaper than Quill (§3.5), and it is easier to put on a slide.

### 8.3 Fallback if this fails

The failure mode to plan for is **Magnum stalling before 2.0.0 final**, which its
cadence makes plausible (§7).

**Primary fallback: vendor it.** The surface eezo depends on is `DbCodec` plus
`Frag` plus two context classes, from a 0.46 MB Apache-2.0 jar with no transitive
dependencies. Copying that subset into `eezo-db` under the Apache licence is a
weekend, not a quarter, and eezo's public API does not change because eezo already
owns `DB`, `Tx`, `read`, `transact` and the codegen. **This is the cheapest
fallback in the whole survey and it is the main reason to prefer Magnum over
ScalaSql**, whose 10 jar, four module structure and macro driven `SimpleTable`
would be far harder to absorb.

**Secondary fallback: `scalasql-simple`.** If eezo later decides it wants a real
typed join and aggregate DSL, ScalaSql is the only maintained candidate that has
one, works on both databases, and stays direct style. It costs +0.08 s on the fan
out reload versus Magnum (§3.4), needs a two line transaction wrapper (§4.2), and
needs the `IN` list trap documented loudly. It is a reasonable place to end up; it
is just not where to start.

**Explicitly not fallbacks:** Doobie and Skunk (cats-effect in a framework that
promises no effect monad, plus §4.3's silent non atomicity), Quill (unmaintained,
2.68 s fan out reload), ldbc (MySQL only).

### 8.4 Confidence

| Claim | Confidence | Basis |
|---|---|---|
| Quill's master branch has received no human commit since 2025-07-21 | **high** | GitHub commits API, checked twice, `pushed_at` is a dependabot branch |
| Quill costs 2.68 s median to recompile after a case class change with 20 dependents | **high** | measured, 5 runs, 4.3x the next worst |
| One 6 way Quill join costs 1.67 s of compiler time | **high** | measured, 3 runs, variance < 1.1%, agrees with zio-quill#2807 |
| Magnum's `connect`/`transact` are literal pass throughs for eezo's scope constructors | **high** | executed, §5.1 |
| `derives DbCodec, Table, Form, Resource` works | **high** | executed, plus skiff ships it |
| Magnum enforces no write guard; eezo must add one | **high** | read from the 2.0.0-M3 jar; skiff documented the same in 1.x |
| Magnum's dialect is a compile time constant and mismatches surface at runtime | **high** for SQLite | executed on SQLite; **medium** for Postgres, no live server was available |
| `insertReturning` throws a null message `UnsupportedOperationException` on SQLite | **high** | executed |
| ScalaSql's classic `Table[T[_]]` breaks a user `derives` clause; `scalasql-simple` fixes it | **high** | both executed |
| `Seq(...).contains(expr)` silently renders `WHERE ?` in ScalaSql | **high** for the behaviour | executed; **medium** that no typed literal `IN` exists at all |
| Doobie's naive direct style block is not atomic; `Strategy.void` plus owning the connection fixes it | **high** | all three attempts executed |
| `unsafeRunSync` on virtual threads costs 3.09x and does not pin | **high** for the number, **medium** for "does not pin" | 5000 threads completed in 239 ms, but no JFR pinning events were collected |
| Magnum's bus factor is one | **high** | 10 of 12 commits, twelve months |
| Vendoring Magnum's needed subset is cheap | **medium** | 0.46 MB, zero transitive deps, Apache-2.0; not attempted |
| Skunk cannot serve eezo | **high** | Postgres only by design, hand written positional codecs |

---

## 9. What other tickets need to know

- **#19 (`derives` chain).** `derives DbCodec, Table, Form, Resource` is
  **verified working** on Magnum 2.0.0-M3 and Scala 3.8.4, and skiff ships it in
  production templates. Budget about 42 ms of clean compile per entity for the
  `DbCodec` half. Note the compiler warning that fires on the naive
  `inline def derived` shape: "New anonymous class definition will be duplicated
  at each inline site". eezo's own typeclasses should avoid returning anonymous
  classes from `inline def derived` or they will bloat every call site.
- **#10 (reload spike).** The fan out measurement in §3.4 is the shape that ticket
  needs, and the rigs are preserved. Magnum's contribution to the reload budget is
  **+0.29 s** on a 20 dependent case class change. The remaining ~2.7 s of the 3 s
  budget belongs to the JVM restart and the browser, not the compiler.
- **#5 / `research/migrations.md`.** Confirmed no conflict: neither Magnum nor
  ScalaSql wants to own DDL. But `@Table(…, SqlNameMapper.CamelToSnakeCase)`
  duplicates naming knowledge, which is a further reason to avoid `@Table`
  (§6.5). Skiff's `PortableSql.scala` five placeholder vocabulary is directly
  reusable evidence for what actually differs between the two dialects.
- **#7 / `research/deploy-target.md`.** HikariCP works unchanged: Magnum's
  `Transactor(dataSource)` takes any `javax.sql.DataSource`, verified running
  against `HikariDataSource` 7.1.0 on JDK 26.
- **Runtime / connection handling ticket.** §5.3 is a live constraint. Skiff had
  to reflect into Magnum's `private[magnum]` `DbTx` constructor because SQLite
  with a pool of one deadlocks when a handler holding a read connection opens a
  transaction. eezo should solve this by handing handlers a `Transactor` rather
  than a live `DbCon`, or by sizing the dev pool above one with WAL. Do not ship
  `setAccessible(true)`.
- **Anyone repeating the compile benchmarks.** The salvaged Quill harness at
  `research/harnesses/bench/quill/Bench.scala` measures nothing, because it
  quotes without running (§3.2). Fix it before trusting any Quill number.
- **The stage story.** "No effect monad" is defensible and now has a measurement
  behind it: not because effects are slow (they cost 33 µs a query) but because
  the naive direct style bridge over doobie **silently loses transactionality**
  (§4.3). That is a better slide than a benchmark.

---

## Appendix A: reproduction

All harnesses are in `/Users/rcardin/.claude/jobs/401c3850/tmp` and are
self contained scala-cli projects. Nothing was added to the eezo repository.

```
gen.py, gen2.py, gen3.py, gen4.py   generators for every rig below
bench/<lib>-<n>/Bench.scala         clean compile, N entities in one file
inc/<lib>/Model*.scala              20 files, one entity each
fan/<lib>/{Model,H}*.scala          the same 20 plus 20 handlers using Ent0
cx/<lib>/C.scala                    the 6 way join, one file
derivestest/D.scala                 derives DbCodec, Table, Form, Resource
dt2/S.scala, dt3/Q.scala            derives against ScalaSql and Quill
portable/{P,R}.scala                Magnum dialect behaviour on SQLite
ssport/S.scala                      ScalaSql on SQLite, full DSL sweep
vt/V.scala, /tmp/txtest/{T,U}.scala doobie on virtual threads, tx scoping
eezoshape/E.scala                   eezo's planned API built on Magnum
measure.sh, inc.sh, fan.sh          the three measurement drivers
```

Clean compile measurement:

```sh
rm -rf $p/.scala-build
scala-cli compile $p --server=false -O -Yprofile-enabled 2>&1 \
  | grep -o 'run ns = [0-9]*' | awk '{s+=$4} END {printf "%.1f\n", s/1000000}'
```

Environment: Scala 3.8.4 (latest stable; 3.9.0-RC4 is the current RC), scala-cli
1.15.0, JDK 26 Temurin 26+35, macOS 24.6.0 on Apple Silicon. Library versions:
`magnum 2.0.0-M3`, `scalasql 0.3.1`, `scalasql-simple 0.3.1`,
`quill-jdbc 4.8.6`, `doobie-core 1.0.0-RC12`, `sqlite-jdbc 3.53.2.1`,
`HikariCP 7.1.0`.

Not reproduced: anything against a live Postgres. Docker was not running on this
machine (`Cannot connect to the Docker daemon at unix:///Users/rcardin/.colima/default/docker.sock`)
and no local `psql` or `pg_ctl` exists. Every Postgres claim in this document is
sourced from the libraries' own code, jars or docs, and is marked medium
confidence where it depends on runtime behaviour.

---

## Appendix B: sources

Versions and release dates, from `https://repo1.maven.org/maven2/<path>/maven-metadata.xml`
(note that `search.maven.org`'s solr index is stale by more than a year for several
of these and should not be used):

- `com/augustnagro/magnum_3` latest 2.0.0-M3, lastUpdated 20260331224840
- `com/lihaoyi/scalasql_3` latest 0.3.1, lastUpdated 20260430092334
- `org/tpolecat/doobie-core_3` latest 1.0.0-RC12, lastUpdated 20260221105319
- `org/tpolecat/skunk-core_3` latest 2.0.0-RC2, lastUpdated 20260714203934
- `io/getquill/quill-jdbc_3` latest 4.8.6, lastUpdated **20241030035513**
- `io/github/takapi327/ldbc-dsl_3` latest 0.7.0, lastUpdated 20260602104502

Repositories and issues:

- [github.com/AugustNagro/magnum](https://github.com/AugustNagro/magnum), Apache-2.0, 282 stars
- [magnum PR #104](https://github.com/AugustNagro/magnum/pull/104) remove SqliteDbType insertReturning
- [magnum PR #110](https://github.com/AugustNagro/magnum/pull/110) LocalDate/LocalTime/LocalDateTime
- [magnum PR #113](https://github.com/AugustNagro/magnum/pull/113) `def derived` instead of `given derived`
- [magnum PR #51](https://github.com/AugustNagro/magnum/pull/51) embed Frags in the sql interpolator
- [github.com/com-lihaoyi/scalasql](https://github.com/com-lihaoyi/scalasql), MIT, 259 stars
- [scalasql SelectTests.scala](https://github.com/com-lihaoyi/scalasql/blob/main/scalasql/test/src/query/SelectTests.scala), the `.contains` / `IN` documentation
- [github.com/zio/zio-quill](https://github.com/zio/zio-quill), Apache-2.0, 2166 stars, 291 open issues
- [zio-quill#2737](https://github.com/zio/zio-quill/issues/2737) open since 2023-04-28
- [zio-quill#2807](https://github.com/zio/zio-quill/issues/2807) "a little under two seconds to compile"
- [github.com/typelevel/doobie](https://github.com/typelevel/doobie), MIT
- [github.com/typelevel/skunk](https://github.com/typelevel/skunk), MIT
- [typelevel.org/skunk/tutorial/Query.html](https://typelevel.org/skunk/tutorial/Query.html) codecs and `Resource[IO, Session[IO]]`
- [github.com/takapi327/ldbc](https://github.com/takapi327/ldbc), MIT, MySQL only

Skiff, read as evidence only, never copied:

- `modules/db/src/main/scala/skiff/db/db.scala`, the `DB = DbCon` / `Tx = DbTx` aliases and the `.write` guard
- `modules/db/src/main/scala/skiff/db/transaction.scala`, `summonFrom` dispatch across nested / outer / pool cases
- `modules/db/src/main/scala/skiff/db/MagnumInternals.scala`, the reflective `DbTx` construction and why
- `modules/db/src/main/scala/skiff/db/schema/PortableSql.scala`, the five placeholder dialect vocabulary
- `modules/derives/src/main/scala/skiff/derives/InstantDbCodec.scala`, the runtime dialect sniff
- `modules/derives/src/main/scala/skiff/query/Query.scala`, 329 lines of typed builder over `Frag`
- `templates/*/src/main/scala/models/*.scala`, `derives DbCodec, Table, Form, AdminResource` in production
- `docs/DECISIONS.md`, "Query layer: **Magnum**, exposed for custom queries"
- `docs/CHOOSING.md`, "underneath is plain Magnum … it's a documented escape hatch, not a hack"
