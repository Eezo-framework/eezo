# eezo-upickle derives only a case class, and reads a body from its bytes with upickle's own readers

**Status:** accepted

The given `serde` is inline. It picks a `ReadWriter` for the model: the author's own instance when one is in scope, else one derived with `macroRW` when the model is a case class, else an instance it asks for by the model's type. It then hands that `ReadWriter` to `adapt`, a private method that is not inline, and `adapt` builds the private class `UpickleSerde`.

The `Serde` that `eezo-upickle` gives reads a request body in two passes over its bytes. The first pass parses the body into nothing, through a `NoOpVisitor`, and a failure there is `Malformed`. The second pass reads the body into the model, and a failure there is `Unfit`. No pass builds a `ujson.Value`. On a write, the fields eezo decided go into the model while the body streams through the reader, in the `Decided` visitor, and not by an edit of a parsed tree. The readers are the readers of `upickle.default`. The adapter adds one wrapper around them, `NoNulls`, which refuses a JSON `null` that a reader turns into a Scala `null`.

The reasons are these:

* The given is inline because it can ask its questions only where the model is known. Scala finds no `Mirror` and no `ClassTag` for an abstract type parameter.
* Only a case class is derived. `List` and `Option` are sums with a `Mirror` of their own, so a derivation of each type with a `Mirror` would accept them and end in an error about `::` or `Some`, not about the collection that the author wrote. Any other type is asked for as an instance, so the message of the compiler names that type where the `Serde` is summoned.
* `adapt` is the one place where the inline given hands off to compiled code. So `UpickleSerde` is compiled once and not copied into each call site, and it stays out of the public API: an application writes `serde`, never `adapt`.
* The pass that failed tells a body that is not JSON from JSON that does not fit the model. So the adapter does not have to sort the exceptions that upickle wraps, and that sort could change with each upickle version.
* A `ujson.Value` holds every number as a `Double`, so a `Long` above 2^53 comes back changed. A read from that tree into the model is recursive, so a deeply nested body causes a `StackOverflowError`, and no `Either` can hold it. The ujson parser keeps a stack of its own, so a nested body costs no thread stack when the reader goes straight from the bytes.
* The decided fields go in on the way past for the same two reasons. The reader skips each field of the body under a decided name, each time the body names it, and puts the decided values in last. So a body that says nothing under a decided name reads the same as a body that says something else there. The reader compares a name after the parser undoes its escapes, so no spelling of a decided name gets through. JSON that is not an object at the top goes to the model unchanged, and the model refuses it in its own words.

## Considered options

**A given that is not inline.** Rejected, because neither the `Mirror` nor the `ClassTag` that derivation needs resolves for an abstract `A`.

**Derive each type that has a `Mirror`.** Rejected, because a `List` or an `Option` of a model with no `ReadWriter` would then fail with an error about `::` or `Some`.

**Call JSON that is not an object `Malformed`**, as the four test rule of the ticket for the seam said. Rejected. `[1,2]` and `42` are JSON, and the `Serde` seam puts JSON that does not fit the model in `Unfit`. The accepted spike gave the same answer. `Malformed` would also refuse a payload that is a list or a number, which a `Serde` of such a type reads.

**Parse into a `ujson.Value`, edit the tree, then read the tree.** Rejected, because a large `Long` comes back changed and a deep body overflows the thread stack.

**One pass, and sort the failure by the exception upickle throws.** Rejected, because the type of failure would then depend on which exception upickle wraps, and not on what was wrong with the body.

**Strict number readers**, which refuse a fraction, a number in quotes and an `Int` past its range. Rejected. Strict readers need an upickle `Api` object of eezo's, and the `ReadWriter` of that object is a different type from the `upickle.default.ReadWriter` that an author writes. The author's own instance would then stop winning over derivation. A model that must refuse such a number writes a `ReadWriter` of its own.

## Consequences

A JSON number reads into an `Int` or a `Long` as `upickle.default` reads it: the reader drops a fraction, reads a number in quotes, and wraps an `Int` past its range.

`upickle.default` reads a JSON `null` into a `String`, a key or a decimal as `null`, and into an `Int` as `0`. Without `NoNulls`, a client that sent no value would be read as a client that sent one, and the handler would meet the `null` later as a 500, and not as a 422 now. A reader that gives `null` a meaning of its own, as an `Option` reads it as `None`, keeps it. `NoNulls` raises its refusal inside the trace, so the reason names the path of the `null`.

The status that a `Malformed` or an `Unfit` earns is the decision of `http`, as [ADR 0006](0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md) records, so the adapter answers an `Either` and throws nothing of eezo's.

These tests in `UpickleAdapterSuite` pin the decision: "a collection of a model with no ReadWriter fails to compile naming the collection", "a type with neither an instance nor a shape to derive from fails to compile, naming it", "JSON that is not an object is Unfit for a record, with or without a key", "a JSON array is never Malformed, since a Serde of a list reads it", "an integer past what a Double holds reads back exactly, with or without a key", "a body nested deeper than any model is refused as a failure, never thrown", "a decided name the body repeats, or spells with an escape, is skipped every time", "a number with a fraction, in quotes or past the range of an Int reads as upickle reads it", and "a null field is Unfit at its path, whatever the field's type, with or without a key".

These tests in `LibraryConstraintsSuite` pin the behaviour of Scala, upickle and ujson that the reasons above rest on, so each goes red on the day its constraint is gone: "Scala finds no Mirror and no ClassTag for an abstract type parameter", "List and Option are sums with a Mirror of their own, as a sealed trait is", "a ujson.Value holds a number as a Double, so a Long past 2^53 comes back changed", "the ujson parser reads a body nested 50000 deep with no thread stack", "the ujson parser undoes the escapes of a name before a visitor sees it", "an upickle Api of eezo's own would not find the author's upickle.default ReadWriter", "upickle alone reads a JSON null into a String field as null and into an Int field as 0", and "upickle reports a model's complaint with the byte offset in its message, not in its clue".
