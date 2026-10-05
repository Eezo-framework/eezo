# Refuse a request, recover from one, answer your own errors

Three things a handler does with a failure: throw a refusal, map its own exception to a status,
or recover inside a transaction. [Failures](../explanation/failures.md) is the one-page model
behind this.

## Refuse

Throw one of the sealed set in `io.eezo.http`:

```scala
throw NotFound(request.path)
throw BadRequest("pages must be a number")
throw Forbidden("this Book belongs to another user")
throw MethodNotAllowed(Seq(Method.GET))
throw PayloadTooLarge(limit)
```

None of them carries a status. The request boundary picks it and renders eezo's problem page
with the detail. A derived `show` throws `NotFound` for a missing row; a bad path parameter
throws `BadRequest` for you.

`NotFound`'s detail always reads `no route matches <path>`. For a 404 with your own wording,
throw your own exception and map it below.

## A status without the page

`Response.status(409)` returns a value: a bare status with an empty body and no problem page.
It's also the way to any status eezo doesn't model.

## Your own exceptions

Map them once, in `Main`:

```scala
import io.eezo.http.Problem

object Main extends EezoApp {
  override def problems: PartialFunction[Throwable, Problem] = {
    case OutOfStock(sku) => Problem(409, s"$sku is out of stock", "/cart")
  }
  // ...
}
```

The hook is tried after eezo's own set and before the 500 fallback, so it can't remap a
`NotFound`. Anything it doesn't cover answers 500. A hook answer of 500 or more is logged with
its stack trace like any other.

## Defects

A mistake in the application, not in the request: a guard asked on an unguarded route, a
resource mounted twice, a price list nobody loaded. Throw `IllegalStateException`, or use
`require`, with a message that names the fix:

```scala
throw new IllegalStateException("no price list loaded: call Prices.load() in boot, before serve")
```

It answers 500, logs at ERROR with the stack trace, and shows the message to the client only
when `dev` is on. In production the page reads "The server encountered an unexpected error."
A defect found at boot, like a duplicate route, stops the boot instead.

## Recover inside a transaction

`attempt[E]` runs its block under a savepoint and gives the named failure back as a value, so
the transaction carries on:

```scala
transact {
  attempt[SQLException] { posts.insert(row) } match {
    case Left(_)  => Response.status(409)
    case Right(_) => Response.Redirect("/posts")
  }
}
```

It owns only the `E` its caller names. A `NotFound` thrown inside, or a guard's refusal, travels
on to the request boundary untouched.

## What the log says

| failure | status | log |
|---|---|---|
| refusal from the sealed set | its own | nothing |
| your exception, mapped below 500 | yours | nothing |
| your exception, mapped 500+ or unmapped | 500 | ERROR with stack trace |
| defect | 500 | ERROR with stack trace |
| database or socket gone | 500 | ERROR with stack trace |
