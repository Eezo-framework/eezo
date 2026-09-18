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

/** Work declared now, applied later: the capability a component that calls out is built with.
  *
  * `async { work }` runs `work` on its own virtual thread and posts the transition it returns
  * through the page's mailbox — the topic-delivery path, so it is serialized with everything else,
  * coalesced under load, and dropped without noise if the page has closed by the time it lands.
  * `work` is ordinary blocking direct-style code: an outbound call through
  * `io.eezo.http.client.Http`, a `transact` block, both.
  *
  * The capability arrives by constructor, through `Live.mount(async => new Weather(async))`, so
  * `Component`'s three methods stay exactly as they are; `Init` extends this, so `init` can start a
  * load and return a loading state — the page responds instantly and the data patches in. `render`
  * must not call it: render runs per event, and work started there is work started per keystroke.
  *
  * [[get]] and [[post]] are [[apply]] pre-composed with the HTTP client, shaped so the reaction is
  * a **total** function over `Reply`'s four arms: the compiler itself asks what happens when
  * credentials die (design/live.md M6).
  */
class Async[S] private[live] (post: (S => S) => Unit) {

  private val log = System.getLogger("io.eezo.live")

  /** Runs `work` off the page thread; its returned transition applies like a topic message. A
    * `work` that throws is logged and moves nothing — nobody is blocked on it, so the log line is
    * the whole story.
    */
  def apply(work: => S => S): Unit = {
    val _ = Thread
      .ofVirtual()
      .name("eezo-live-async")
      .start(() =>
        try post(work)
        catch {
          case e: Exception =>
            log.log(System.Logger.Level.ERROR, "async work failed; the state is unchanged", e)
        }
      )
  }

  def get(url: String, headers: (String, String)*)(
      react: io.eezo.http.client.Reply => S => S
  ): Unit =
    apply(react(io.eezo.http.client.Http.get(url, headers*)))

  def post(
      url: String,
      body: String,
      contentType: String = "application/json",
      headers: (String, String)*
  )(
      react: io.eezo.http.client.Reply => S => S
  ): Unit =
    apply(react(io.eezo.http.client.Http.post(url, body, contentType, headers*)))
}

/** The mount-time context: what [[Component.init]] may do beyond computing state.
  *
  * `subscribe` registers a transition to run when a topic delivers: the message and the state at
  * delivery time in, the next state out, applied on the page's own thread like any event. The
  * window closes when `init` returns — the page seals the context — so the phase rule
  * "subscriptions happen in init" is a thrown exception rather than a convention.
  */
final class Init[S] private[live] (post: (S => S) => Unit) extends Async[S](post) {

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
