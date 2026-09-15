package io.eezo.live.harness

import java.util.concurrent.CountDownLatch

import io.eezo.core.html.{Html, Key}
import io.eezo.core.html.Tags.*
import io.eezo.live.*

/** The M2 runtime, watched by eye: one page, client events and topic publishes, the wire frames
  * each produces. What M3 will put on a WebSocket, this prints.
  *
  * Usage: `sbt "live/Test/runMain io.eezo.live.harness.Demo"`
  */
object Demo {

  private final case class Chat(clicks: Int, messages: List[String])

  private def step(title: String)(body: => Unit): Unit = {
    println(s"\n== $title")
    body
  }

  def main(args: Array[String]): Unit = {
    val room = new Topic[String]

    val component = new Component[Chat] {
      def init(ctx: Init[Chat]): Chat = {
        ctx.subscribe(room)((message, chat) => chat.copy(messages = chat.messages :+ message))
        Chat(0, Nil)
      }
      def handle(event: Event, chat: Chat): Chat = event.name match {
        case "click" => chat.copy(clicks = chat.clicks + 1)
        case "wait"  => Demo.gate.await(); chat
        case other   => throw new IllegalArgumentException(s"no handler '$other'")
      }
      def render(chat: Chat): Html =
        div(
          p("clicks: ", span(chat.clicks)),
          ul(chat.messages.map(m => li(Key(m), m)))
        )
    }

    val page = new Page("demo", component, patches => println(Wire.patches(patches)))

    step("mount: init runs, the first render is the HTML the response would embed") {
      println(page.mount().render)
    }

    step("a client event: handle moves state, and the frame is one setText, not a re-render") {
      page.event(Event("click"))
    }

    step("a topic publish: same page thread, same discipline, a keyed <li> appended") {
      room.publish("hello from another thread")
      page.event(Event("click")) // the fence: when this returns, the publish is processed too
    }

    step("a burst of 50 publishes while the page is busy: coalesced into few frames") {
      val busy = Thread.ofVirtual().start(() => page.event(Event("wait")))
      Thread.sleep(50) // let the page thread park inside handle
      (1 to 50).foreach(i => room.publish(f"burst $i%02d"))
      gate.countDown()
      busy.join()
      page.event(Event("click"))
    }

    step("an event whose handle throws fails that caller alone; the page survives") {
      try page.event(Event("no-such-event"))
      catch { case e: IllegalArgumentException => println(s"caught: ${e.getMessage}") }
      page.event(Event("click"))
    }

    step("close: the subscription is gone, the page refuses further events") {
      page.close()
      println(s"subscribers after close: ${room.subscriberCount}")
      try page.event(Event("click"))
      catch { case e: IllegalStateException => println(s"caught: ${e.getMessage}") }
    }
  }

  private val gate = new CountDownLatch(1)
}
