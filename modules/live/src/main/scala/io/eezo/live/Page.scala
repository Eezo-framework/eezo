package io.eezo.live

import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, CompletionException}
import java.util.concurrent.atomic.AtomicLong

import io.eezo.core.html.Html

/** One live page: a component instance, its state, the last tree the client holds, and the one
  * thread allowed to touch any of them.
  *
  * **One writer per page** (design/live.md §2.4) is this class. Everything that can move state or
  * send a patch goes through the mailbox and is applied by the page's own virtual thread, so *read
  * lastTree → handle → render → diff → send → store* is atomic as a whole by construction: no diff
  * is ever computed against a tree the client does not hold, and state needs no atomics because
  * exactly one thread reads or writes it after mount.
  *
  * The mailbox carries two kinds of work, with two deliberately different disciplines:
  *
  *   - **Client events block their caller** ([[event]]): the WebSocket listener's contract is
  *     "finish the work before you return", so a spamming client is back-pressured through TCP by
  *     Jetty's own demand loop. Nothing is queued unboundedly and nothing is dropped.
  *   - **Topic deliveries never block their publisher** ([[post]]): they enqueue and return. A full
  *     mailbox drops the delivery and records it; the loop answers with one full resync, so the DOM
  *     stays honest about the state — which has genuinely missed those messages, because a
  *     subscriber slower than its publisher misses messages or grows without bound, and eezo
  *     chooses the honest loss (design/live.md §1.1 on §4.5).
  *
  * Coalescing falls out of the queue: the loop drains whatever is pending and renders **once** per
  * batch, so a chatty topic costs one diff per drain, not one per message.
  *
  * The mutable fields below are the thin shell the design allows: each is written by one thread
  * (`state`/`lastTree` by the page thread; the mount-time fields before it starts), and every
  * computation over them ([[applied]], the render/diff step) is a function returning its result.
  */
