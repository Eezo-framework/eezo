# Core holds what two modules would write twice, and each shared type keeps a narrow charter

**Status:** accepted

`modules/core` has no dependencies of its own, and `http` and `db` both depend on it and never on each other. This ADR records the rule for what goes into `core`, and the decisions that bind each shared type there. Until now the scaladoc of these types carried them. Public scaladoc now tells a user how to use a member, so the decisions are recorded here.

## The rule for what core holds

A piece of code goes into `core` when two modules that cannot see each other both need it, and it needs nothing of its own. If each module wrote it, the two copies would come to disagree. Each type in `core` therefore knows neither layer.

* `Dispatch` holds `main`, the answer to an unknown command, `help` over the rows of every layer, and the `--json` flag with the one error shape it implies. It starts and stops nothing: no server, no database, no lifecycle hook. `program` is the one hook, for the reason ADR 0005 gives.
* `Id[T]` is a UUID with a phantom owner. `db` gives it a `Column`, `http` a `Field` and a `FromPath`. Each instance is a given in the companion of its own typeclass, downstream, and implicit search finds a given there as reliably as one in the companion of `Id`.
* `Parser` answers one question, "is this text an `Int`", that a form field and a configuration value both ask. `http`'s `Field` is composed over a `Parser` and adds only what a browser input needs.
* `util.snake` is one camel case rule. `db` turns a class name into a table name with it, and `http` turns the same class name into a route path.
* `Json` is the writer and reader that `db` uses for the schema snapshot and its command results, `http` for the route listing and `live` for its wire messages. It is not a JSON library, and `derives Api` is not built on it.

## Store and OwnedStore

`Store[A]` is the vocabulary the derivation speaks, not the data access API of eezo. It has five operations, one for each thing the seven derived routes do, and no sixth. A route that wants a filter, a sort or a page is a handwritten route on the query DSL of `db`. A wider `Store` would put a second query language on the seam, in the module that costs most to change. It is a trait because two implementations exist, the JDBC store and the in memory store, and nobody writes a third. It is one store for each model, because a store that must be told which bucket to read cannot be typed. Nothing in it throws, as ADR 0001 requires: a missing row is a `None` or a `false`, and `http` decides the 404.

`all()` is ordered by primary key, and the order is part of the contract, because a derived `index` renders it and a page that reorders itself on every write cannot be read. Insertion order was rejected: the JDBC store cannot keep it without a column that exists only for it, and a promise only one implementation can keep is not a seam. `insert` returns `Unit`, because every key a derived `create` inserts is minted by `Id.gen()` just before, so a conflict result would be a branch that is never taken.

`OwnedStore[A, V]` sits beside `Store` and not inside it. Narrowing answers a plain `Store[A]`, so everything after a narrowing still sees the five operations, a derived handler cannot tell a narrowed store from a whole one, and the refusal of a row of another owner is the absence of the row, not a check somebody must remember. `V` is a type parameter and not `Id[?]`, because `db` needs a `Column[V]` to bind it and a wildcard has none. `by` is not memoised: a cache keyed by owner grows with the number of users and never shrinks.

`OwnerOf` holds two things, the name and the getter, because the two modules need different parts of one fact: `db` renders the name into its owner condition, and `http` calls the getter to compare the owner of a row with the current user. Neither part can be derived from the other: a getter gives no column name at runtime, and a name gives no typed getter without a mirror.

`OwnedStoreContract`, in the test sources of `core`, holds the assertions both implementations must pass. The `http` and `db` suites mix it in through their test dependency on `core`, so a behaviour that one implementation has and the other has not is a failing test in the module that has it.

## Redacted

`Redacted` is not a case class, because `unapply`, `copy` and `productElement` would give the text to anyone. It is not a `String`, which `toString`, logging and interpolation show as it is. `equals` compares in constant time with `MessageDigest.isEqual`, as the session's `Secret` does, so that a check of a value an attacker sent cannot be timed. `hashCode` comes from a digest, because the hash of a short text can be read back. Its `Parser` never refuses, so no message of it can repeat the text, and what a valid key looks like is the business of the provider. `apply` is public so that a test builds one directly.

## Parser

A message of a `Parser` is shown to whoever typed the text, and the text may be a secret, so a message is a predicate about the value and never repeats the text. The `Boolean` reading is strict, because outside a browser there is no wire convention to honour, and a typo such as `ture` in a configuration value must be refused. The `on` of a checkbox is a convention of the browser, and the form side keeps its own reading for it. The `Option` reading is a named given, so that an optional form field can lift the reading its inner field was built with, which need not be the given one. The `Url` reading always gives `Absolute`, kept verbatim, because text from outside is a finished address and normalising would mangle its scheme.

## The JSON reader

`Json.parse` returns `Either` and does not throw, because the frames it reads come from a browser, every one is untrusted input, and a malformed one is an ordinary value that the page loop logs and survives. The message carries the offset, because a message without a position starts a debugging session. Numbers are integers only, like `Json.Num`: nothing eezo puts on the wire is fractional, and a client that sends `1.5` where the server expects an index must fail loudly. Nesting deeper than 64 levels is refused, so that a hostile frame inside the size cap of the transport is a parse error and not a stack overflow, because the reader is recursive.

Inside the reader a failure is an exception, `Parse`, and not an `Either` threaded through every production, because the reader is recursive and a failure is terminal: nothing recovers in the middle of a value. `Parse` never leaves `parse`, and it builds no stack trace, because its message is the whole payload.

`compact` writes one line, for a value that travels as one line of text, such as the marker lines of the dev loop, where a reader takes one line as one event. `render` stays the indented shape that every `--json` command prints, because a person reads those.

## Considered options

**Each module writes its own copy.** Rejected: two camel case rules, two JSON escapes or two readings of a number disagree on the day one of them changes, and the difference shows only in an application that has both layers.

**A JSON library as a dependency of core.** Rejected: `core` has no dependencies, so that every module can depend on it without a library arriving on its classpath. The writer and reader are small because they serve only what eezo writes and reads.

**A `Store` with filter, sort and page operations.** Rejected for the reason above: the query language belongs to `db`, and a handwritten route uses it directly.

## Consequences

The scaladoc of the types in `core` now says how to use them. A reader who wants to know why a type has its shape finds it here. A new member of `core` must pass the rule above: if only one module needs it, it goes to that module.
