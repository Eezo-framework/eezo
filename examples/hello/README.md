# hello: the http edge alone

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo-http"` |
| entry trait | `io.eezo.http.HttpApp` |
| edges | http only |

One handwritten route and no database. `src/main/scala/app/Hello.scala` defines `def hello`, and
the sbt plugin turns the file's name into `GET /hello` in a generated `io.eezo.generated.Routes`,
which `Main.scala` names as its `routes`. That override is the whole entry point: `HttpApp` brings
`main`, the server and its overrides (`port`, `maxBodySize`, `problems`), and the two commands of
the http edge.

```bash
sbt publishLocalForExample      # once, at the repository root
cd examples
sbt hello/run                   # http://localhost:8080/hello
sbt "hello/run routes"          # the table: 1 route, GET /hello
sbt "hello/run dev"             # the listing and the reload client; `hello/eezoDev` restarts on save
```

## What does not exist here

The database edge. `eezo-http` does not depend on `eezo-db`, so nothing under `io.eezo.db` is on the
classpath, and the schema commands are not commands:

```
$ sbt "hello/run status"
[eezo] unknown command: status. Run `help` for the list.
```

A model deriving `Table` does not compile. The line, tried in `src/main/scala/models/Note.scala`
and removed again:

```scala
import io.eezo.db.Table

case class Note(id: Id[Note], text: String) derives Table
```

```
[error] -- [E008] Not Found Error: hello/src/main/scala/models/Note.scala:4:15
[error] 4 |import io.eezo.db.Table
[error]   |       ^^^^^^^^^^
[error]   |       value db is not a member of io.eezo
```

The cure is one changed line in `build.sbt`: depend on `"io.eezo" %% "eezo"` instead and extend
`EezoApp`, as `../blog` and `../todo` do. Opting out of an edge is the deliberate act, and it costs
that one line.
