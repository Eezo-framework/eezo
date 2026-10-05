# Call an external API from a live component

Do slow work off the page thread and patch the outcome in when it lands.

## Why not in `handle`

One thread per page is the whole concurrency story of the live layer, so a `handle` that waits
two seconds on an HTTP call is a page that ignores clicks for two seconds. Slow work goes off
the thread, and its result comes back as a transition.

## 1. Ask for `Async`

A component that calls out is built with the page's `Async`:

```scala
Live.mount(request, async => new Callout(async))
```

```scala
final class Callout(async: Async[Report]) extends Component[Report] {
  // ...
}
```

## 2. Start the call, render now, patch later

```scala
def handle(event: Event, state: Report): Report = event.name match {
  case "fetch" =>
    async.get("https://api.example.com/report", Auth.bearer(state.token)) {
      case Reply.Ok(r)            => _.copy(report = Some(r.text), loading = false)
      case Reply.Denied(_)        => _.copy(error = Some("sign in again"), loading = false)
      case Reply.Failed(r)        => _.copy(error = Some(s"api said ${r.status}"), loading = false)
      case Reply.Unreachable(why) => _.copy(error = Some(why), loading = false)
    }
    state.copy(loading = true)
  case _ => state
}
```

`handle` returns at once with `loading = true`, and that renders now. The call runs on its own
thread on the server, and when it ends, the matching arm's transition is applied to whatever the
state is by then, through the same mailbox a topic uses. The browser only ever sees frames.

The match over `Reply` is total on purpose: `Ok`, `Denied` for a 401 or 403, `Failed` for any
other status, `Unreachable` when no response came. Forgetting the expired-token case is a compile
error.

`async.post(url, body)` works the same way. For anything that isn't HTTP, `async { work }`
runs a block and applies the transition it returns.

## 3. At mount

`Init` extends `Async`, so `init` can start a load and return a loading state:

```scala
def init(ctx: Init[Report]): Report = {
  ctx.get("https://api.example.com/report") { case Reply.Ok(r) => _.copy(report = Some(r.text)); case _ => identity }
  Report.loading
}
```

## It runs on the server

The call is made by your server, not the browser. If you develop over a forwarded port, your
browser's `localhost` and the server's `localhost` are different machines; test the URL from
the server host. Found the hard way with a VS Code port forward that answered the browser and
swallowed the server's request.

## Try it

`examples/blog`, `GET /callout`, demos all four arms with a token you can toggle.
