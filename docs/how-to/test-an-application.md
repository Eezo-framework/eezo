<!-- draft -->
# Test an application

Drive the route table in a unit test, boot a real server against a real database with the testkit, and drive a live socket.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the table without a server: `Routes.table().dispatch(Request(...))`, a fresh in-memory store per call
- what dispatch does for the test: CSRF minting, the method override
- `eezo-testkit`: booting on port 0, a Postgres from testcontainers, a schema per suite
- driving HTTP with the built in client; driving a WebSocket
- testing a live component as pure functions: `handle` and `render`
- testing ownership against real rows

## Where the material is

- `modules/testkit/src/main/scala`
- `examples/blog/src/test/scala/PostOwnershipSuite.scala`, `UserSuite.scala`
- `site/src/test/scala/site/RoutesSuite.scala`, `LiveSuite.scala`
- `modules/http/src/main/scala/io/eezo/http/client/Http.scala`
