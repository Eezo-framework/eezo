# A request, read whole; a response, as a value

A handler is a function from `Request` to `Response`. This page is about the two shapes: what
you're handed, what you hand back, and the decisions behind both.

## The request

```scala
final case class Request(
    method: Method,
    path: String,
    query: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    pathParams: Map[String, String],
    session: Session = Session.empty,
    secure: Boolean = false,
    currentUser: Option[String] = None
)
```

A case class, with the body already read. Building one by hand in a test is a constructor
call, and nothing in it is a Jetty type.

**The body is eager and capped.** eezo reads it whole before your handler runs, up to
`maxBodySize` (1 MiB by default, an override on your `Main`). It reads one byte past the cap
and refuses with a 413, because a `Content-Length` the client sent isn't a limit. There's no
stream to drain and no body a handler can forget to close.

**Path parameters are text, read with a type.** `request.param[Id[Book]]("id")` converts the
captured segment, and a value that won't convert is a 400 with the parameter named. `Int`,
`Long`, `UUID`, `String` and `Id[T]` are covered; a `FromPath[A]` instance adds another.

**The form is decoded once, lazily, and multi-valued.** `request.form` reads an
`application/x-www-form-urlencoded` body into a `Map[String, Seq[String]]`, because checkbox
groups and multi-selects produce repeats and a single-valued map would drop them in silence.
Multipart is out of scope today.

## The `_method` override

A browser issues `GET` and `POST` from markup and nothing else, and the derived routes need
`PUT` and `DELETE`. So a form carries a hidden `_method` field, and eezo applies it before
dispatch, which means the route table and every handler see the real verb. A unit test that
builds a `PUT` gets a `PUT`.

The override is narrow:

- It applies to a form-encoded `POST` and nothing else. A `POST` with a JSON body never has its
  verb rewritten by a field or a query parameter.
- It never downgrades to a safe method. Turning a `POST` into a `GET` would lose the body and
  make the request repeatable, and `HEAD` and `OPTIONS` are refused with it because the CSRF
  check skips safe methods and a forged submission naming one would reach a handler unchecked.
- A name eezo doesn't recognise is left alone.

## No attachment bag

Many frameworks give the request a `Map[String, Any]` for middleware to drop things in and
handlers to fish them out. eezo doesn't. A handler that needs something asks for it in its
signature, as a `using` parameter, and the generated route table closes over it. If it isn't
there, the compile fails at the row that needs it, instead of the first request failing with
`sys.error`.

## Two facts eezo writes before you run

**`secure`** is whether the browser used HTTPS: the connection says so, or a proxy that
terminated TLS says so with `X-Forwarded-Proto`. That header is trusted for this one question
and nothing else. A client that forges it only puts `Secure` on its own cookies, and can't take
it off anyone else's, since a TLS connection counts whatever the header says. The answer also
picks the scheme of the origin the live socket compares against, and the same reasoning
holds: a browser can't add headers to a WebSocket upgrade, and a client that can isn't a
browser and carries no victim's cookie.

**`currentUser`** is who the guard says is behind the request, as the key the session spells.
It's written by the guard's wrapper on a guarded page, and by the route table's `identify` on
a socket upgrade, in both cases before the code that reads it. It is never taken from a
header, a query parameter, a path parameter or a frame. Those are things a client chooses, and
who is signed in is not.

## The response

```scala
final case class Response(
    status: Int,
    headers: Seq[(String, Url | String)],
    body: Body,
    session: Option[Session] = None
)
```

**The status is an `Int`.** `Response.Ok(page)` and `Response.Redirect(url)` are the two named
constructors, because those are the two responses with structure beyond the code: a page and
its content type, a `Location`. Everything else goes through `Response.status(418)`. A
framework that can't say 418 is one people work around.

**Headers are an ordered sequence that allows repeats.** Writing wants both, and `Set-Cookie`
is the header that needs both. The request's headers are a map, because reading wants lookup.
A header value can be a `Url`, which is how a `Location` of `Url.Mounted` travels with a mount.

**The body has three cases.** `Body.Html` is a page from the DSL, rendered once on the way out.
`Body.Bytes` is anything already encoded. `Body.Empty` is a redirect's. A fourth, streaming,
is reserved and absent, because nothing has needed it yet and the renderer already writes into
a builder, so it can arrive without reshaping anything.

**The session is carried as a value.** A handler reads `request.session` and, when it wants a
change kept, returns `response.withSession(amended)`. Signing it needs the secret, which the
handler doesn't have, so the cookie is written once, after dispatch, and only when the session
differs from the one that arrived. A page that only reads the session costs no `Set-Cookie`.

## Why there is no `Response.NotFound`

A 404 is reached by throwing `NotFound(request.path)`, and the request boundary turns it into
a full problem page with the status, a title and the detail. A `Response.NotFound` constructor
would sit one letter from that exception, return an empty body, and skip the page. If you want
a bare 404 with no page, `Response.status(404)` says so plainly. [Failures](failures.md) covers
the thrown set and what each boundary does with it.

## A handler on a virtual thread

Every request runs on its own virtual thread. Blocking in a handler is fine: a query, an
outbound HTTP call, a `Thread.sleep`. The thread parks and the carrier moves on. This is why
the API is direct style with no `Future` anywhere, and it's why eezo needs JDK 25. Before JEP
491, a virtual thread that blocked inside a `synchronized` block pinned its carrier, and JDBC
drivers and connection pools are full of `synchronized`. JDK 24 removed the pinning and JDK 25
is the first long-term release that carries the fix.

## Where to go next

The session the request carries and the token every form returns are
[sessions and CSRF](sessions-and-csrf.md). What the boundary does with a thrown refusal is
[failures](failures.md).
