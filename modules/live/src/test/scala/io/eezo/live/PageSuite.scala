package io.eezo.live

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}

import io.eezo.core.html.{Html, Key}
import io.eezo.core.html.Tags.*

/** The one-writer contract, held under fire: simultaneous client events and topic publishes against
  * one page, and both the final state and the final DOM come out right. The DOM assertion is the
  * sharp one — every emitted frame, applied in emission order from the initial tree, must land
  * exactly on the final render, which fails if any diff was ever computed against a baseline the
  * client did not hold.
  */
class PageSuite extends munit.FunSuite {

  /** A board: clicks counted, notes appended. Clicks arrive as client events, notes over a topic,
    * so the two mutation paths of design/live.md §2.4 both run.
    */
  private final case class Board(clicks: Int, notes: List[String])

  private final class BoardComponent(topic: Topic[String]) extends Component[Board] {
    def init(ctx: Init[Board]): Board = {
      ctx.subscribe(topic)((note, board) => board.copy(notes = note :: board.notes))
      Board(0, Nil)
    }

    def handle(event: Event, board: Board): Board = event.name match {
      case "click" => board.copy(clicks = board.clicks + 1)
      case "boom"  => throw new IllegalArgumentException("component said no")
    }

    def render(board: Board): Html =
      div(
        span(board.clicks),
        ul(board.notes.sorted.map(note => li(Key(note), note)))
      )
  }

  /** Frames are recorded in emission order; the sink runs on the page thread only. */
  private def collector(): (ConcurrentLinkedQueue[List[Patch]], List[Patch] => Unit) = {
    val frames = new ConcurrentLinkedQueue[List[Patch]]()
    (frames, patches => { val _ = frames.add(patches) })
  }

  private def replayed(
      initial: Html.Element,
      frames: ConcurrentLinkedQueue[List[Patch]]
  ): Vector[Html] = {
    import scala.jdk.CollectionConverters.*
    frames.asScala.foldLeft(Vector[Html](initial))(RefApplier.apply)
  }

  test("simultaneous events and publishes: final state, final DOM, no lost update") {
    val topic          = new Topic[String]
    val component      = new BoardComponent(topic)
    val (frames, send) = collector()
    val page           = new Page("p1", component, send)
    val initial        = page.mount()

    val clickers   = 8
    val perClicker = 25
    val notes      = (1 to 100).map(i => f"n$i%03d").toList
    val start      = new CountDownLatch(1)

    val clickThreads = (1 to clickers).map { _ =>
      Thread
        .ofVirtual()
        .start(() => {
          start.await()
          (1 to perClicker).foreach(_ => page.event(Event("click")))
        })
    }
    val publishThreads = notes
      .grouped(25)
      .map { chunk =>
        Thread
          .ofVirtual()
          .start(() => {
            start.await()
            chunk.foreach(topic.publish)
          })
      }
      .toList

    start.countDown()
    (clickThreads ++ publishThreads).foreach(_.join())

    // The queue is FIFO: one final blocking event fences everything enqueued before it.
    page.event(Event("click"))

    val expected = Board(clickers * perClicker + 1, notes.reverse)
    val rendered = Canonical.root(component.render(expected))

    // No lost update: the naive `@volatile var; s = f(s)` interleaving would show up here.
    assert(DomEqual.all(replayed(initial, frames), Vector(rendered)))
    page.close()
  }

  test("a chatty topic coalesces: many messages, few frames") {
    val topic          = new Topic[String]
    val gate           = new CountDownLatch(1)
    val (frames, send) = collector()

    // handle("wait") parks the page thread, so the burst piles into the mailbox and the next
    // batch drains it whole.
    val component = new Component[List[String]] {
      def init(ctx: Init[List[String]]): List[String] = {
        ctx.subscribe(topic)((note, notes) => note :: notes)
        Nil
      }
      def handle(event: Event, notes: List[String]): List[String] = {
        if (event.name == "wait") gate.await()
        notes
      }
      def render(notes: List[String]): Html = div(span(notes.length))
    }

    val page = new Page("p2", component, send)
    val _    = page.mount()

    val burst   = 200
    val blocker = Thread.ofVirtual().start(() => page.event(Event("wait")))
    (1 to burst).foreach(i => topic.publish(s"m$i"))
    gate.countDown()
    blocker.join()
    page.event(Event("fence"))

    import scala.jdk.CollectionConverters.*
    val frameCount = frames.asScala.size
    assert(frameCount < burst / 2, s"$frameCount frames for $burst messages is not coalescing")

    val expected = Canonical.root(div(span(burst)))
    assert(DomEqual.all(replayed(Canonical.root(div(span(0))), frames), Vector(expected)))
    page.close()
  }

