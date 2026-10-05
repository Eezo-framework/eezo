# The live layer

State on the server, a thin browser, one socket. This page is about what travels over that
socket, how a tree is diffed, what happens when the connection goes, and what the layer
doesn't do. [A live page](../tutorials/a-live-page.md) builds one; this is the
model behind it.

## Three functions, one thread

A component is a state type and three functions over it:

```scala
trait Component[S] {
  def init(ctx: Init[S]): S
  def handle(event: Event, state: S): S
  def render(state: S): Html
}
```

`init` runs once, at mount, and is the only place a subscription to a `Topic` can be made:
subscribing from `render` would add one per re-render, so `subscribe` throws after `init`
returns. `handle` takes an event from the browser and moves the state. `render` is a pure
function of the state. The state is whatever immutable value you choose, and it lives on the
server. The browser only ever holds its rendering.

Each page gets its own virtual thread and a mailbox. Everything that can move state or send a
patch goes through the mailbox and is applied by that one thread, so `handle` needs no
synchronisation, and "read the last tree, handle, render, diff, send, store" is atomic by
construction. Two kinds of message have two different disciplines:

- **A client event blocks its caller** until the state has moved and the patches are on the
  wire. The socket listener's contract is "finish the work before you return", so a browser
  spamming clicks is back-pressured through TCP and nothing is queued unboundedly.
- **A topic delivery never blocks the publisher.** It enqueues and returns. A full mailbox
  drops the message and counts it, and the page answers with one full resync, so the DOM stays
  honest about a state that genuinely missed messages.

The loop drains whatever is pending and renders once per batch, so a chatty topic costs one
diff per drain.

An event still running after ten seconds is logged, once, and the socket keeps waiting. A
`handle` waiting on a row lock is slow, not wrong, and cutting it off would leave the state and
the browser guessing.

## The differ

The contract is one sentence: applying the patches to a DOM that holds the old tree produces
the DOM that rendering the new tree would. Everything else follows from wanting that to be
true.

A patch names its target by a path of child indices from the mount anchor. That only works if
the tree the server diffs is exactly the DOM the browser parsed, which is why the differ refuses
trees it can't trust instead of repairing them:

- A `<div>` inside a `<p>`: the parser closes the `<p>` early and hoists the `<div>` out, so
  every sibling index after it is fiction. Refused at mount, naming the parser's move.
- Two adjacent text nodes, an empty text node, a fragment as a child, a void element with
  children: the DSL's own guard prevents these, so one arriving was built around it.
- A `<tr>` outside `<tbody>`, text directly in a `<table>`: foster-parented in front of the
  table. Refused.
- Children with keys on some and not others, or two with the same key. Refused.

The recursion prefers the finest patch that's still correct. A text edit over a subtree
replace, an attribute set over either, because replacing a node destroys focus, caret, scroll
and playback state the user can see. Attributes are compared as maps, since order has no DOM
meaning. It falls back to something coarser at two seams: a changed tag name is a node
replacement, and a child list holding raw markup is replaced whole, because one `Html.raw`
child can parse into any number of DOM nodes and no index past it can be trusted.

Children are diffed by position unless every child has a key. With keys, a reorder becomes
moves instead of rewrites, and only the keys off a longest stable subsequence move, so a
prepend costs one insert and the rows below keep their state.

`Live.ignore` on an element fences off its children for a subtree some other script owns, a
chart or an editor. eezo still patches that element's attributes and never touches below it.

## On the wire

A frame is JSON, `{"kind":"patches","patches":[...]}`, and each patch carries its path, the
node name it expects to find there, and markup for anything it inserts. The expectation is the
integrity check: the client refuses a patch whose target disagrees, never applies it, reports
it, and the server logs it loud and answers with a full resync. If you ever see `refused
patch` in a console, that's the alarm, and it's worth a bug report.

