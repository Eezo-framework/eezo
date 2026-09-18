package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.live.{Component, Event, Init, Live, Topic}

/** Every live feature on one page (design/live.md M7): a per-page counter, a validated live
  * form, a shared keyed list over pub/sub, and a sort toggle that moves rows instead of
  * re-rendering them.
  *
  * Two kinds of state on one screen, on purpose. The click counter is this page's own: two
  * browsers count independently, because each mount is its own component instance. The entries
  * are shared: signing publishes to the topic, every subscribed page — the signer's included —
  * appends through its own subscription, so both browsers converge on one list. The sort toggle
  * is per-page again: the same shared rows, ordered differently in each browser, and reordering
  * emits `moveChild` patches so the row nodes keep their identity.
  */
final case class GuestbookState(
    name: String,
    message: String,
    alphabetical: Boolean,
    clicks: Int,
    entries: Vector[Guestbook.Entry]
)

object Guestbook {

  final case class Entry(id: Long, name: String, message: String)

  /** The shared thing: one topic, every open guestbook subscribed. */
  val signed: Topic[Entry] = new Topic[Entry]
}

final class Guestbook extends Component[GuestbookState] {

  def init(ctx: Init[GuestbookState]): GuestbookState = {
    ctx.subscribe(Guestbook.signed) { (entry, s) =>
      s.copy(entries = (s.entries :+ entry).takeRight(30))
    }
    GuestbookState("", "", alphabetical = false, clicks = 0, entries = Vector.empty)
  }

  def handle(event: Event, s: GuestbookState): GuestbookState = {
    def value = event.payload.getOrElse("value", "")
    event.name match {
      case "click"           => s.copy(clicks = s.clicks + 1)
      case "name-changed"    => s.copy(name = value)
      case "message-changed" => s.copy(message = value)
      case "toggle-sort"     => s.copy(alphabetical = !s.alphabetical)
      case "sign" =>
        val name    = event.payload.getOrElse("name", "").trim
        val message = event.payload.getOrElse("message", "").trim
        if (name.nonEmpty && message.nonEmpty)
          Guestbook.signed.publish(Guestbook.Entry(System.nanoTime(), name, message))
        // The signer's own list updates through the subscription, like everyone else's.
        s.copy(name = "", message = "")
      case _ => s
    }
  }

  private def problem(s: GuestbookState): Option[String] =
    if (s.name.trim.isEmpty) Some("a name is required")
    else if (s.message.trim.isEmpty) Some("say something")
    else None

  def render(s: GuestbookState): Html = {
    val ordered =
      if (s.alphabetical) s.entries.sortBy(entry => (entry.name, entry.id))
      else s.entries.reverse

    div(
      Attrs.style := "font-family: system-ui; max-width: 34rem; margin: 3rem auto",
      h1("guestbook"),
      p(
        "your clicks here: ",
        strong(s.clicks),
        " ",
        button(Live.onClick("click"), "+1"),
        " — this half is per page; open a second browser and the counts diverge"
      ),
      form(
        Live.onSubmit("sign"),
        p(label("name ", input(
          Attrs.tpe   := "text",
          Attrs.name  := "name",
          Attrs.value := s.name,
          Live.onInput("name-changed")
        ))),
        p(label("message ", input(
          Attrs.tpe   := "text",
          Attrs.name  := "message",
          Attrs.value := s.message,
          Live.onInput("message-changed")
        ))),
        p(
          button(Attrs.tpe := "submit", Attrs.disabled := problem(s).isDefined, "sign"),
          " ",
          problem(s).map(em(_)).getOrElse(em("ready — this half is shared; both browsers get it"))
        )
      ),
      h2(
        "entries ",
        button(
          Live.onClick("toggle-sort"),
          if (s.alphabetical) "sort by time" else "sort by name"
        )
      ),
      ul(ordered.map { entry =>
        li(Key(entry.id.toString), strong(entry.name), ": ", entry.message)
      })
    )
  }
}
