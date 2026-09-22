package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.http.client.{Auth, Reply}
import io.eezo.live.{Async, Component, Event, Init, Live}

/** The components-that-call-out demo (design/live.md M6): an outbound HTTP call from `handle`,
  * its four outcomes each driving their own state, and the page never freezing while the call
  * runs.
  *
  * The component is built with its [[Async]] through `Live.mount(request, async => new Callout(async))`.
  * A click starts the call on its own virtual thread and returns the loading state at once; the
  * reaction is a total match over `Reply`, so forgetting the `Denied` arm is a compile error,
  * not a production surprise. The token toggle is the whole auth demo: break it and the same
  * click lands in the `Denied` branch, live.
  */
final case class CalloutState(token: String, outcome: String, loading: Boolean)

final class Callout(async: Async[CalloutState]) extends Component[CalloutState] {

  def init(ctx: Init[CalloutState]): CalloutState =
    CalloutState(token = "let-me-in", outcome = "nothing fetched yet", loading = false)

  def handle(event: Event, s: CalloutState): CalloutState = event.name match {
    case "toggle-token" =>
      s.copy(token = if (s.token == "let-me-in") "wrong-token" else "let-me-in")

    case "fetch" =>
      async.get("http://127.0.0.1:8080/api", Auth.bearer(s.token)) {
        case Reply.Ok(r)            => done(s"ok: ${r.text}")
        case Reply.Denied(r)        => done(s"denied (${r.status}): the token is bad — fix it and retry")
        case Reply.Failed(r)        => done(s"failed (${r.status}): the upstream answered, badly")
        case Reply.Unreachable(why) => done(s"unreachable: $why")
      }
      s.copy(loading = true)

    case "fetch-missing" =>
      async.get("http://127.0.0.1:8080/api/no-such-thing") {
        case Reply.Ok(r)            => done(s"ok: ${r.text}")
        case Reply.Denied(r)        => done(s"denied (${r.status})")
        case Reply.Failed(r)        => done(s"failed (${r.status}): the upstream answered, badly")
        case Reply.Unreachable(why) => done(s"unreachable: $why")
      }
      s.copy(loading = true)

    case _ => s
  }

  private def done(outcome: String): CalloutState => CalloutState =
    _.copy(outcome = outcome, loading = false)

  def render(s: CalloutState): Html =
    div(
      Attrs.style := "font-family: system-ui; max-width: 32rem; margin: 3rem auto",
      h1("calling out"),
      p("token: ", code(s.token), " ", button(Live.onClick("toggle-token"), "toggle")),
      p(
        button(Live.onClick("fetch"), "fetch /api"),
        " ",
        button(Live.onClick("fetch-missing"), "fetch a missing route")
      ),
      p(if (s.loading) em("calling…") else strong(s.outcome)),
      p(
        Attrs.style := "color: gray",
        "The call happens on the server: this page's handle runs in the JVM, and async.get " +
          "fetches /api over the loopback (127.0.0.1, matching Jetty's IPv4 bind - `localhost` " +
          "can resolve to IPv6 ::1 and time out). The browser's network tab shows only the " +
          "WebSocket frames: the event going up, the outcome patching down."
      )
    )
}
