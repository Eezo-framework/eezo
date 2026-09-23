# A live page, from nothing — the eezo live walkthrough

The arc: scaffold an app, write a component, watch it update in the browser with no JavaScript
of yours, share state between two browsers, deploy it. The live layer is Phoenix LiveView's
model in eezo's idiom: state lives on the server, the browser is a thin renderer, and what
travels is events up and DOM patches down over one WebSocket.

What you will *not* write at any point: JavaScript, a WebSocket handler, a diff algorithm, a
reconnect loop, or a serializer.

---

## 0. Prerequisites

- The same setup as `docs/deploying.md` §0: a local eezo publish and the `eezo` alias. If you
  are deploying at the end, the Fly CLI too.
- No database is needed for this walkthrough: a live component's state lives in memory, on the
  server, per page. (Components that *want* the database use `transact`/`read` inside `handle`
  like any handler.)

## 1. Scaffold

```bash
eezo new counter
cd counter
eezo dev
```

The scaffold serves on 8080. Live is already on: every `EezoApp` carries the live socket and
client under the reserved `/eezo` prefix — the boot listing shows `WS /eezo/live/:page` and
`GET /eezo/live.js`. They are the framework's, so `eezo routes` does not list them, the same
way it does not list `/eezo/health`.

## 2. The component

A component is three functions over a state type you choose. Create
`src/main/scala/components/Counter.scala`:

```scala
package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.live.{Component, Event, Init, Live}

final class Counter extends Component[Int] {

  def init(ctx: Init[Int]): Int = 0

  def handle(event: Event, count: Int): Int = event.name match {
    case "inc" => count + 1
    case "dec" => count - 1
    case _     => count
  }

  def render(count: Int): Html =
    div(
      h1("live counter"),
      p(Attrs.style := "font-size: 3rem", span(count)),
      button(Live.onClick("dec"), "−"),
      button(Live.onClick("inc"), "+")
    )
}
```

The rules the compiler and runtime hold you to, and why:

- **`render` is pure**: state in, tree out, exactly one root element. It runs after every
  event, and the differ ships only what changed.
- **`handle` needs no synchronisation**: one thread per page applies events one at a time.
  Your state is any immutable value.
- **`init` is the only place subscriptions happen** (§4 below); subscribing anywhere else
  throws, by name.
- Event bindings are attributes: `Live.onClick("inc")` names the event `handle` receives.
  There are no callbacks in the tree — the tree is data.

## 3. Mount it

A live component is embedded in an ordinary page by an ordinary route. Create
`src/main/scala/app/counter/Index.scala`:

```scala
package app.counter

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.{Request, Response}
import io.eezo.live.Live
import components.Counter

object Index {
  def index(request: Request): Response =
    Response.Ok(
      Html.doctype ++ html(
        head(meta(Attrs.charset := "utf-8"), title("counter")),
        body(Live.mount(request, new Counter))
      )
    )
}
```

You own the whole document — title, stylesheets, chrome. `Live.mount` returns a fragment: the
anchor div holding the first render, and the client script tag. It takes the request you are
answering, because that is what says whether the page is bound (§7); there is no overload without
it. Save; the dev server restarts; open `http://localhost:8080/counter`.

Click. The number moves with no page load. In DevTools → Network → WS → Messages you can read
the whole protocol: `{"kind":"event","name":"inc"...}` up, one `setText` patch down. View
source shows what the server rendered: your document, plus `data-eezo-page` on the anchor and
one `<script src="/eezo/live.js" defer>`.

Open the page in a second tab: independent counters. Each GET mints a page — its own state,
its own socket, its own server-side thread.

## 4. Shared state: the topic

Per-page state diverges; a `Topic[A]` is how pages converge. The blog example's guestbook
(`examples/blog`, `GET /live`) is the complete pattern; the heart of it:

```scala
object Guestbook {
  final case class Entry(id: Long, name: String, message: String)
  val signed: Topic[Entry] = new Topic[Entry]
}

final class Guestbook extends Component[GuestbookState] {

  def init(ctx: Init[GuestbookState]): GuestbookState = {
    ctx.subscribe(Guestbook.signed)((entry, s) => s.copy(entries = s.entries :+ entry))
    GuestbookState.empty
  }

  def handle(event: Event, s: GuestbookState): GuestbookState = event.name match {
    case "sign" =>
      Guestbook.signed.publish(Guestbook.Entry(...))  // publish; do not touch s.entries
      s.copy(name = "", message = "")
  }
}
```

