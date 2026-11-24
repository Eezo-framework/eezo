# eezo-upickle reads a body from its bytes, with upickle's own readers

**Status:** accepted

The `Serde` that `eezo-upickle` gives reads a request body in two passes over its bytes. The first pass parses the body into nothing, through a `NoOpVisitor`, and a failure there is `Malformed`. The second pass reads the body into the model, and a failure there is `Unfit`. No pass builds a `ujson.Value`. On a write, the fields eezo decided go into the model while the body streams through the reader, in the `Decided` visitor, and not by an edit of a parsed tree. The readers are the readers of `upickle.default`. The adapter adds one wrapper around them, `NoNulls`, which refuses a JSON `null` that a reader turns into a Scala `null`.

The reasons are these:

* The pass that failed tells a body that is not JSON from JSON that does not fit the model. So the adapter does not have to sort the exceptions that upickle wraps, and that sort could change with each upickle version.
* A `ujson.Value` holds every number as a `Double`, so a `Long` above 2^53 comes back changed. A read from that tree into the model is recursive, so a deeply nested body causes a `StackOverflowError`, and no `Either` can hold it. The ujson parser keeps a stack of its own, so a nested body costs no thread stack when the reader goes straight from the bytes.
* The decided fields go in on the way past for the same two reasons. The reader skips each field of the body under a decided name, each time the body names it, and puts the decided values in last. So a body that says nothing under a decided name reads the same as a body that says something else there. The reader compares a name after the parser undoes its escapes, so no spelling of a decided name gets through. JSON that is not an object at the top goes to the model unchanged, and the model refuses it in its own words.

## Considered options

**Parse into a `ujson.Value`, edit the tree, then read the tree.** Rejected, because a large `Long` comes back changed and a deep body overflows the thread stack.

**One pass, and sort the failure by the exception upickle throws.** Rejected, because the type of failure would then depend on which exception upickle wraps, and not on what was wrong with the body.

**Strict number readers**, which refuse a fraction, a number in quotes and an `Int` past its range. Rejected. Strict readers need an upickle `Api` object of eezo's, and the `ReadWriter` of that object is a different type from the `upickle.default.ReadWriter` that an author writes. The author's own instance would then stop winning over derivation. A model that must refuse such a number writes a `ReadWriter` of its own.

## Consequences

A JSON number reads into an `Int` or a `Long` as `upickle.default` reads it: the reader drops a fraction, reads a number in quotes, and wraps an `Int` past its range.

`upickle.default` reads a JSON `null` into a `String`, a key or a decimal as `null`, and into an `Int` as `0`. Without `NoNulls`, a client that sent no value would be read as a client that sent one, and the handler would meet the `null` later as a 500, and not as a 422 now. A reader that gives `null` a meaning of its own, as an `Option` reads it as `None`, keeps it. `NoNulls` raises its refusal inside the trace, so the reason names the path of the `null`.

The status that a `Malformed` or an `Unfit` earns is the decision of `http`, as [ADR 0006](0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md) records, so the adapter answers an `Either` and throws nothing of eezo's.

These tests in `UpickleAdapterSuite` pin the decision: "an integer past what a Double holds reads back exactly, with or without a key", "a body nested deeper than any model is refused as a failure, never thrown", "a decided name the body repeats, or spells with an escape, is skipped every time", "a number with a fraction, in quotes or past the range of an Int reads as upickle reads it", "upickle alone reads a JSON null into a String field as null and into an Int field as 0", and "a null field is Unfit at its path, whatever the field's type, with or without a key".