The client sends a `join` when it's ready, which carries the mount prefix its DOM was rendered
with, so a mounted page's later renders are rebased to match. Then events, pings, and failure
reports. A malformed frame is answered with an error frame and a warning, and the page loop
survives it. Every inbound frame is untrusted input, capped and validated before it becomes an
`Event`.

## The registry

Every mount mints a 128-bit id from `SecureRandom` and registers the page. A page accepts
exactly one socket at a time. The registry is capped at ten thousand pages, because every
mount runs `init`, usually a query, with no requirement that a socket ever connect, and that's
an amplification vector if it's uncapped. Past the cap, the component is rendered once,
statically, the anchor says so in an attribute, and the page works without updating. A busy
site degrades to pages that don't update, never to errors.

A reaper runs every ten seconds. A page nobody ever connected to is dropped after thirty
seconds. A page whose socket dropped without a close frame is kept for sixty, the grace window.

## When the connection goes

- **A network blip inside the grace window** rejoins the same page: state intact, one full
  tree resync.
- **Past the window**, the page was reaped, the server answers the reconnect with 4404, and the
  client reloads itself into a fresh page.
- **The server restarts**: every page is gone with the process, and the client retries, capped
  at about three seconds, then reloads. The anchor carries `data-eezo-state="lost"` meanwhile,
  for styling.
- **A duplicated tab** carries the original's id, is refused with 4409 (one socket per page),
  and reloads into its own page.
- **The tab closes** cleanly, with code 1000, and the page is freed at once.

Server state is per process, and when the process goes the state is honestly gone. There's no
persistence of page state and no attempt at one.

## Origins

A browser names the page that opened a socket in its `Origin` header, and the socket admits
only its own origin: the scheme the browser used plus the `Host` it asked for, compared whole.
`https://localhost:8080` is not `http://localhost:8080`. An upgrade with no `Origin` isn't a
browser and is let through, because origin checking exists against a browser lending its
cookies to a socket another site opened, and a client that isn't a browser carries nobody's
cookie.

A proxy that rewrites `Host`, or terminates TLS without sending `X-Forwarded-Proto`, makes the
server compute an origin the browser never used, and every page is refused with 4403 and a
warning showing both origins. Fix the proxy first. When the page really is served from another
origin, list it in `allowedOrigins`, as whole origins, matched exactly. An entry that isn't an
origin fails the boot.

## Bound pages

A page mounted behind a guarded route remembers who it was rendered for, and the socket's
upgrade has to name the same user, or it's closed with 4403. Checked once, when the socket
opens. [Guards and ownership](guards-and-ownership.md) has the rest.

## State that moves without a click

A `Topic[A]` is a value you share: a field on an object, a lookup per entity. Anything may
publish; only `init` may subscribe. Publishing hands the message to each subscriber's mailbox
and returns, so a publisher holding a database connection mid-transaction never renders a page
with it. The discipline that keeps two browsers honest is that `handle` publishes and leaves
the shared state alone; the publisher's own page updates through its subscription, the same
path as everyone else's.

`Async[S]` is for work that takes time. A component mounted with `Live.mount(request, async =>
new Weather(async))` can run a block on its own thread and post a transition back through the
mailbox when it's done. The page responds now and the result patches in later. The HTTP
client's `get` and `post` are pre-composed with it, and the reaction is a total match over the
four ways a call can end, so forgetting the expired-token case is a compile error.

## What isn't there

- **Multi-mount.** One component per response. A second `Live.mount` renders, but the client
  drives only the first anchor it finds.
- **Client hooks.** No lifecycle callbacks in the browser, no way to run your JavaScript when a
  patch lands. `Live.ignore` is the whole escape hatch.
- **A JavaScript API.** The client script is the framework's and has no public surface.
- **Cross-process fan-out.** A `Topic` is in-process. Two machines don't share one.

## Where to go next

What happens when `handle` throws, and what the socket answers, is in [failures](failures.md).
