<!-- draft -->
# Refuse a request, recover from one, answer your own errors

The three things a handler does with a failure: throw a refusal, map its own exception to a status, or recover inside a transaction.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- throwing the sealed set: `NotFound`, `BadRequest`, `Forbidden`, `MethodNotAllowed`, `PayloadTooLarge`, `NotImplemented`
- a 404 with your own detail, through the `problems` hook
- `override def problems: PartialFunction[Throwable, Problem]` in `Main`
- signalling a defect: `IllegalStateException` with a message naming the fix; what the client sees in dev and in production
- `attempt[E] { ... }` inside `transact`, the savepoint, and why it names one exception type
- `Response.status(409)` versus a thrown refusal: with and without the problem page
- what the log says for each

## Where the material is

- `docs/explanation/failures.md`
- `modules/http/src/main/scala/io/eezo/http/Errors.scala`, `Boundary.scala`, `Problem.scala`
- `modules/db/src/main/scala/io/eezo/db/engine/Run.scala` (`attempt`)