  test("a mailbox overflow drops, logs, and resyncs to an honest DOM") {
    val topic          = new Topic[String]
    val gate           = new CountDownLatch(1)
    val (frames, send) = collector()

    val component = new Component[Int] {
      def init(ctx: Init[Int]): Int = {
        ctx.subscribe(topic)((_, n) => n + 1)
        0
      }
      def handle(event: Event, n: Int): Int = {
        if (event.name == "wait") gate.await()
        n
      }
      def render(n: Int): Html = div(span(n))
    }

    val page = new Page("p3", component, send, mailboxCapacity = 8)
    val _    = page.mount()

    val blocker = Thread.ofVirtual().start(() => page.event(Event("wait")))
    // Wait until the page thread has taken "wait" off the queue, or the burst races it.
    while (page.pendingForTests > 0) Thread.onSpinWait()
    (1 to 1000).foreach(i => topic.publish(s"m$i"))
    gate.countDown()
    blocker.join()
    page.event(Event("fence"))

    import scala.jdk.CollectionConverters.*
    val all = frames.asScala.toList
    assert(
      all.exists(_.exists {
        case Patch.SetChildren(Nil, None, _) => true
        case _                               => false
      }),
      "an overflow must produce a full resync frame"
    )
    // The DOM ends on the state the page really has - fewer than 1000, because drops happened,
    // but exactly what the frames say.
    val replay = replayed(Canonical.root(div(span(0))), frames)
    val landed = replay.map(_.render).mkString
    assert(landed.matches("<div><span>\\d+</span></div>"), landed)
    page.close()
  }

  test("subscribe outside init throws, by name") {
    val topic               = new Topic[String]
    var smuggled: Init[Int] = null

    val component = new Component[Int] {
      def init(ctx: Init[Int]): Int         = { smuggled = ctx; 0 }
      def handle(event: Event, n: Int): Int = {
        smuggled.subscribe(topic)((_, s) => s)
        n
      }
      def render(n: Int): Html = div(span(n))
    }

    val page = new Page("p4", component, _ => ())
    val _    = page.mount()
    val e    = intercept[IllegalStateException](page.event(Event("go")))
    assert(e.getMessage.contains("subscribe outside init"), e.getMessage)
    page.close()
  }

  test("an event whose handle throws fails alone; the page and the batch survive") {
    val (frames, send) = collector()
    val topic          = new Topic[String]
    val page           = new Page("p5", new BoardComponent(topic), send)
    val _              = page.mount()

    val boom = intercept[IllegalArgumentException](page.event(Event("boom")))
    assertEquals(boom.getMessage, "component said no")

    page.event(Event("click"))
    val expected = Canonical.root(new BoardComponent(topic).render(Board(1, Nil)))
    assert(
      DomEqual.all(
        replayed(Canonical.root(new BoardComponent(topic).render(Board(0, Nil))), frames),
        Vector(expected)
      )
    )
    page.close()
  }

  test("close cancels the subscription and later publishes reach nobody") {
    val topic = new Topic[String]
    val page  = new Page("p6", new BoardComponent(topic), _ => ())
    val _     = page.mount()
    assertEquals(topic.subscriberCount, 1)

    page.close()
    assertEquals(topic.subscriberCount, 0)
    topic.publish("into the void")
    intercept[IllegalStateException](page.event(Event("click")))
  }

  test("a non-canonical render fails the mount and cancels what init subscribed") {
    val topic     = new Topic[String]
    val component = new Component[Int] {
      def init(ctx: Init[Int]): Int = {
        ctx.subscribe(topic)((_, n) => n)
        0
      }
      def handle(event: Event, n: Int): Int = n
      def render(n: Int): Html              = span("a") ++ span("b") // a fragment, not one root
    }

    val page = new Page("p7", component, _ => ())
    intercept[NotCanonical](page.mount())
    assertEquals(topic.subscriberCount, 0)
  }

  test("a rebase moves the baseline and every later patch under the prefix, exactly once") {
    import io.eezo.core.html.{Attrs, Url}

    final class Linked extends Component[Int] {
      def init(ctx: Init[Int]): Int         = 0
      def handle(event: Event, n: Int): Int = n + 1
      def render(n: Int): Html              =
        div(a(Attrs.href := Url.Mounted(s"/posts/$n"), "posts"), span(n))
    }

    val (frames, send) = collector()
    val page           = new Page("p8", new Linked, send)
    val initial        = page.mount()
    assert(initial.render.contains("href=\"/posts/0\""))

    page.rebase("/admin")
    page.resync()
    page.event(Event("fence"))

    // The fence may batch with the resync, and the loop sends changes before resyncs, so the
    // resync is found wherever it landed rather than assumed first.
    import scala.jdk.CollectionConverters.*
    val resync = frames.asScala.toList.flatten.collectFirst {
      case Patch.SetChildren(Nil, None, children) => children.map(_.render).mkString
    }
    assert(resync.exists(_.contains("href=\"/admin/posts/")), resync)
    assert(resync.exists(!_.contains("/admin/admin")), resync)

    // The fence's own patch (the +1 above) carries the moved url too.
    val attrValues = frames.asScala.toList.flatten.collect { case Patch.SetAttr(_, _, "href", v) =>
      v
    }
    assert(attrValues.contains("/admin/posts/1"), attrValues)

    // A rejoin reporting the same prefix must not prefix the prefix.
    page.rebase("/admin")
    page.resync()
    page.event(Event("fence"))
    val all = frames.asScala.toList.flatten.map {
      case Patch.SetChildren(_, _, children) => children.map(_.render).mkString
      case other                             => other.toString
    }
    assert(!all.exists(_.contains("/admin/admin")), all)
    page.close()
  }
}
