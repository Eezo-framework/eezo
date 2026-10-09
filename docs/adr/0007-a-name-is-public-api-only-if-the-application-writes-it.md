# A name is public API only if the application writes it

**Status:** accepted

A name is public API only if the application writes it, tests of that application included. Everything else is `private[eezo]` at most, and narrower, such as `private[live]` or `private[http]`, where a single module is the only reader. A package name promises nothing; the modifier does. A name that sits in a package called `internal` or `cli` but carries no modifier is public API as far as the compiler is concerned, and an application that finds it through its IDE can build on it.

This ADR says "public API" in full every time it means that sense. `CONTEXT.md` uses the bare word for a page or a model that needs no guard, and the two meanings must not blur.

The decision implies narrowing the names an application never writes. [Issue #211](https://github.com/Eezo-framework/eezo/issues/211) lists them: the JSON helpers in `core.internal` and `db.internal`, the `toJson` methods in `db.schema` that put `Json` in a signature an application can see, `Wire`, `Patch`, `Differ` and `NotCanonical` in `live`, everything in `http.cli` and `db.cli`, and `Csrf.Token.gen()`. Each is its own ticket. This record states the rule those tickets apply, not the state of the code when it was written.

## Considered options

**The application writes it, kept.** The line follows what an application actually spells, in its sources and in its tests. It is checked against the examples, not against what a name might be useful for: `Csrf.Token.gen()` was first kept for an application test that renders a form outside a request, and the recommendation became to narrow it once the examples showed no such test and showed that its token could not pass a submit outside `io.eezo` anyway.

**The convention as the promise.** Every name stays public API to the compiler, and a package called `internal` plus a documented sentence saying "do not use this" is the contract, as Cats Effect and Akka do. Rejected because nothing stops an application from building on a hand rolled JSON parser its IDE autocompletes. The sentence lives in a document an application author may never read; the modifier lives in the compiler, which every application author meets.

**The wire as public API.** The live protocol is spoken by the browser, so its frames, `Wire` and `Patch` among them, would be promised like any name an application writes. Rejected because the wire is a promise to `client.js`, which eezo ships itself, not to the application. That promise is kept by tests over the bytes, which break when a frame changes shape, not by the visibility of a Scala enum. Narrowing the Scala side leaves the wire exactly as stable and as guarded as it was.

## Consequences

eezo publishes no JSON API. An application that writes a JSON endpoint brings its own library; the parser eezo uses for its own snapshots and frames is not one an application is meant to reach.

A test that needs a CSRF token dispatches a request and reads the token off the rendered page, as the blog example's suites already do. If rendering a form outside a request ever becomes a real test scenario, the door for it belongs to testkit, not to `Csrf`.

The live wire and the `--json` output of a subcommand are promised by tests over the bytes. Neither is public API, and neither is less of a promise for it: the tests fail when the bytes change.

`NotCanonical` cannot be caught by name. In line with [ADR 0006](0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md), it is a defect in the view, not a refusal: a page whose rendered tree breaks a canonical rule is built wrong, the fix is a code change, and there is nothing for an application to handle.

The rule is kept by the modifiers and by review. There is no visibility suite and no golden list of the public API yet. Whether a golden list is worth keeping is left to the ticket that sorts the rest of the public API, [issue #263](https://github.com/Eezo-framework/eezo/issues/263), which also sorts the top level names this decision did not reach.

Recorded in full on [issue #211](https://github.com/Eezo-framework/eezo/issues/211).
