# eezo
A Scala 3 web framework. Direct-style. The case class is the source of truth. Deploy with one command.

## Prerequisites
Building and running eezo requires JDK 25 or newer. JEP 491, delivered in JDK 24, removes virtual thread pinning on `synchronized` blocks, JDK 25 is the first LTS release carrying it, and eezo's server design depends on it. See `research/http-server.md` section 1.2 for the measurements.

## The example applications

eezo is not released yet, so both examples resolve it from the local ivy cache:

```bash
sbt publishLocalForExample      # publishes eezo and sbt-eezo locally, records the version
cd examples
sbt hello/run                   # http://localhost:8080/hello
sbt blog/run                    # http://localhost:8080
```

`examples/hello` is one handwritten route, no database and no derivation. The route is not mounted
anywhere: `src/main/scala/app/Hello.scala` defines `def hello`, and the sbt plugin turns the file's
name and location into `GET /hello` in a generated `io.eezo.generated.Routes`, which the
application names in its own `@main`.

`examples/blog` is one case class. `models/Post.scala` carries `derives Form, Resource`, and that
mounts seven CRUD routes — list, new, create, show, edit, update, delete — served in a browser over
an in-memory store the generated table mints. Its `app/Index.scala` is a handwritten route beside
them, listed first, because a handwritten route wins a path a derived one would also match.

Half of that table is then served under a prefix: `Main.scala` splits the routes on where they came
from and wraps only the derived ones in `Route.under("/admin")`. The handwritten index keeps
answering `GET /`, so the blog root is still http://localhost:8080 and its posts are at
`/admin/posts`. That is the shape most applications end up with, a public page at the root and the
screens that edit the data behind a prefix a deployment can guard on its own.

A mount moves the routes and the URLs the pages emit together, so the seven derived pages link to
each other without ever naming `/admin`. `app/Index.scala` is the page that has to name it: it
lives outside the mount, nothing rewrites what it emits, so it links with the plain string
`"/admin/posts"`, a finished address that stays exactly as written.

## Licence

eezo is released under the [MIT License](LICENSE).

```
Copyright (c) 2026 Riccardo Cardin and Daniel Ciocîrlan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

An application that depends on eezo carries no
obligation beyond preserving the copyright notice, and eezo takes on no
dependency that would add one. Attribution notices for third-party components
are collected in [NOTICE](NOTICE).
