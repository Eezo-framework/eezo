# eezo-upickle reads a body in two passes over its bytes

**Status:** accepted

The `Serde` that `eezo-upickle` gives reads a request body in two passes over its bytes. The first pass parses the body and keeps nothing, and a failure there is `Malformed`. The second pass reads the body into the model, and a failure there is `Unfit`. No pass builds a `ujson.Value`. On a write, the fields that eezo decided go into the model during the second pass, and not by an edit of a parsed tree. This was decided on [issue #309](https://github.com/Eezo-framework/eezo/issues/309), whose spike edited a parsed `ujson.Obj`, and merged in PR #318. [Issue #328](https://github.com/Eezo-framework/eezo/issues/328) extended the second pass from the key to each field that eezo decides. This record writes it down after the fact.

The pass that fails tells a body that is not JSON from JSON that does not fit the model, so the adapter does not sort the exceptions that upickle wraps, and that sort can change with each upickle version. A `ujson.Value` holds each number as a `Double`, so a `Long` past 2^53 changes. A read from that tree into the model is recursive, so a deeply nested body overflows the thread stack, and no `Either` can hold that error. The ujson parser that reads the bytes keeps a stack of its own.

## Considered options

**Parse into a `ujson.Value`, edit the tree, then read the tree into the model.** This is what the spike did. Rejected, because a large `Long` changes and a deep body overflows the thread stack.

**One pass, and sort the failure by the exception that upickle throws.** Rejected, because the kind of failure then depends on which exception upickle wraps, and not on what is wrong with the body.

**Call JSON that is not an object `Malformed`**, as the four test rule of issue #309 said. Rejected. `[1,2]` and `42` are JSON, and the `Serde` seam puts JSON that does not fit the model in `Unfit`, as the spike on the issue also found. `Malformed` also refuses a list or a number that a `Serde` of such a type reads.

## Consequences

The adapter parses each body two times. A body that is JSON but not an object is `Unfit` for a case class, and a `Serde` of a list reads a JSON array. A `Long` past 2^53 reads back exactly, and a body nested deeper than any model is a failure, not a thrown error.
