<!-- draft -->
# A request, read whole; a response, as a value

What a handler is handed and what it hands back, and the decisions behind both shapes.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `Request`: eager body under a cap, the 413, path params as text with typed reads, the query and the headers
- the `_method` override: what it applies to and what it refuses to do
- no attachment bag: capabilities arrive through the handler's signature
- `secure`, `X-Forwarded-Proto`, and the one question that header is trusted for
- `currentUser` as a fact written before the handler, never from client input
- `Response`: status as an `Int`, ordered headers with repeats, `Body` in three cases and the reserved fourth
- the session carried as a value, encoded once after dispatch
- why `Response.NotFound` does not exist
- a handler on a virtual thread: blocking is fine, and what JDK 25 has to do with it

## Where the material is

- `modules/http/src/main/scala/io/eezo/http/Request.scala`, `Response.scala`, `HttpServer.scala`
- `research/http-server.md`
