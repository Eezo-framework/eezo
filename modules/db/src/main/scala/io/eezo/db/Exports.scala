package io.eezo.db

/** So that one import gets a user everything they write.
  *
  * The capabilities live in `io.eezo.db.capability` because they are a closed set of types with one
  * job, and the machinery behind them lives in `io.eezo.db.engine`; neither is something an
  * application should have to name. `import io.eezo.db.*` brings the two type aliases and `Scopes`
  * into scope, which is the whole vocabulary: `transact`, `read`, `using Tx`, `using DB`.
  */
export io.eezo.db.capability.{DB, Tx}
