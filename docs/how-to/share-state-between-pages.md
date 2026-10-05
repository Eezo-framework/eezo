# Share state between live pages

Make two browsers converge on one value with a `Topic`, and keep `handle` honest while they do.

## The problem

Each live page has its own state and its own thread. Two tabs on a counter are two counters.
When pages need to agree, a guestbook where everyone sees every entry, the state has to move
between them, and the thing that moves it is a `Topic`.

## 1. A topic

A topic is a value shared by every page in the process. Put it in the component's companion:

```scala
object Guestbook {
  final case class Entry(id: Long, name: String, message: String)
  val signed: Topic[Entry] = new Topic[Entry]
}
```

## 2. Subscribe in `init`

```scala
final class Guestbook extends Component[GuestbookState] {

  def init(ctx: Init[GuestbookState]): GuestbookState = {
    ctx.subscribe(Guestbook.signed)((entry, state) => state.copy(entries = state.entries :+ entry))
    GuestbookState.empty
  }
  // ...
}
```

The second argument is a transition: a message in, the state at delivery time in, the next state
out. It runs on the page's thread like any event. `init` is the only place `subscribe` works;
calling it anywhere else throws, by name.

## 3. Publish in `handle`, and leave the shared state alone

```scala
def handle(event: Event, state: GuestbookState): GuestbookState = event.name match {
  case "sign" =>
    Guestbook.signed.publish(Guestbook.Entry(nextId(), state.name, state.message))
    state.copy(name = "", message = "")
  case _ => state
}
```

The signer's own page doesn't append the entry here. It gets it through its subscription, the
same path as every other page, so every open page converges on the same list in the same order.
Appending locally as well would show the entry twice on the page that sent it.

`publish` never blocks and never renders on the publisher's thread.

## 4. Keyed rows

A list that other pages reorder wants keys, so a reorder moves nodes instead of rewriting them:

```scala
ul(state.entries.map(e => li(key(e.id.toString), strong(e.name), ": ", e.message)))
```

## Under load

A chatty topic is coalesced per page: several messages that arrive while a page is busy become
few frames. A subscription is cancelled when its page closes, so a topic never holds a dead page.

## Try it

`cd examples && sbt "blog/run dev"`, open http://localhost:8080/live in two browsers, and sign the
guestbook in one.
