package io.eezo.live

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.logging.{Handler, Level, LogRecord, Logger}

import scala.concurrent.duration.DurationInt

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
    val page           = new Page("p1", component)
    page.attach(send)
    val initial = page.mount()

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

    val page = new Page("p2", component)
    page.attach(send)
    val _ = page.mount()

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

    val page = new Page("p3", component, mailboxCapacity = 8)
    page.attach(send)
    val _ = page.mount()

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

  test("frames reach only an attached listener; with none attached they drop without a throw") {
    val topic          = new Topic[String]
    val component      = new BoardComponent(topic)
    val (frames, send) = collector()
    val page           = new Page("p9", component)
    val initial        = page.mount()

    // Nobody listening: the step and the resync still run, and nothing surfaces to the caller.
    page.event(Event("click"))
    page.resync()
    page.event(Event("click"))
    assert(frames.isEmpty)

    // Attached: the resync replays the whole tree, so the dropped frames are owed to nobody.
    page.attach(send)
    page.resync()
    page.event(Event("click"))
    // Two frames whether the loop drains them in one batch or two: the step's patches and the
    // whole tree resync.
    assertEquals(frames.size, 2, "an attached listener must see the resync and the step")

    page.detach()
    page.event(Event("click"))
    page.resync()
    page.event(Event("click"))
    assertEquals(frames.size, 2)

    // What was delivered, replayed over the initial tree, lands on the state as of the detach.
    val atDetach = Canonical.root(component.render(Board(3, Nil)))
    assert(DomEqual.all(replayed(initial, frames), Vector(atDetach)))
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

    val page = new Page("p4", component)
    val _    = page.mount()
    val e    = intercept[IllegalStateException](page.event(Event("go")))
    assert(e.getMessage.contains("subscribe outside init"), e.getMessage)
    page.close()
  }

  test("an event whose handle throws fails alone; the page and the batch survive") {
    val (frames, send) = collector()
    val topic          = new Topic[String]
    val page           = new Page("p5", new BoardComponent(topic))
    page.attach(send)
    val _ = page.mount()

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

  /** Runs `body` with the records the live logger publishes about `pageId` collecting into the
    * queue it is handed, so it can poll them while it runs. The logger is process wide and other
    * suites share it, so a record is kept only when it names this page, the trailing space keeping
    * "p1" from matching "p10".
    */
  private def logsAbout[A](pageId: String)(
      body: ConcurrentLinkedQueue[(Level, String)] => A
  ): A = {
    val logger   = Logger.getLogger("io.eezo.live")
    val captured = new ConcurrentLinkedQueue[(Level, String)]()
    val handler  = new Handler {
      override def publish(record: LogRecord): Unit =
        if (record.getMessage.startsWith(s"page $pageId ")) {
          val _ = captured.add((record.getLevel, record.getMessage))
        }
      override def flush(): Unit = ()
      override def close(): Unit = ()
    }
    logger.addHandler(handler)
    try body(captured)
    finally logger.removeHandler(handler)
  }

  private def messagesAt(
      level: Level,
      records: ConcurrentLinkedQueue[(Level, String)]
  ): List[String] = {
    import scala.jdk.CollectionConverters.*
    records.asScala.toList.collect { case (`level`, message) => message }
  }

  /** Holding the page thread on a latch lets a test keep an event past the injected threshold for
    * as long as it needs, then decide whether that event ends in success or in failure; the instant
    * events show that work inside the threshold stays silent.
    */
  private final class Held(gate: CountDownLatch) extends Component[Int] {
    def init(ctx: Init[Int]): Int         = 0
    def handle(event: Event, n: Int): Int = {
      if (event.name == "slow" || event.name == "doomed") gate.await()
      if (event.name == "doomed") throw new IllegalStateException("doomed")
      n + 1
    }
    def render(n: Int): Html = div(span(n))
  }

  test("an event held past the threshold warns once, still completes, then logs one INFO") {
    val gate    = new CountDownLatch(1)
    val page    = new Page("p10", new Held(gate), slowEventThreshold = 20.millis)
    val _       = page.mount()
    val outcome = new java.util.concurrent.atomic.AtomicReference[Option[Throwable]]()

    logsAbout("p10") { records =>
      val caller = Thread
        .ofVirtual()
        .start(() =>
          outcome.set(
            try { page.event(Event("slow")); None }
            catch { case e: Throwable => Some(e) }
          )
        )
      eventually(messagesAt(Level.WARNING, records).nonEmpty)
      // Ten thresholds more: the warning must not repeat, and the caller must still be waiting.
      Thread.sleep(200)
      assert(caller.isAlive, "the threshold must never cut the event off")
      assertEquals(
        messagesAt(Level.WARNING, records),
        List("page p10 event 'slow' still running after 20ms; the socket waits for it")
      )
      assertEquals(messagesAt(Level.INFO, records), Nil)

      gate.countDown()
      assert(caller.join(java.time.Duration.ofSeconds(5)), "the event never completed")
      assertEquals(outcome.get, None)
      assertEquals(messagesAt(Level.WARNING, records).size, 1)
      val infos = messagesAt(Level.INFO, records)
      assertEquals(infos.size, 1, infos)
      assert(infos.head.matches("page p10 event 'slow' finished after \\d+s"), infos)
    }
    page.close()
  }

  test("an event that warned and then fails logs no finish; its failure is the socket's to log") {
    val gate    = new CountDownLatch(1)
    val page    = new Page("p12", new Held(gate), slowEventThreshold = 20.millis)
    val _       = page.mount()
    val outcome = new java.util.concurrent.atomic.AtomicReference[Option[Throwable]]()

    logsAbout("p12") { records =>
      val caller = Thread
        .ofVirtual()
        .start(() =>
          outcome.set(
            try { page.event(Event("doomed")); None }
            catch { case e: Throwable => Some(e) }
          )
        )
      eventually(messagesAt(Level.WARNING, records).nonEmpty)
      gate.countDown()
      assert(caller.join(java.time.Duration.ofSeconds(5)), "the event never completed")
      outcome.get match {
        case Some(e: IllegalStateException) => assertEquals(e.getMessage, "doomed")
        case other => fail(s"expected the handle's own exception, got $other")
      }
      assertEquals(messagesAt(Level.WARNING, records).size, 1)
      assertEquals(messagesAt(Level.INFO, records), Nil)
    }
    page.close()
  }

  test("an event finishing inside the threshold logs neither a warning nor a finish") {
    // Generous, so a slow runner cannot push a trivial event past it.
    val page = new Page("p11", new Held(new CountDownLatch(0)), slowEventThreshold = 2.seconds)
    val _    = page.mount()
    logsAbout("p11") { records =>
      (1 to 5).foreach(_ => page.event(Event("click")))
      page.event(Event("slow"))
      assertEquals(messagesAt(Level.WARNING, records), Nil)
      assertEquals(messagesAt(Level.INFO, records), Nil)
    }
    page.close()
  }

  test("the default threshold is ten seconds and the warning reads it as 10s") {
    assertEquals(Page.SlowEventThreshold, 10.seconds)
    assertEquals(Page.logText(Page.SlowEventThreshold), "10s")
  }

  test("close cancels the subscription and later publishes reach nobody") {
    val topic = new Topic[String]
    val page  = new Page("p6", new BoardComponent(topic))
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

    val page = new Page("p7", component)
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
    val page           = new Page("p8", new Linked)
    page.attach(send)
    val initial = page.mount()
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

  /** Spins until the emitted frames satisfy `cond`, because async transitions land on their own
    * schedule and the suite has no socket to block on.
    */
  private def eventually(cond: => Boolean): Unit = {
    val deadline = System.currentTimeMillis + 5000
    while (!cond && System.currentTimeMillis < deadline) Thread.sleep(10)
    assert(cond, "condition not met within 5s")
  }

  /** A page wired the way Live.mount wires one: the Async posts into the page it belongs to. */
  private def mountedWithAsync[S](
      create: Async[S] => Component[S],
      send: List[Patch] => Unit
  ): Page[S] = {
    val holder = new java.util.concurrent.atomic.AtomicReference[Page[S]]
    val page   = new Page[S](
      "p-async",
      create(new Async[S](transition => Option(holder.get).foreach(_.post(transition))))
    )
    page.attach(send)
    holder.set(page)
    val _ = page.mount()
    page
  }

  test(
    "async in handle: the loading state ships now, the result lands later, a throw moves nothing"
  ) {
    import java.util.concurrent.CountDownLatch
    val (frames, send) = collector()
    val gate           = new CountDownLatch(1)

    final class Loader(async: Async[String]) extends Component[String] {
      def init(ctx: Init[String]): String         = "idle"
      def handle(event: Event, s: String): String = event.name match {
        case "load" =>
          async {
            gate.await()
            _ => "loaded"
          }
          "loading"
        case "boom" =>
          async(throw new IllegalStateException("work failed"))
          s
        case _ => s
      }
      def render(s: String): Html = div(span(s))
    }

    val page = mountedWithAsync[String](new Loader(_), send)

    def rendered: String =
      replayed(Canonical.root(div(span("idle"))), frames).map(_.render).mkString

    // The loading state is in the event's own frame: event() returned, so it was sent.
    page.event(Event("load"))
    assertEquals(rendered, "<div><span>loading</span></div>")

    gate.countDown()
    eventually(rendered == "<div><span>loaded</span></div>")

    // A throwing work is a log line, not a state change and not a dead page.
    page.event(Event("boom"))
    Thread.sleep(100)
    assertEquals(rendered, "<div><span>loaded</span></div>")
    page.event(Event("fence"))
    page.close()
  }

  test("async in init: fetch-at-mount renders the loading state instantly, then patches in") {
    val (frames, send) = collector()

    final class Eager(async: Async[String]) extends Component[String] {
      def init(ctx: Init[String]): String = {
        async(_ => "ready")
        "waiting"
      }
      def handle(event: Event, s: String): String = s
      def render(s: String): Html                 = div(span(s))
    }

    val page = mountedWithAsync[String](new Eager(_), send)

    eventually(
      replayed(Canonical.root(div(span("waiting"))), frames)
        .map(_.render)
        .mkString == "<div><span>ready</span></div>"
    )
    page.close()
  }

  test("an async result arriving after close is dropped without noise") {
    import java.util.concurrent.CountDownLatch
    val (frames, send) = collector()
    val gate           = new CountDownLatch(1)

    final class Late(async: Async[Int]) extends Component[Int] {
      def init(ctx: Init[Int]): Int         = 0
      def handle(event: Event, n: Int): Int = {
        async { gate.await(); _ + 1 }
        n
      }
      def render(n: Int): Html = div(span(n))
    }

    val page = mountedWithAsync[Int](new Late(_), send)
    page.event(Event("go"))
    page.close()
    val before = frames.size
    gate.countDown()
    Thread.sleep(150)
    assertEquals(frames.size, before)
  }
}
