# Add a live component

Embed server-held, self-updating state in an ordinary page with no JavaScript of your own.
[A live page, from nothing](../tutorials/a-live-page.md) is the long version.

## 1. The dependency

`EezoApp` already carries the live layer. An application on `eezo-http` alone adds `eezo-live`
and extends `LiveApp` instead of `HttpApp`. Either way you get one socket route and one script
route under `/eezo`.

## 2. The component

Three methods over a state type you choose:

```scala
package components

import io.eezo.core.html.*
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
      p(span(count)),
      button(Live.onClick("dec"), "−"),
      button(Live.onClick("inc"), "+")
    )
}
```

- `init` runs once, at mount. It's the only place a subscription can be made.
- `handle` runs once per event, on the page's own thread, one event at a time. No locking.
  Database work goes here like in any handler: `transact { ... }` or `read { ... }`.
- `render` is a pure function of the state and returns exactly one root element.

## 3. Mount it in a page

```scala
def index(request: Request): Response =
  Response.Ok(
    Html.doctype ++ html(
      head(meta(Attrs.charset := "utf-8"), title("counter")),
      body(Live.mount(request, new Counter))
    )
  )
```

`Live.mount` returns the anchor `div` with the first render inside and the `<script>` tag that
brings it to life. It takes the request because a page behind a guarded route is bound to the
user who rendered it. One mount per response; a second one renders but doesn't connect.

## 4. The bindings

| attribute | sends | payload |
|---|---|---|
| `Live.onClick("name")` | on click | none |
| `Live.onInput("name", debounceMillis = 300)` | per keystroke, debounced | `Map("value" -> ...)` |
| `Live.onChange("name")` | on a committed change (a tick, a pick) | the value, `"on"` or `""` for a checkbox |
| `Live.onSubmit("name")` | on submit, intercepted | every named field in the form |

They are attributes, so they go anywhere an attribute goes. The client delegates one listener at
the document, so a binding survives any patch.

## 5. Things the differ holds you to

- Exactly one root element. A fragment or bare text at the root is refused at mount.
- Canonical trees: no `<div>` inside `<p>`, no text directly inside a `<table>`, no mixed keyed
  and unkeyed children. The error at mount names the rule.
- Keyed lists: give rows `key("...")` so a reorder moves nodes instead of rewriting them.
- A subtree another script owns, a chart or an editor: mark it `Live.ignore` and eezo patches its
  attributes but never its children.

## 6. Watching it work

DevTools, Network, WS: `{"kind":"event","name":"inc"}` up, one patch down. View source shows
your document, the anchor with `data-eezo-page`, and one script tag.

## Next

- Two browsers converging on one value: [share state between live pages](share-state-between-pages.md).
- Slow work off the page thread: [call an external API](call-an-external-api.md).
- Deploying one: the only setting that matters is `min_machines_running = 1`, in [deploy](deploy.md).
