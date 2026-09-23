# Failures

**A failure travels to the nearest boundary that owns it, and a boundary owns the failures it names.**
A **refusal** is the caller's fault and is expected: a missing row, a stale form, a foreign owner.
A **defect** means the application is built wrong, and the fix is a code change. A database or a
socket gone travels like a defect. The reasons are in
[ADR 0006](adr/0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md).

## The three boundaries

| boundary | owns | answers |
|---|---|---|
| request | everything thrown while a request is handled | a refusal with its status; the `problems` hook next; anything else a 500 |
| savepoint, `attempt[E]` | only the `E` its caller names | a `Left(e)`, the transaction carrying on |
| socket, a mounted live page | what happens after the request is gone | an error frame, a log line, or a detached connection |

## Refusing from a handler

Throw one of the sealed `EezoException` set in `io.eezo.http`: `BadRequest(detail)`,
`Forbidden(detail)`, `NotFound(path)`, `MethodNotAllowed(allowed)`, `PayloadTooLarge(limit)`,
`NotImplemented(method)`. None carries a status; the request boundary picks it, and the error page
shows the detail. So a 404 is `throw NotFound(request.path)`, which is what a derived `show` does for a
missing row. Its detail always reads `no route matches <path>`, row or route. For a 404 with your
own detail, throw your own exception and map it in the `problems` hook below to
`Problem(404, detail, path)`. `Response.status(404)` returns a value instead: a bare 404 with an
empty body, no detail and no error page. It is also the way to a status eezo does not model.

## Signalling a defect

Throw `IllegalStateException`, or call `require`, with a message that names the mistake and the fix:

```scala
throw new IllegalStateException("no price list loaded: call Prices.load() in boot, before serve")
```

The request boundary offers it to the `problems` hook first. Uncovered, it answers a 500 and logs it
at ERROR with the stack trace. The client sees the message only when `dev` is true; otherwise the
page reads "The server encountered an unexpected error."

## Your own exception: the problems hook

```scala
// in your object Main extends HttpApp
override def problems: PartialFunction[Throwable, Problem] = {
  case OutOfStock(sku) => Problem(409, s"$sku is out of stock", "/cart")
}
```

`HttpApp.problems: PartialFunction[Throwable, Problem]` is tried after eezo's sealed set and before
the 500 fallback, so it cannot remap a `NotFound`. What it does not cover is a 500. A hook answer of
500 or more is logged with its stack trace like any other.

## Recovering inside a transaction: `attempt[E]`

```scala
transact {
  attempt[SQLException] { posts.insert(row) } match {
    case Left(_)  => Response.status(409)
    case Right(_) => Response.Redirect("/posts")
  }
}
```

The body runs on a savepoint. `attempt[SQLException]` catches a `NonFatal` `SQLException` thrown in
the body and returns it as a `Left`, after the savepoint is rolled back; the rest of the transaction
carries on. It lets through any other throwable, a guard's refusal and a defect included, which
travel on and roll back the enclosing `transact`; a fatal error; and the body's failure when the
savepoint rollback itself fails. Name the narrowest type you can answer: `attempt[RuntimeException]`
would swallow refusals too. `attempt` with no type, `attempt[Throwable]` and `attempt[Nothing]` do
not compile.

## A live page, once mounted

The socket has no status to give, and live throws no `EezoException`. A malformed frame from the
browser is answered with an error frame carrying the problem, and a WARNING line. A socket refused
as it opens is closed with a code instead: 4403 for an origin not allowed or a user who is not the
page's, 4404 for an unknown page, 4409 for a page already connected. An event handler
that throws an `Exception` is answered with a generic error frame, `event '<name>' failed`, and an
ERROR line with the stack trace; an `Error`, such as the `NotImplementedError` of `???`, is not
caught there. A broken connection is detached, and the page waits its grace window for the
browser to rejoin. The mount still goes through the request boundary, and so do the
refusals raised while the upgrade is built: a guard's `Forbidden`, or an upgrade matching no
`Route.Ws`, answered 404.