private[live] final class Page[S](
    val id: String,
    component: Component[S],
    send: List[Patch] => Unit,
    mailboxCapacity: Int = Page.DefaultMailboxCapacity
) {

  private enum Msg {
    case FromClient(event: Event, done: CompletableFuture[Void])
    case FromTopic(transition: S => S)
    case Stop
  }

  private val mailbox = new ArrayBlockingQueue[Msg](mailboxCapacity)
  private val dropped = new AtomicLong(0)
  private val log     = System.getLogger("io.eezo.live")

  // Written once by mount before the worker starts (Thread.start is the happens-before edge),
  // then owned by the worker.
  private var state: S                                    = scala.compiletime.uninitialized
  private var lastTree: Html.Element                      = scala.compiletime.uninitialized
  @volatile private var subscriptions: List[Subscription] = Nil
  @volatile private var worker: Thread | Null             = null
  @volatile private var closed                            = false

  /** Runs `init`, renders and validates the first tree, starts the page thread, and returns the
    * tree for the mount to embed. A failing `init` or a non-canonical first render propagates to
    * the caller — the HTTP request that is mounting — after cancelling whatever subscriptions
    * `init` already made.
    */
  def mount(): Html.Element = {
    val ctx = new Init[S](post)
    try {
      state = component.init(ctx)
      subscriptions = ctx.seal()
      lastTree = Canonical.root(component.render(state))
    } catch {
      case e: Throwable =>
        ctx.seal().foreach(_.cancel())
        throw e
    }
    worker = Thread.ofVirtual().name(s"eezo-live-$id").start(() => loop())
    lastTree
  }

  /** One client event, processed to completion: when this returns, the state has moved, the patches
    * are handed to `send`, and the baseline is stored. Blocking is the point — see the class
    * comment. Throws what `handle` (or the render after it) threw.
    */
  def event(event: Event): Unit = {
    if (closed) throw new IllegalStateException(s"page $id closed")
    val done = new CompletableFuture[Void]()
    mailbox.put(Msg.FromClient(event, done))
    try {
      val _ = done.join()
    } catch {
      case e: CompletionException if e.getCause != null => throw e.getCause
    }
  }

  /** A topic delivery: enqueue and return, never work on the publisher's thread. On a full mailbox
    * the message is dropped and counted; the loop resyncs (class comment).
    */
  private def post(transition: S => S): Unit =
    if (!mailbox.offer(Msg.FromTopic(transition))) {
      val _ = dropped.incrementAndGet()
    }

  /** Stops the page: cancels its subscriptions, stops the thread, and fails whatever callers were
    * still waiting. Idempotent, callable from any thread — the registry reaps through this.
    */
  def close(): Unit = {
    closed = true
    subscriptions.foreach(_.cancel())
    val _ = mailbox.offer(Msg.Stop)
    worker match {
      case t: Thread => t.interrupt()
      case null      => ()
    }
  }

  /** What sits in the mailbox right now. For tests that need to observe the drain, not logic. */
  private[live] def pendingForTests: Int = mailbox.size()

  private def loop(): Unit = {
    try {
      var running = true
      while (running) {
        val first = mailbox.take()
        val rest  = new java.util.ArrayList[Msg]
        val _     = mailbox.drainTo(rest)
        val batch = first :: scala.jdk.CollectionConverters.ListHasAsScala(rest).asScala.toList

        val work = batch.filter {
          case Msg.Stop => running = false; false
          case _        => true
        }
        if (work.nonEmpty) step(work)

        val lost = dropped.getAndSet(0)
        if (lost > 0) resync(lost)
      }
    } catch {
      case _: InterruptedException => ()
    } finally {
      subscriptions.foreach(_.cancel())
      drainPendingAsClosed()
    }
  }

  /** One batch: fold the state through every message, render once, diff against the baseline, send,
    * store, and only then release the blocked callers — "the work is finished" includes the patches
    * being on their way.
    */
  private def step(work: List[Msg]): Unit = {
    val (nextState, waiters) = applied(work)

    val outcome: Option[Throwable] =
      try {
        val tree    = Canonical.root(component.render(nextState))
        val patches = Differ.diff(lastTree, tree)
        if (patches.nonEmpty) send(patches)
        state = nextState
        lastTree = tree
        None
      } catch {
        case e: Exception =>
          // The render or the diff refused. The client's DOM still matches lastTree, so the
          // baseline stays; the state does not advance either, so the two cannot drift apart.
          // The callers get the error, which in M3 is the socket's cue to close loudly.
          log.log(System.Logger.Level.ERROR, s"page $id failed to re-render", e)
          Some(e)
      }

    waiters.foreach { case (done, failure) =>
      failure.orElse(outcome) match {
        case Some(error) => val _ = done.completeExceptionally(error)
        case None        => val _ = done.complete(null)
      }
    }
  }

  /** The state folded through the batch, with each blocked caller's individual outcome: an event
    * whose `handle` throws fails alone and moves nothing; the rest of the batch still applies. A
    * topic transition that throws is logged and skipped — nobody is waiting on it.
    */
  private def applied(work: List[Msg]): (S, List[(CompletableFuture[Void], Option[Throwable])]) =
    work.foldLeft((state, List.empty[(CompletableFuture[Void], Option[Throwable])])) {
      case ((s, acc), Msg.FromClient(event, done)) =>
        try (component.handle(event, s), (done, None) :: acc)
        catch { case e: Exception => (s, (done, Some(e)) :: acc) }
      case ((s, acc), Msg.FromTopic(transition)) =>
        try (transition(s), acc)
        catch {
          case e: Exception =>
            log.log(System.Logger.Level.WARNING, s"page $id dropped a failing topic transition", e)
            (s, acc)
        }
      case ((s, acc), Msg.Stop) => (s, acc)
    } match {
      case (s, acc) => (s, acc.reverse)
    }

  /** The mailbox overflowed: `lost` messages never reached the state. One full resync makes the DOM
    * agree with the state the page actually has, and the log says what was sacrificed.
    */
  private def resync(lost: Long): Unit = {
    log.log(
      System.Logger.Level.WARNING,
      s"page $id mailbox overflowed; $lost topic messages were dropped, resyncing the client"
    )
    send(List(Patch.SetChildren(Nil, None, Vector(lastTree))))
  }

  /** Whoever was still queued when the page closed must not wait forever. */
  private def drainPendingAsClosed(): Unit = {
    val rest = new java.util.ArrayList[Msg]
    val _    = mailbox.drainTo(rest)
    scala.jdk.CollectionConverters.ListHasAsScala(rest).asScala.foreach {
      case Msg.FromClient(_, done) =>
        val _ = done.completeExceptionally(new IllegalStateException(s"page $id closed"))
      case _ => ()
    }
  }
}

private[live] object Page {

  /** Room for a burst without room for a runaway: past this, topic deliveries drop-to-resync. */
  val DefaultMailboxCapacity: Int = 256
}
