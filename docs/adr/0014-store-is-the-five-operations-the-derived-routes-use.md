# Store is the five operations the derived routes use

**Status:** accepted

The seven derived routes of a model read and write its rows. The derivation is in `http`, which cannot see `db`. The two layers meet at a trait in `core`, `Store[A]`, which has two implementations: the in memory store in `http` and the JDBC store in `db`. Issue #112 named the risk of such a seam: it grows into a second and weaker query API beside the query DSL of `db`. Settled on issue #145, and for ownership on issue #178.

## Decision

`Store[A]` has five operations, one for each thing that the derived routes do: `all`, `find`, `insert`, `update` and `delete`. It is the vocabulary of the derivation, and not the data access API of eezo. A route that needs a filter, a sort or a page is a handwritten route on the query DSL of `db`, and it does not use `Store`.

Ownership adds no operation. `OwnedStore.by` narrows a store to the rows of one owner and gives back a plain `Store[A]` with the same five operations.

## Considered options

**A `Store` with filter, sort and page operations.** Rejected. It puts a second query language on the seam. The seam is in `core`, so each new operation is a change to both stores and to each module that reads them, and the DSL of `db` already answers these queries.

**A sixth operation on `Store` that reads the rows of one owner.** Rejected. Each derived handler must then choose between the two reads, and the refusal of a row of another owner becomes a check that a handler must remember. With narrowing, a row of another owner is absent from the narrowed store. A derived handler cannot tell a narrowed store from a whole one, so it has no check to forget.

## Consequences

The two stores must answer the five operations in the same way. A contract in the test sources of `core` holds the assertions, and the suites of both stores mix it in, so a difference is a failing test in the module that has it.

A sixth operation needs a new decision that replaces this one.
