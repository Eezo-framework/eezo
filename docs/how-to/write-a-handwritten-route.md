<!-- draft -->
# Write a handwritten route

Mount a page or an endpoint from a file under `app/`, read what the request carries, and answer it.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- The filename rules
  - the seven REST names and the method and path each carries
  - a custom name is a `GET` at a segment of its own name, calling a `def` of that name
  - a custom `POST`: `app/health/Create.scala`
  - directories as segments, `_id` as `:id`, `__rest` as `*rest`
- Reading the request
  - `request.param[Int]("id")` and `paramOpt`, the 400 on a bad value, `FromPath` instances
  - `queryParam`, `header`, `cookie`, `form`
  - decoding a form body into a model: `Form.parse` / `request.as[A]` and the `Either` it returns
- Answering
  - `Response.Ok(html)`, `Response.Redirect`, `Response.status(n)`, a `Body.Bytes` with a content type
  - setting a cookie, amending the session
  - refusing: `throw NotFound(request.path)`, `BadRequest`, `Forbidden`
- Declaring `Guarded` when the application has a guard
- Where the generated table is written and how to read a compile error in it

## Where the material is

- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala` (`Verbs`, `routeFor`)
- `modules/http/src/main/scala/io/eezo/http/Request.scala`, `Response.scala`
- `examples/todo/src/main/scala/app/Health.scala`, `examples/hello`
- `README.md`, the paragraph on `target/.../Routes.scala` and the `// from` comments
