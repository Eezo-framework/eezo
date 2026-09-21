# Connection pooling for a JDBC runtime on virtual threads

Research for ticket [#195](https://github.com/Eezo-framework/eezo/issues/195), part of map [#193](https://github.com/Eezo-framework/eezo/issues/193). Checked on 2026-09-21. Primary sources only: every claim links to the repository, specification or first party documentation that owns it.

## The question

What are the real options for pooling Postgres connections under eezo's constraints: JDK 25, a Jetty virtual thread pool, one transaction scope per thread, and `modules/db` compiled with capture checking?

## Short answer

1. **Pinning is a closed chapter on eezo's floor.** JEP 491 shipped in JDK 24, so `synchronized` no longer pins a virtual thread. On JDK 25 neither HikariCP nor pgjdbc can pin a carrier through a monitor. pgjdbc had already moved to `ReentrantLock` in 42.6.0, and eezo is on 42.7.1.
2. **HikariCP had a second, separate virtual thread problem, and it was fixed only in 7.1.0 (June 2026).** The hand off loop in `ConcurrentBag` called `Thread.yield()`, which does not unmount a virtual thread, so returning threads could saturate every carrier. Any HikariCP older than 7.1.0 is the wrong version for eezo.
3. **The pgjdbc pooling classes are not an option.** `PGPoolingDataSource` is deprecated since 42.0.0 and the pgjdbc documentation says not to use it. `PGConnectionPoolDataSource` is not a pool: it is the hook an application server's pool calls.
4. **Every Scala library surveyed delegates.** Doobie, Quill and Magnum point at HikariCP. ScalikeJDBC ships Commons DBCP2 by default and documents HikariCP through a `DataSource` wrapper. None of them wrote a pool.
5. **A hand rolled semaphore pool is small because eezo's shape removes the hard parts.** Connections are borrowed only inside `Run.tx` and `Run.read`, under `try`/`finally`, so user code cannot leak one. JEP 444 itself names the semaphore as the construct for limiting access to a scarce resource from virtual threads. The cost is owning validation, lifetime and shutdown forever.

**Recommendation:** HikariCP 7.1.0 or later, hidden entirely behind `Pool`, configured through a wrapping `DataSource` so that `databaseInit` keeps its `Connection -> Unit` shape. The hand rolled pool is the credible fallback if two new jars on the `db` classpath (HikariCP and `slf4j-api`) are judged too heavy. Reasoning and the implied implementation issues are at the end.

## eezo's side of the seam

Read from the repository at `4156da4`.

* `modules/db/src/main/scala/io/eezo/db/engine/Pool.scala`: `final class Pool private[eezo] (url, user, password, init: Connection -> Unit)` with three members, `acquire(): Connection`, `release(c: Connection): Unit` and `close(): Unit`. Today `acquire` opens through `DriverManager` and runs `init`, `release` closes, `close` does nothing. The class comment says the interface is deliberately the one a real pool needs.
* `init` runs on **every** connection created, not once per borrow. It carries `search_path`, `application_name` and statement timeouts, and per suite schema isolation in tests depends on it.
* `engine/Run.scala` is the only caller. Both `tx` and `read` acquire inside `Scope.enter` and release in `finally`. A user cannot hold a connection past a block, and capture checking stops the handle escaping.
* `engine/Scope.scala` holds the open scope in an `InheritableThreadLocal` and refuses a second scope on the same thread or on a forked thread. The one escape hatch is `detached { ... }`, which lets a thread that already holds a connection take a second one.
* `engine/Database.scala` owns the `Pool`; `Database.close()` calls `pool.close()`. `DbApp` builds, installs and closes it around each command. Its comment already warns that installing is free only because `Database.connect` never touches the network.
* `DbInit.scala` exposes `databaseUrl`, `databaseUser`, `databasePassword` and `databaseInit`. There is no pool size or timeout setting yet.
* `modules/db` depends on the Postgres driver and nothing else at runtime (`project/Dependencies.scala`, `build.sbt`).

Two consequences matter for every option below.

**The deadlock bound.** HikariCP's sizing page gives the minimum pool size that cannot deadlock when one thread holds several connections: `pool size = Tn x (Cm - 1) + 1`, with `Tn` threads and `Cm` connections per thread ([About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)). `Scope` holds `Cm` at 1 for ordinary code, so any pool size is safe. `detached` raises `Cm` to 2, and on virtual threads `Tn` is effectively unbounded, so no finite pool satisfies the formula. The only defence is an acquire timeout. A pool without one can hang an eezo application for good.

**A latent release bug.** In `Run.tx` the `finally` block runs `c.setAutoCommit(prev)` before `db.pool.release(c)`. On a broken connection the first call throws and the second is skipped. Today that loses nothing because the socket is already dead. With a real pool it leaks a slot. Whatever is chosen, release must be unconditional and a connection that failed must be discarded, not returned.

## Virtual threads: what the JDK says

* **JEP 444, Virtual Threads, JDK 21.** A virtual thread was pinned to its carrier "when it executes code inside a `synchronized` block or method", and the advice was to replace long lived `synchronized` sections with `ReentrantLock`. The same JEP says virtual threads "should never be pooled", and: "Do not be tempted to pool virtual threads in order to limit concurrency. Instead use constructs specifically designed for that purpose, such as semaphores." On thread locals: "do not use thread locals to pool costly resources among multiple tasks." ([JEP 444](https://openjdk.org/jeps/444))
* **JEP 491, Synchronize Virtual Threads without Pinning, JDK 24.** Virtual threads that block in `synchronized` now "release their underlying platform threads". Migration to `ReentrantLock` "will no longer be necessary". The cases that still pin are narrow: blocking while loading a class during symbolic resolution, blocking inside a class initializer, and waiting for another thread to initialize a class. ([JEP 491](https://openjdk.org/jeps/491))

eezo's floor is JDK 25, so monitor pinning is off the table for every option. What remains is behaviour that was never about monitors: busy spins, `Thread.yield()`, and per thread caches.

## Option 1: HikariCP

**What it is.** One jar of about 173 KB (`HikariCP-7.1.0.jar` on Maven Central) with one required dependency, `slf4j-api`. Javassist, Micrometer, Dropwizard metrics and Prometheus are optional or provided in its `pom.xml` ([pom.xml at 7.1.0](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.1.0/pom.xml)). The 7.x line requires Java 11 ([README](https://github.com/brettwooldridge/HikariCP/blob/dev/README.md)).

**Virtual thread history, in order.**

* 2019, [issue #1463](https://github.com/brettwooldridge/HikariCP/issues/1463) "Make HikariCP loom friendly": still open. It asks for `synchronized` to be replaced. The matching pull requests, [#2027](https://github.com/brettwooldridge/HikariCP/pull/2027) and [#2055](https://github.com/brettwooldridge/HikariCP/pull/2055), were closed without merging. `HikariPool` at 7.1.0 still declares `synchronized` on `shutdown`, `suspendPool`, `resumePool` and `fillPool` ([HikariPool.java](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.1.0/src/main/java/com/zaxxer/hikari/pool/HikariPool.java)). None of these is on the borrow path, and after JEP 491 none of them pins.
* 2025, [issue #2293](https://github.com/brettwooldridge/HikariCP/issues/2293), a deadlock at pool initialisation under virtual threads: closed. A contributor notes in the thread that JEP 491 removes that scenario on JDK 24 and later.
* 2025, [issue #2329](https://github.com/brettwooldridge/HikariCP/issues/2329), high CPU with 6.3.0 on JDK 21 and virtual threads. The changelog shows the response: 7.0.1 "decrease thread yield frequency in ConcurrentBag.requite()" and 7.0.2 the same for `unreserve()` ([CHANGES](https://github.com/brettwooldridge/HikariCP/blob/dev/CHANGES)).
* 2026, [issue #2398](https://github.com/brettwooldridge/HikariCP/issues/2398): "ConcurrentBag.requite() yield-spin still saturates all carrier threads under virtual thread load" on 7.0.2. The analysis in the thread: "`Thread.yield()` does not unmount a virtual thread from its carrier", so the hand off spin burns carriers. Fixed by [PR #2402](https://github.com/brettwooldridge/HikariCP/pull/2402), merged 2026-06-11, which parks for 10 microseconds when the returning thread is virtual. The PR reports, on JDK 25.0.3 with 2,000 virtual threads contending for 50 entries, about 6.66M borrows per second on 7.0.2 against about 11.63M on the fix. The maintainer asked for the JDK 25 run himself.
* **7.1.0** is the first release with that fix: "merged #2402 avoid virtual-thread yield spin in ConcurrentBag" ([CHANGES](https://github.com/brettwooldridge/HikariCP/blob/dev/CHANGES)). The tag dates from 2026-06-14.
* Still open and worth watching: [#2268](https://github.com/brettwooldridge/HikariCP/issues/2268) "Connection leak when using virtual threads" and [#2151](https://github.com/brettwooldridge/HikariCP/issues/2151) on virtual thread performance. Neither has a confirmed maintainer diagnosis. [#2366](https://github.com/brettwooldridge/HikariCP/issues/2366), titled a "death spiral" on 7.0.2, was closed by its reporter as a database trigger deadlock, not a HikariCP fault.

**One residual mismatch.** `ConcurrentBag.borrow` looks first in a `ThreadLocal` list of connections this thread used before ([ConcurrentBag.java](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.1.0/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java)). With one fresh virtual thread per request that list is always empty, so every borrow falls through to the shared list scan and then the `SynchronousQueue` hand off. This costs a small allocation per thread and loses an optimisation. It is not a correctness problem, and it is the pattern JEP 444 warns about only in the sense that the cache is useless, not harmful.

**Sizing advice.** "connections = ((core_count * 2) + effective_spindle_count)", and "you want a small pool, saturated with threads waiting for connections" ([About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)). This fits virtual threads well: thousands of cheap threads may wait on ten connections. The README default is `maximumPoolSize` 10 with `minimumIdle` equal to it, a fixed size pool, which the README recommends.

**The five properties** (all from the [README](https://github.com/brettwooldridge/HikariCP/blob/dev/README.md) unless a source file is named).

* Leak detection: `leakDetectionThreshold`, default 0 (off), minimum 2000 ms. It logs a warning with the borrower's stack trace and does **not** reclaim the connection ([ProxyLeakTask.java](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.1.0/src/main/java/com/zaxxer/hikari/pool/ProxyLeakTask.java)). The message names the thread by `Thread.getName()`, which is empty for an unnamed virtual thread, the same problem `Scope.threadName` already solves on eezo's side.
* Acquire timeout: `connectionTimeout`, default 30000 ms, minimum 250 ms, then an `SQLException`.
* Validation on borrow: `Connection.isValid()` for JDBC4 drivers (`connectionTestQuery` only for legacy drivers), bounded by `validationTimeout` (default 5000 ms), and skipped when the connection was used within the last 500 ms (`aliveBypassWindowMs` in `HikariPool.java`). Also `keepaliveTime` (default 2 minutes) and `maxLifetime` (default 30 minutes) in the background.
* Shutdown: `HikariDataSource.close()`. `HikariPool.shutdown()` soft evicts idle connections, waits for the adder, then for up to 10 seconds repeatedly calls `Connection.abort()` on connections still in use, then stops its executors.
* Init hook: `connectionInitSql` is a single SQL string run on each new connection. It is not a callback. See the seam section for how eezo keeps its function.
* Start up: `initializationFailTimeout` defaults to 1, so constructing the pool opens a connection and fails fast.

## Option 2: the pgjdbc `ds` package

* **`PGConnectionPoolDataSource` is not a pool.** Its own class comment: "The app server or middleware vendor should provide a DataSource implementation that takes advantage of this ConnectionPoolDataSource." ([source](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/ds/PGConnectionPoolDataSource.java)). It hands out `PooledConnection` objects for someone else's pool to manage. The pgjdbc documentation says the same: the application server configuration refers to it, and application code gets a `DataSource` from the server ([Connection Pools and Data Sources](https://jdbc.postgresql.org/documentation/datasource/)). eezo has no application server, so this class gives eezo nothing.
* **`PGPoolingDataSource` is a pool, and is deprecated.** "@deprecated Since 42.0.0, instead of this class you should use a fully featured connection pool like HikariCP, vibur-dbcp, commons-dbcp, c3p0, etc." ([source](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/ds/PGPoolingDataSource.java)). The documentation: "In general it is not recommended to use the PostgreSQL provided connection pool", because "connections are never closed until the pool itself is closed", "there is no way to shrink the pool", and "Its error handling sometimes cannot remove a broken connection from the pool". It has no leak detection, no acquire timeout and no validation on borrow.
* **The driver itself is virtual thread ready.** pgjdbc 42.6.0 (2023-03-17): "refactor:(loom) replace the usages of synchronized with ReentrantLock", [PR #2635](https://github.com/pgjdbc/pgjdbc/pull/2635) ([CHANGELOG](https://github.com/pgjdbc/pgjdbc/blob/master/CHANGELOG.md)). eezo pins 42.7.1. The current release is 42.7.13 (2026-07-06).

Verdict: zero dependency weight, and nothing usable. Ruled out by its own maintainers.

## Option 3: a hand rolled semaphore pool

There is no upstream source for a design that does not exist yet, so this section states what the primary sources license and what eezo would have to write.

* **The construct is the sanctioned one.** JEP 444 names semaphores as the way to limit concurrency over a scarce resource from virtual threads. A `java.util.concurrent.Semaphore` parks a virtual thread without holding a carrier, and `tryAcquire(timeout, unit)` gives the acquire timeout that the deadlock bound above makes mandatory.
* **What a semaphore alone does not give.** The HikariCP issue thread makes the point precisely: a semaphore "would ensure you don't overwhelm the database server with a bunch of connections but wouldn't solve the expense of connection establishment" ([#1463](https://github.com/brettwooldridge/HikariCP/issues/1463)). So the pool needs an idle queue beside the permits.
* **Minimum honest feature list**, taken from what HikariCP does and what `PGPoolingDataSource` was deprecated for lacking: a fair `Semaphore` of `maxSize` permits; a `ConcurrentLinkedDeque` of idle connections with their last use time; `tryAcquire` with a timeout and an error that says the pool is exhausted; `Connection.isValid(seconds)` on borrow when the connection has been idle longer than a short window; a maximum lifetime checked on release, since Postgres side restarts and proxies drop old sockets; discard instead of return when the borrower saw an `SQLException`; `close()` that stops new borrows, closes idle connections and waits a bounded time for the rest.
* **Leak detection is nearly free, because leaks are nearly impossible.** `Run.tx` and `Run.read` are the only borrowers and both release in `finally`. What can still happen is a scope held for a very long time. `Scope` already knows the owner thread and can already print the first application frame, so a "held longer than N seconds" warning can carry a better message than HikariCP's, with no extra dependency.
* **Dependency weight:** zero jars. Roughly 100 to 150 lines in `Pool.scala`, plus tests that need a real Postgres for the validation and lifetime paths.
* **The real cost** is that the unglamorous cases become eezo's to own: a server that restarts mid borrow, a proxy such as Fly's or PgBouncer that closes idle sockets, a slow `isValid`, shutdown racing a request. The list of limitations pgjdbc published about its own pool is the list of bugs a small pool grows.

## Option 4: what the Scala libraries do

| Library | What it ships or recommends | Source |
| --- | --- | --- |
| Doobie | A separate `doobie-hikari` module with `HikariTransactor`, presented as the production path. `DriverManagerTransactor` is for "test and for experimentation": no pooling and "no upper bound on the number of concurrent connections". The connect `ExecutionContext` should be bounded to the pool size, "since any more threads are guaranteed to be blocked". Doobie's build is on HikariCP 7.1.0. | [Managing Connections](https://typelevel.org/doobie/docs/14-Managing-Connections.html), [build.sbt](https://github.com/typelevel/doobie/blob/main/build.sbt) |
| Quill | "Quill uses HikariCP for connection pooling." HikariCP is a compile dependency of the JDBC modules (6.3.1, with `slf4j` excluded), configured through HikariCP's own property names. A plain `DataSource` can be passed instead. | [Contexts](https://zio.dev/zio-quill/contexts/), [build.sbt](https://github.com/zio/zio-quill/blob/master/build.sbt) |
| Magnum | Ships no pool and no pool dependency. `Transactor(dataSource)` takes any `javax.sql.DataSource`. The README says: "for performance, a JDBC connection pool like HikariCP". | [README](https://github.com/AugustNagro/magnum/blob/master/README.md), [build.sbt](https://github.com/AugustNagro/magnum/blob/master/build.sbt) |
| ScalikeJDBC | Ships Commons DBCP2 as a compile dependency and default (`commons-dbcp2` 2.14.0, a jar of about 223 KB that also pulls `commons-pool2` and `commons-logging`). DBCP 1 and BoneCP are `provided`. HikariCP is documented through `DataSourceConnectionPool`. `ConnectionPoolSettings` carries `initialSize`, `maxSize`, `connectionTimeoutMillis`, `validationQuery`. | [Connection Pool](https://scalikejdbc.org/documentation/connection-pool.html), [build.sbt](https://github.com/scalikejdbc/scalikejdbc/blob/master/build.sbt) |

Two readings. Nobody in this ecosystem wrote their own pool, and three of four name HikariCP. Magnum is the closest relative to eezo (direct style, Scala 3, context functions) and it takes the `DataSource` seam and leaves the pool to the user. eezo cannot do that: `Pool` is `private[eezo]` by design and the stranger from the talk should not have to choose a pool.

## Comparison

| | HikariCP 7.1.0 | pgjdbc `PGPoolingDataSource` | Hand rolled semaphore pool | Commons DBCP2 (ScalikeJDBC's default) |
| --- | --- | --- | --- | --- |
| Dependency weight | 1 jar, about 173 KB, plus `slf4j-api` | none, already in the driver | none | 3 jars: `commons-dbcp2` about 223 KB, `commons-pool2`, `commons-logging` |
| Leak detection | `leakDetectionThreshold`, logs a stack trace, does not reclaim | none | not needed for leaks (borrow is bracketed in `Run`); a long hold warning is a few lines | `removeAbandonedOnBorrow` (off by default, 300 s timeout) can reclaim; `logAbandoned` logs the stack |
| Acquire timeout | `connectionTimeout`, default 30 s, minimum 250 ms | none documented | `Semaphore.tryAcquire(timeout)` | `maxWaitMillis`, default waits indefinitely |
| Validation on borrow | `isValid()` unless used in the last 500 ms; keepalive and max lifetime in the background | none; "cannot remove a broken connection" | `isValid()` after an idle window; max lifetime on release; all eezo's to write | `testOnBorrow` on by default, `validationQuery` or else `isValid()` |
| Shutdown | `close()`: evict idle, abort in use for up to 10 s, stop executors | `close()` closes all | `close()`: refuse borrows, close idle, bounded wait; eezo's to write | `close()` |
| Virtual thread behaviour | No pinning on JDK 24 and later. The `Thread.yield()` carrier saturation is fixed in 7.1.0 only. Thread local cache is dead weight with a thread per request. Two virtual thread issues still open upstream. | Deprecated since 42.0.0, not evaluated further | Semaphore parks cleanly; the construct JEP 444 recommends | Not researched against primary sources for virtual threads; listed for the dependency comparison only |
| Maintainer's own verdict | Actively maintained; maintainer engaged on JDK 25 results | "not recommended" | none | recommended by pgjdbc docs as an alternative |

DBCP2 appears because ScalikeJDBC ships it. Its properties are from the [DBCP configuration page](https://commons.apache.org/proper/commons-dbcp/configuration.html) and its dependencies from its published POM. It is heavier than HikariCP, its default acquire wait is unbounded, and it is not a candidate.

## What each option demands of the `Pool` seam

The shape `acquire(): Connection`, `release(c): Unit`, `close(): Unit` survives every option. The class comment's promise, that adding reuse "changes this file and nothing else", nearly holds. The exceptions are listed per option.

**HikariCP.**

* `Pool` holds a `HikariDataSource`. `acquire` is `ds.getConnection()`, `release` is `c.close()` on the proxy (which returns it and resets auto commit, read only, isolation, catalog and network timeout), `close` is `ds.close()`. No signature changes.
* `init: Connection -> Unit` does not map onto `connectionInitSql`, which is one SQL string. The way to keep the function is `HikariConfig.setDataSource` with a small `DataSource` whose `getConnection` opens through the driver and then runs `init`. HikariCP calls it once per physical connection, which is exactly the DESIGN 8.7 contract. This wrapper is a class inside `Pool.scala` holding a pure function, so capture checking has nothing new to track: no closure over `this` crosses into user code, and HikariCP itself is plain Java seen through its public API.
* `modules/db` gains `HikariCP` and `slf4j-api`. `http` already has `slf4j-api` through Jetty, and `http` does not depend on `db`, so there is no conflict, but the umbrella must end with exactly one SLF4J binding story or HikariCP's start up and leak warnings go nowhere.
* Start up changes meaning: the default `initializationFailTimeout` makes `Database.connect` touch the network. `DbApp`'s comment already anticipates this. Commands that never query (code generation, printing SQL) must either build the pool lazily or set `initializationFailTimeout` to a negative value so the pool starts empty.
* `DbInit` needs new members for pool size and acquire timeout, with defaults. Suggested by the sources: fixed size 10, acquire timeout well under 30 s so a `detached` deadlock surfaces as an error a person can read.
* Pin the floor at 7.1.0 in `Dependencies.scala` with a comment citing issue 2398.

**pgjdbc pooling classes.** Nothing to map. `PGConnectionPoolDataSource` would still need a pool written around it, which is Option 3 with an extra layer. `PGPoolingDataSource` would fit the three methods and fail the hardening goal on every row of the table.

**Hand rolled semaphore pool.**

* All of it lives in `Pool.scala`. The constructor gains `maxSize`, `acquireTimeout`, `maxLifetime` and an idle validation window. `init` keeps its exact type and is called where a physical connection is opened, as today.
* `release` must learn whether the connection is still good. Either `release(c, broken: Boolean)`, or `Run` calls a new `discard(c)` from its `catch`. This is the one signature change, and it touches `Run.scala`, not only `Pool.scala`.
* Capture checking: the pool's state is a `Semaphore`, a deque and an `AtomicBoolean`, all Java types with no capabilities. `init` is already typed as pure (`->`). The risk the repo has met before is a lambda that closes over `this` inside a capture checked class; a background reaper thread would be such a lambda. Avoid it by doing lifetime and idle checks on the borrow and release paths, with no background thread at all. That also removes a shutdown race.
* Tests need the real database for the broken connection paths (`pg_terminate_backend` is the usual lever), under the existing colima and `-Dapi.version` constraints.

**Both real options** need the `Run.tx` fix: `release` (or `discard`) must run even when `setAutoCommit(prev)` throws, and the reset belongs inside the pool, not the caller.

## Decision

Adopt **HikariCP 7.1.0 or later behind `Pool`**, with the `DataSource` wrapper for `init`.

* The target reader of map 193 is a stranger deploying to Fly, Render or Heroku. Those platforms put proxies and restarts between the app and Postgres. Keepalive, max lifetime, eviction on connection errors and abort on shutdown are the exact features that environment exercises, and HikariCP has years of production evidence for them. A new pool has none.
* The virtual thread objections to HikariCP are now historical on eezo's floor: monitors stopped pinning in JDK 24, and the yield spin was fixed in 7.1.0 with measurements on JDK 25.
* Every peer library made the same call.
* The cost is two jars on `db` and a logging question eezo has to answer anyway.
* `Pool` stays `private[eezo]`, so this is reversible. If the dependency is later judged too heavy, the hand rolled design above is a drop in replacement behind the same three methods. This follows the repo's habit of shipping the concrete class and extracting later.

If the owner prefers zero dependencies in `db` above all, Option 3 is sound and the JDK's own guidance supports it. It should then be treated as a product surface with its own failure injection suite, not as a utility.

## Implementation issues this implies

Each is sized for `issue-to-pr`.

1. **`Run` releases unconditionally and reports broken connections.** Move the auto commit reset behind `Pool.release`; make sure a throw during rollback or reset cannot skip the release; add `discard` or a `broken` flag. No behaviour change with today's open and close pool. Do this first; both options need it.
2. **`Pool` backed by HikariCP.** Add `hikariCP` (7.1.0 floor, comment citing HikariCP issue 2398) and `slf4j-api` to `Dependencies.scala` and the `db` project. Wrap a driver backed `DataSource` that runs `init` per physical connection. Keep `acquire`, `release`, `close`. Extend the existing suite that proves `init` runs on every connection, and add one proving a second borrow reuses the first physical connection (compare `pg_backend_pid()`).
3. **Pool settings on `DbInit`.** `databasePoolSize` (default 10) and `databaseAcquireTimeout` (default a few seconds), with `EEZO_DB_POOL_SIZE` style environment overrides matching the existing naming. Document the sizing formula and the reason for a short timeout (`detached` can deadlock a full pool).
4. **Lazy or non failing start up for commands that never query.** Decide between constructing the `HikariDataSource` on first `acquire` and `initializationFailTimeout = -1`; keep `DbApp`'s promise that installing a `Database` costs nothing for code generation commands. Add a test that runs such a command with no database reachable.
5. **An exhausted pool error a stranger can act on.** Catch HikariCP's timeout `SQLException` in `Pool.acquire` and rethrow in eezo's voice: pool size, how long it waited, and the `detached` hint when the current thread already holds a scope.
6. **Logging story for `db`.** Decide where HikariCP's SLF4J output goes in an `EezoApp` and in a bare `DbApp`, so leak and eviction warnings are visible and there is no "no SLF4J providers" banner. Turn on `leakDetectionThreshold` in dev mode only.
7. **Shutdown order in `EezoApp`.** Jetty stops accepting and drains before `Database.close()`, so HikariCP's 10 second abort phase is a backstop and not the normal path. Add a test that a request in flight at shutdown commits.

## Sources

* JEP 444: https://openjdk.org/jeps/444
* JEP 491: https://openjdk.org/jeps/491
* HikariCP README: https://github.com/brettwooldridge/HikariCP/blob/dev/README.md
* HikariCP CHANGES: https://github.com/brettwooldridge/HikariCP/blob/dev/CHANGES
* HikariCP About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
* HikariCP source at tag `HikariCP-7.1.0`: `ConcurrentBag.java`, `HikariPool.java`, `ProxyLeakTask.java`, `pom.xml`
* HikariCP issues and pull requests: #1463, #2027, #2055, #2151, #2268, #2293, #2329, #2366, #2398, #2402
* pgjdbc data source documentation: https://jdbc.postgresql.org/documentation/datasource/
* pgjdbc source: `PGConnectionPoolDataSource.java`, `PGPoolingDataSource.java`; CHANGELOG entry for 42.6.0 and PR #2635
* Doobie, Managing Connections: https://typelevel.org/doobie/docs/14-Managing-Connections.html
* Quill, Contexts: https://zio.dev/zio-quill/contexts/
* Magnum README: https://github.com/AugustNagro/magnum/blob/master/README.md
* ScalikeJDBC, Connection Pool: https://scalikejdbc.org/documentation/connection-pool.html
* Commons DBCP configuration: https://commons.apache.org/proper/commons-dbcp/configuration.html
* HikariCP `PoolBase.java` at 7.1.0 (`resetConnectionState`) for what is reset on return
* Maven Central jar sizes, read from `Content-Length`: `HikariCP-7.1.0.jar` 172,996 bytes; `commons-dbcp2-2.14.0.jar` 222,783 bytes
