# The eezo API

Generated from the sources' scaladoc, for the eezo modules an application can depend on:

- `io.eezo.core`: configuration, the HTML node tree and DSL, the model key `Id[T]`, `Store`.
- `io.eezo.http`: the server, `Request` and `Response`, routing, `Form` and `Resource`, sessions, CSRF, the HTTP client.
- `io.eezo.db`: the connection, the `sql` interpolator, transactions, `Table`, `Schema`, migrations.
- `io.eezo.live`: `Component`, `Live`, `Topic`, the patch protocol.
- `io.eezo.auth`: `Guard`, `Password`, ownership.
- `io.eezo`: the umbrella's `EezoApp`.

What is not an API, the command line, the environment variables, the sbt plugin's tasks and the files a deploy writes, is in the reference section of the docs.
