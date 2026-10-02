# The database edge

The connection pool, the two scopes a handler opens, the query layer, and the type-level trick
that keeps a connection from leaking out of the block that borrowed it. This page is for you
if you've written `transact { ... }` and want to know what's underneath.

## One database, installed around `boot`

`DbApp` builds a `Database` from your settings, installs it in a process-wide holder, runs your
program, and closes it afterwards. You never name the `Database`. The whole API is two
functions:

```scala
import io.eezo.db.Scopes.*

read     { Table[Book].all() }
transact { Table[Book].insert(book) }
```

Both reach the installed database from any thread, so a handler, a live component's `handle`
and a background job all write the same line. A `transact` reached before anything was
installed, say in a static initialiser, throws with a message saying so.

## Two scopes

`read` borrows a connection, runs your block with a `DB` capability in scope, and gives the
connection back. `transact` does the same with a `Tx`, turning auto-commit off first, committing
when the block returns and rolling back when it throws. `Tx` extends `DB`, so a read works
inside a transaction and a write does not work inside a read:

```
this writes to the database, which requires a transaction.
Take `(using Tx)` if your caller has one, or run inside `transact { ... }`.
```

Nesting is refused at compile time where the compiler can see it. A `transact` inside a
`transact` says "already inside a transaction: remove this `transact`", and a `transact` inside
a `read` says to move it outward. What the compiler can't see is a helper that opens its own
scope in its body, so that's caught at run time: the second scope would take a second
connection, couldn't see the first one's uncommitted rows, and would block on any row the
first had locked. The error names both call sites and the fix, which is to let the helper take
`(using Tx)` and join the caller's scope.

The deliberate escape is `detached { ... }`: a transaction that commits on its own, whether or
not the caller's does. It borrows a second connection while the caller still holds the first,
which is why the pool's acquire timeout is short.

## A savepoint for the failure you expect

Inside a transaction, a duplicate key is something you planned for, and answering it shouldn't
roll back everything else:

```scala
transact {
  attempt[SQLException] { posts.insert(row) } match {
    case Left(_)  => Response.status(409)
    case Right(_) => Response.Redirect("/posts")
  }
}
```

The block runs on a savepoint. A thrown `SQLException` rolls the savepoint back and comes out as
a `Left`; the transaction carries on. Anything else travels on and rolls the whole transaction
back, a guard's refusal and a defect included. You name the type, and the narrowest you can
answer: `attempt[RuntimeException]` would swallow refusals too. `attempt` with no type and
`attempt[Throwable]` don't compile, because neither is a choice you made.

## Why a connection can't escape

The scope functions take their body as `Tx ?-> A`, a context function under Scala 3's capture
checking, and the `db` module is compiled with it on. Inside a capture-checked consumer, a
value that holds the scope can't leave the block: a lazy iterator over a result set returned
from `read` is a compile error. Outside one, which is every application unless it opts in, the
guarantee is silent, and the same mistake is caught at run time instead: the handle is retired
when the block ends, and any use after that throws `EscapedScope` with the usual cause named,
and a use from another thread throws `OffThread`. So capture checking costs you nothing when
you don't have it and gives you a compile error when you do, and the run-time check holds
either way.

The practical rule: force results inside the block. `list()` and `all()` already do, returning
a `List`. There's no cursor type yet.

## The pool

Opening a Postgres connection costs a TCP handshake, authentication and a backend process, so
eezo pools them, with HikariCP, whose stalls under load have already been found. Three
decisions on top of it:

- **Fixed size, ten by default.** A pool that shrinks when idle pays the connect cost on the
  first request after a quiet spell, which is the request somebody is watching. Ten is small
  for a reason: Postgres works on CPU and disk, and connections beyond what those serve only
  queue inside the database. Thousands of virtual threads waiting briefly on a small pool is
  the intended shape.
- **Starts empty.** Building the pool never waits on the network, so an application boots with
  Postgres down and fails the first query that needs it, after the acquire timeout, instead of
  failing in `main`. Under `eezo dev` that means every page that needs no database keeps
  serving, and the database coming back needs no restart.
- **A five second acquire timeout.** HikariCP's own thirty seconds reads as a hang to whoever
  is waiting, and `detached` can make one thread hold two connections, so a full pool can wait
  on itself. The timeout is what turns that into an error. Below 250 ms is refused, because
  HikariCP reads 0 as forever.

When no connection comes within the timeout, the failure is `ConnectionUnavailable`, a plain
exception and not an `SQLException`, so an `attempt[SQLException]` or a `problems` hook
doesn't catch it and pretend something recoverable happened. It reaches the boundary as a 500
with one line that says the pool size, the wait, the database with its password masked, and
what the driver last said.

## The query layer

`Table[Book]` carries typed column references, so a query is written against the fields:

```scala
Table[Book]
  .where(_.author === "Herbert")
  .where(_.pages > 300)
  .orderBy(_.title.asc)
  .limit(10)
  .list()
```

A `Query` is a pure description. It holds no capability, so you can build it anywhere, pass it
around and compose it conditionally; only `list()`, `first()` and `count()` need a `DB`. A value
never reaches the SQL string. Each becomes a `?` and a bind that goes through the same
`Column[A]` the macro used for `encode`, so there is no SQL injection to defend
against. `where`, `orderBy`, `limit`, `offset`, and the comparisons `===`, `<>`, `<`,
`<=`, `>`, `>=` and `in` are the vocabulary.

What it doesn't try to be is an ORM. There are no joins, no lazy relations, no identity map.
`deleteWhere` takes a predicate always, and `deleteAll()` is the only way to say the whole
table, so a `.where` dropped in a refactor stops compiling instead of wiping rows. `update(row)`
writes every column by key and answers a count: 0 means the row wasn't there, 1 means it was,
and two writers on one row see 1 each and the later one wins. There's no version column.

## Behind a derived resource

A derived route calls a `Store[Book]`, five operations, and nothing else. For a model with a
`Table`, that store is `JdbcStore`, which opens one scope per operation: `read` for the two
reads, `transact` for the three writes. Each derived handler makes exactly one store call, so
there's nothing for a transaction to span, and a handler that wants two writes under one
commit is a handwritten one calling `transact` itself.

Nothing in the store throws. A missing row is a `false` or a `None`, and `Resource` turns it
into a 404 one layer up, where HTTP is known.

## Why `db` never sees `http`

The database module depends on `core` and never on `http`. The thrown set with the statuses
lives in `http`. So nothing in `db`, and nothing in domain code that depends on `db` alone, can
reach for an HTTP status, and a query helper can't decide to be a 404. The module boundary
enforces what a convention would only ask for.

## Where to go next

How the model becomes the schema, and how the difference between the two is handled, is
[schema and migrations](schema-and-migrations.md).