The discipline that keeps two browsers honest: **`handle` publishes and leaves the shared
state alone.** The signer's own page updates through its subscription, the same path as
everyone else's, so every open page converges on the same list. Publishing never blocks and
never renders on the publisher's thread; a chatty topic is coalesced per page into few frames.

Run the example (`cd examples && sbt "blog/run dev"`, open `/live` in two browsers) to see all
of it at once: the per-page counter diverging, the signed entries converging, and the sort
toggle reordering the keyed list with `moveChild` patches — the rows *move*, keeping scroll
and focus, rather than re-rendering.

## 5. Live forms

Three bindings, one per commitment level, all on the guestbook and signup examples:

- `Live.onInput("name-changed")` — per keystroke, debounced on the client (default 300ms,
  tunable per field). The payload is `Map("value" -> ...)`.
- `Live.onChange("subscribed")` — committed changes (a tick, a pick), sent at once.
- `Live.onSubmit("save")` — the form's named fields as one payload, browser submit intercepted.

Echoing a field's value back from the server is safe *while the user is typing in it*: the
applier never overwrites the focused element's live value, so the caret stays put and no
keystroke is eaten. A subtree some other script owns (a chart, an editor) is fenced off with
`Live.ignore` — eezo patches that element's attributes but never its children.

## 6. Calling out

Slow work must not block the page (one thread per page is the whole concurrency story). A
component that calls an HTTP API is mounted with its `Async` capability:

```scala
Live.mount(request, new Callout(_))   // i.e. Live.mount(request, async => new Callout(async))

final class Callout(async: Async[S]) extends Component[S] {
  def handle(event: Event, s: S): S = event.name match {
    case "fetch" =>
      async.get("https://api.example.com/report", Auth.bearer(s.token)) {
        case Reply.Ok(r)            => _.copy(report = Some(r.text), loading = false)
        case Reply.Denied(_)        => _.copy(error = Some("sign in again"), loading = false)
        case Reply.Failed(r)        => _.copy(error = Some(s"api said ${r.status}"), loading = false)
        case Reply.Unreachable(why) => _.copy(error = Some(why), loading = false)
      }
      s.copy(loading = true)        // this renders NOW; the outcome patches in later
  }
}
```

The call runs on its own thread **on the server**; the browser sees only the WebSocket frames.
The reaction is a total match over the four ways an HTTP call ends — `Denied` (401/403) is its
own arm so forgetting the expired-token case is a compile error, not a production surprise.
`examples/blog`'s `/callout` page demos all arms with a toggleable token.

## 7. A page behind a guard: the bound page

A live page rendered behind a guarded route is a **bound page**. It remembers the current user of
the request that rendered it, and admits one socket, whose upgrade must name the same current
user; a sign in that has lapsed, or that was signed out, names nobody, and a browser now signed
in as another account names that account. Neither is the page's user, so the socket is refused
with `4403`. A page rendered on a public route is unbound whoever the
visitor was, and anyone holding its id may join, as before guards existed. That is why `mount`
takes the request: it reads exactly one thing off it, who the guard let through.

The binding is checked when the socket opens and never again while it stays open. A page whose
user signs out, or whose sign in lapses, keeps working for as long as its socket lives; the
refusal arrives at the next upgrade, which in practice is the reconnect after a network blip.
Nothing is pushed at an open socket to close it.

Nothing is asked of you beyond mounting with the request. Guard the route, and the page behind it
is bound; leave it public, and it is not.

## 8. Deploy

Nothing live-specific to configure: `eezo deploy` from `docs/deploying.md` ships it, and the
page works over `wss://` because the client derives the socket scheme from the page's. One
setting matters:

- **`min_machines_running`.** `eezo deploy` writes `auto_stop_machines = 'stop'` and
  `min_machines_running = 0`, which is right for request/response and wrong for sockets: an
  idle-stopped machine closes every live connection, and each waking reconnect is a cold
  start. For a live app, set `min_machines_running = 1` in `fly.toml` — or accept that idle
  pages reconnect-and-reload when traffic returns.

Two devices on one deployed guestbook is the whole acceptance test: sign on the phone, watch
the laptop.

### Origins

