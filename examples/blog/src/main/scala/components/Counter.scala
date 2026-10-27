package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.live.{Component, Event, Init, Live}

/** A live counter: the smallest thing that proves the whole layer.
  *
  * State is one `Int`, held on the server. `render` is a pure function of it; the two buttons carry
  * click bindings, so a click sends `Event("inc")` / `Event("dec")` to `handle`, which returns the
  * next `Int`, and the differ ships exactly the one `<span>` text that changed. Watch the network
  * panel: no page reload, one tiny frame per click.
  */
final class Counter extends Component[Int] {

  def init(ctx: Init[Int]): Int = 0

  def handle(event: Event, count: Int): Int = event.name match {
    case "inc"   => count + 1
    case "dec"   => count - 1
    case "reset" => 0
    case _       => count
  }

  def render(count: Int): Html =
    div(
      Attrs.cls := "mt-16 text-center",
      h1("eezo live counter"),
      p(Attrs.cls := "my-4 text-6xl font-light tabular-nums", span(count)),
      div(
        Attrs.cls := "flex justify-center gap-2",
        button(Live.onClick("dec"), "−"),
        button(Live.onClick("reset"), "reset"),
        button(Live.onClick("inc"), "+")
      )
    )
}
