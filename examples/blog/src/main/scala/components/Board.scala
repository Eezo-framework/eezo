package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.live.{Component, Event, Init, Live, Topic}

/** A board every open browser shares: the two-browsers-one-topic demo.
  *
  * The topic is the shared thing, a value on the companion; every mounted board subscribes to it
  * in `init`. A click does not touch the clicking page's own state at all — `handle` publishes
  * and returns the state unchanged, and the update comes back through the subscription like
  * everyone else's, so both browsers repaint from the same message, the clicking one included.
  * That round trip is the whole pub/sub model in one gesture.
  *
  * The self link is a `Url.Mounted`, so the same component mounted at `/board` and under
  * `/admin/board` shows a different, correct address in each place - the mount-prefix seam
  * (design/live.md §2.6), visible.
  */
object Board {

  val posts: Topic[(Long, String)] = new Topic[(Long, String)]
}

final class Board extends Component[List[(Long, String)]] {

  def init(ctx: Init[List[(Long, String)]]): List[(Long, String)] = {
    ctx.subscribe(Board.posts)((post, items) => (items :+ post).takeRight(20))
    Nil
  }

  def handle(event: Event, items: List[(Long, String)]): List[(Long, String)] = {
    val stamp = java.time.LocalTime.now().withNano(0)
    event.name match {
      case "shout" => Board.posts.publish((System.nanoTime(), s"a shout at $stamp"))
      case "wave"  => Board.posts.publish((System.nanoTime(), s"a wave at $stamp"))
      case _       => ()
    }
    items
  }

  def render(items: List[(Long, String)]): Html =
    div(
      Attrs.style := "font-family: system-ui; max-width: 30rem; margin: 3rem auto",
      h1("shared board"),
      p("Open this page in two browsers; act in one, watch the other."),
      p(a(Attrs.href := Url.Mounted("/board"), "this board's own address")),
      div(button(Live.onClick("shout"), "shout"), " ", button(Live.onClick("wave"), "wave")),
      ul(items.map { case (id, text) => li(Key(id.toString), text) })
    )
}