A browser names the page that opened a socket in its `Origin` header, and the live socket
admits only its own origin: the scheme the browser used (`https` when the connection is TLS or
`X-Forwarded-Proto` says `https`, `http` otherwise) plus the `Host` it asked for. Scheme and host
compare case insensitively and a default port (80 for `http`, 443 for `https`) is ignored,
but nothing else is lenient: `https://localhost:8080` is not `http://localhost:8080`. An upgrade
with no `Origin` is not a browser and is admitted. With no configuration this is all you need,
and `eezo deploy` on Fly needs nothing either: Fly sends `X-Forwarded-Proto` and passes the
browser's `Host` through.

A proxy of your own in front is where it can go wrong. If it rewrites `Host` to the upstream's
address, or terminates TLS without saying so, the server computes an origin the browser never
used and every live page is refused. Fix the proxy first; in nginx that is

```nginx
proxy_set_header Host $http_host;
proxy_set_header X-Forwarded-Proto $scheme;
```

Use `$http_host`, not the more common `proxy_set_header Host $host`: `$host` drops the port, so
a proxy listening on a port other than 80 or 443 (`localhost:8080` in front of an app on 9000)
would still forward an origin the browser never used.

When the page really is served from another origin, list it:

```scala
object Main extends EezoApp {
  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
  override protected def allowedOrigins: Set[String] = Set("https://app.example")
}
```

Each entry is a whole origin, `scheme://host` or `scheme://host:port`, matched exactly: no
wildcards, no subdomains, no path. An entry that is not an origin fails the boot with a message
naming it. The list widens the server's own origin and never replaces it.

## 9. When things break: designed-in behavior, not bugs

- **Kill the server; the page goes quiet.** The client logs `connection lost, reconnecting…`
  once and retries forever (capped ~3s); the anchor carries `data-eezo-state="lost"` for
  styling. On restart it reloads itself into a fresh page — server state is per-process and
  honestly gone.
- **A dropped connection (network blip) inside 60s** rejoins the *same* page: state intact,
  one full-tree resync. Past the grace window, the page was reaped and the client reloads.
- **A duplicated tab** carries the original's page id; the server refuses the second socket
  (one socket per page) and the copy reloads itself into its own fresh page.
- **A patch the client cannot apply is never applied silently**: it is reported, logged loud
  on both sides, and answered with a full resync. If you ever see `refused patch` in a
  console, that is the correctness alarm — file it.

## Troubleshooting, from the field

- **`unreachable: ...` from a component calling `localhost`, while the URL works in your
  browser.** Remember where the call runs: on the *server*. If you develop over a forwarded
  port (VS Code Remote, `ssh -L`), your browser and the server see different `localhost`es —
  your machine's forward on one side, the server's own loopback on the other. Test the URL
  from the server host, not your laptop. Found the hard way: a VS Code port-forward on a Mac
  answered the browser and swallowed a jshell probe, and the component was innocent all along.
- **The page renders but never connects, console shows `4403`.** The upgrade was refused, and
  the console line carries the server's own reason, which is the only thing that tells the two
  refusals apart. `origin <origin> not allowed` (it read `origin mismatch` in earlier versions)
  means the socket was opened by a page whose origin is neither the server's own nor in
  `allowedOrigins`. The server log has a WARNING with both origins side by side. The client
  serves and connects through the same origin by construction, so the usual culprit is a
  reverse proxy that rewrites `Host` or terminates TLS without `X-Forwarded-Proto`: see
  Origins in §8.
  `not signed in as the page's user` means a bound page (§7) was joined by a socket that is
  somebody else, or nobody: a lapsed or signed out session names nobody, and a browser signed
  in as a different account names that account. Note that eezo never revokes an earlier sign
  in; a session cookie is good until its stamp is older than the guard's lifetime. The cure is
  to sign in again and reload the page yourself: unlike 4404 and 4409 the client does not
  reload itself here, because reloading would only render the same refusal again.
- **`NotCanonical: <div> inside <p>...` at mount.** The differ refuses trees the HTML parser
  would restructure, because a restructured DOM breaks patch addressing silently. The message
  names the parser's move; restructure as it says. Same for mixed keyed/unkeyed children and
  duplicate keys.
- **Console says `page registry at capacity`.** The server holds 10k live pages and a GET past
  the cap serves a static render instead of failing. Usually a load test, not real traffic.
