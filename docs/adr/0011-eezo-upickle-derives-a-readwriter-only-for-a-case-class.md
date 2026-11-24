# eezo-upickle derives a ReadWriter only for a case class

**Status:** accepted

The given `serde` of `eezo-upickle` builds the `Serde` of a model from a `upickle.default.ReadWriter`. It takes the author's own `ReadWriter` when one is in scope. Else, when the model is a case class, it derives one with `macroRW`. Else, it asks for a `ReadWriter` of the model's type, so the build stops there when none exists. This was decided on [issue #309](https://github.com/Eezo-framework/eezo/issues/309) and merged in PR #318. This record writes it down after the fact.

The reason is the error that an author sees. `List` and `Option` have a `Mirror` of their own, as a sealed trait has. A derivation for each type with a `Mirror` accepts a `List` of a model that has no `ReadWriter`, and the error then names `::` or `Some`. When only a case class is derived, the message of the compiler names the type that has no `ReadWriter`, at the place where the `Serde` is summoned. The given is inline because the author's `ReadWriter`, and the `Mirror` and the `ClassTag` that `macroRW` needs, resolve only where the type of the model is known, as the spike on issue #309 found.

## Considered options

**Derive each type that has a `Mirror`.** Rejected, because a `List` or an `Option` of a model with no `ReadWriter` then fails with an error about `::` or `Some`, and not about the model.

**No derivation: a given that is not inline and takes the `ReadWriter` as a using parameter.** Rejected, because each flat model then needs a `derives ReadWriter` clause before an API route can read it.

**Strict numbers, through an upickle `Api` of eezo's own**, which refuses a fraction, a number in quotes and an `Int` past its range. Rejected. The `ReadWriter` of that `Api` is a different type from the `upickle.default.ReadWriter` that an author writes, so the author's own instance stops winning over derivation.

## Consequences

A model inside a `List` or an `Option` needs a `ReadWriter` of its own, and so does each field of a derived case class. A JSON number goes into an `Int` or a `Long` as `upickle.default` reads it: a fraction is dropped, a number in quotes is read, and an `Int` past its range wraps. A model that must refuse such a number has a `ReadWriter` of its own.
