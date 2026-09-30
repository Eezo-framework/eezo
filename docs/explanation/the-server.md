<!-- draft -->
# The server

Jetty on virtual threads, the JDK floor, what stop does, and what the framework serves without being asked.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- why Jetty, and the measurements behind it
- virtual threads and JEP 491: the 16x the floor exists to avoid
- draining on stop: refusing new requests, finishing the ones in flight, closing the database last
- `/eezo/health` and the other framework routes
- the body cap, the WebSocket message cap
- logging: `System.Logger`, SLF4J, one backend

## Where the material is

- `research/http-server.md`
- `modules/http/src/main/scala/io/eezo/http/HttpServer.scala`, `HttpConfig.scala`
- `README.md`, Prerequisites and Logging
