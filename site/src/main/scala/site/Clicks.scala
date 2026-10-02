package site

import io.eezo.core.html.*
import io.eezo.live.Event
import io.eezo.live.Live

/** The front page's small proof that the live layer is real: a number that lives on the server.
  *
  * Every click is an event up the socket; the new number comes back as one patch. The count is a
  * slice of [[Page]]'s state and so of this page's alone: a second tab starts from zero, which is
  * the point being made.
  */
object Clicks {

  def handle(event: Event, count: Int): Int = event.name match {
    case "inc"   => count + 1
    case "reset" => 0
    case _       => count
  }

  def render(count: Int): Html =
    div(
      Attrs.cls := "live-demo",
      div(
        Attrs.cls := "live-demo-row",
        span(Attrs.cls := "live-dot", Attrs.attr("aria-hidden") := "true"),
        p(
          Attrs.cls := "live-count",
          strong(count),
          if (count == 1) " click" else " clicks",
          ", held on the server"
        ),
        button(Attrs.cls := "btn btn-small", Attrs.tpe := "button", Live.onClick("inc"), "+1"),
        button(
          Attrs.cls := "btn btn-small btn-ghost",
          Attrs.tpe := "button",
          Live.onClick("reset"),
          "reset"
        )
      ),
      p(
        Attrs.cls := "live-note",
        "This box is a live component: ",
        code("Component[State]"),
        " on the server, events up and patches down over one socket, ",
        "with no JavaScript of ours."
      )
    )
}
