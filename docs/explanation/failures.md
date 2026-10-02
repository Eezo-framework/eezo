# Failures

A failure travels to the nearest boundary that owns it, and a boundary owns the failures it
names. That's the whole model. This page is about the two kinds of failure, the three
boundaries, and what each one answers.

## Two kinds

A **refusal** is the caller's fault and is expected: a missing row, a stale form, somebody
else's row, a body too large. It's raised where it's detected, it carries no status, and it
travels to the boundary that answers it with one.

A **defect** means the application is built wrong: a guard asked on an unguarded route, a
session too large for a cookie, a `require` that failed. Nobody handles a defect where it's
raised, because the fix is a code change. It reaches the boundary and is answered as a server
fault, with the message in the log and, in dev, on the page. A database or a socket gone
travels the same way. A defect found while the application starts, a resource mounted twice,
stops the boot and reaches no boundary at all.

## Three boundaries

| boundary | owns | answers |
|---|---|---|
| the request | everything thrown while a request is handled | a refusal with its status; your `problems` hook next; anything else a 500 |
| the savepoint, `attempt[E]` | only the `E` its caller names | a `Left(e)`, with the transaction carrying on |
| the socket, a mounted live page | what happens after the request is gone | an error frame, a log line, or a close code |

## Refusing from a handler

Throw one of the sealed set in `io.eezo.http`: `BadRequest(detail)`, `Forbidden(detail)`,
`NotFound(path)`, `MethodNotAllowed(allowed)`, `PayloadTooLarge(limit)`,
`NotImplemented(method)`. None of them carries a status. The request boundary picks it, and
renders a problem page with the status, a title and the detail. So a 404 is `throw
NotFound(request.path)`, which is what a derived `show` does for a missing row. Its detail
always reads `no route matches <path>`, row or route. For a 404 with your own wording, throw
your own exception and map it in the hook below.

`Response.status(404)` is the other way: a value, a bare 404, no detail and no page. It's also
the way to a status eezo doesn't model.

## Signalling a defect

Throw `IllegalStateException`, or call `require`, with a message that names the mistake and the
fix:

```scala
throw new IllegalStateException("no price list loaded: call Prices.load() in boot, before serve")
```

The request boundary offers it to your `problems` hook first. Uncovered, it answers a 500 and
logs it at ERROR with the stack trace. The client sees the message only when `dev` is on.
Otherwise the page reads "The server encountered an unexpected error."

## Your own exception: the `problems` hook

```scala
object Main extends EezoApp {
  override def problems: PartialFunction[Throwable, Problem] = {
    case OutOfStock(sku) => Problem(409, s"$sku is out of stock", "/cart")
  }
}
```

The hook is tried after eezo's sealed set and before the 500 fallback, so it can't remap a
`NotFound`. A hook answer of 500 or more is logged with its stack trace like any other.

A 4xx is never logged at ERROR. It's a client mistake, and logging it that way is how log noise
starts.

## Recovering inside a transaction

```scala
transact {
  attempt[SQLException] { posts.insert(row) } match {
    case Left(_)  => Response.status(409)
    case Right(_) => Response.Redirect("/posts")
  }
}
```

The body runs on a savepoint. A `SQLException` thrown inside is rolled back to the savepoint and
returned as a `Left`, and the rest of the transaction carries on. Everything else travels on:
a guard's refusal, a defect, a fatal error. Name the narrowest type you can answer, because
`attempt[RuntimeException]` would swallow refusals too. `attempt` with no type,
`attempt[Throwable]` and `attempt[Nothing]` don't compile.

## A live page, once mounted

The socket has no status to give, and the live layer throws none of the sealed set. What it
answers:

- A malformed frame from the browser gets an error frame carrying the problem, and a WARNING.
- A socket refused as it opens is closed with a code: 4403 for an origin not allowed or a user
  who isn't the page's, 4404 for an unknown page, 4409 for a page already connected.
- An event handler that throws an `Exception` gets a generic error frame, `event '<name>'
  failed`, and an ERROR line with the stack trace. The state doesn't move and the browser's
  DOM still matches the last tree. An `Error`, such as the `NotImplementedError` behind `???`,
  isn't caught there.
- A broken connection is detached, and the page waits its grace window for the browser to
  rejoin.

The mount itself still goes through the request boundary, and so do refusals raised while the
upgrade is built: a guard's `Forbidden`, or an upgrade matching no WebSocket route, answered
404.

## Where to go next

The dev server shows a defect's message on the page and production hides it. What else the
dev server does differently is [the dev loop](the-dev-loop.md).
