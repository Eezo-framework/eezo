package io.eezo.live

import io.eezo.core.html.Html

/** A live component: server-held state, rendered to HTML, updated over patches.
  *
  * The mental model is a server template that re-renders itself (design/live.md §1): an event
  * arrives from the browser or a [[Topic]], the state moves, [[render]] runs again, and the differ
  * ships the difference. The three methods are the three phases, and what each receives is what
  * that phase may do — the split is enforced by the arguments, not by documentation:
  *
  *   - [[init]] runs once, at mount, and is the **only** place subscriptions exist: it receives the
  *     [[Init]] context, and `subscribe` after `init` returns throws. `render` runs on every event,
  *     so a subscription there would be a duplicate per re-render.
  *   - [[handle]] moves the state. It runs on the page's own thread, one event at a time, so it
  *     needs no synchronisation of its own. Database work happens here the way it does in any
  *     handler: open a scope with `transact { ... }` or `read { ... }` per event — the installed
  *     `Database` reaches every thread, and a connection captured across events is already a loud
  *     error (`EscapedScope`).
  *   - [[render]] is a pure function of the state and receives nothing else. Exactly one root
  *     element, in canonical form; the differ refuses anything else by naming the broken rule.
  *
  * State is any immutable value the component chooses. It lives on the server, owned by one page
  * thread; the browser only ever holds its rendering.
  */
trait Component[S] {

  /** The initial state, computed at mount. Subscriptions to [[Topic]]s are registered here and
    * nowhere else.
    */
  def init(ctx: Init[S]): S

  /** One client event in, the next state out. Runs serialized on the page's thread. */
  def handle(event: Event, state: S): S

  /** The page for this state. Pure: same state, same tree. */
  def render(state: S): Html
}

/** What the browser sent: the handler name from the event binding, and the payload the client
  * collected (an input's value, a form's fields). Both are untrusted input; the wire layer caps and
  * validates before this exists.
  */
final case class Event(name: String, payload: Map[String, String])

object Event {

  /** An event with no payload, which is most clicks. */
  def apply(name: String): Event = Event(name, Map.empty)
}

/** The mount-time context: what [[Component.init]] may do beyond computing state.
  *
  * `subscribe` registers a transition to run when a topic delivers: the message and the state at
  * delivery time in, the next state out, applied on the page's own thread like any event. The
  * window closes when `init` returns — the page seals the context — so the phase rule
  * "subscriptions happen in init" is a thrown exception rather than a convention.
  */
final class Init[S] private[live] (post: (S => S) => Unit) {

  @volatile private var open = true

  private val registered = new java.util.concurrent.CopyOnWriteArrayList[Subscription]

  def subscribe[A](topic: Topic[A])(transition: (A, S) => S): Unit = {
    if (!open)
      throw new IllegalStateException(
        "subscribe outside init: subscriptions are registered exactly once, at mount. " +
          "render runs per event and handle moves state; neither may subscribe."
      )
    val _ = registered.add(topic.subscribe(message => post(state => transition(message, state))))
  }

  /** Closes the window and hands the page what to cancel at close. */
  private[live] def seal(): List[Subscription] = {
    open = false
    scala.jdk.CollectionConverters.ListHasAsScala(registered).asScala.toList
  }
}
