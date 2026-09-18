package io.eezo.live

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

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
    case FromClient(event: Event, done: Promise[Unit])
    case FromTopic(transition: S => S)
    case Rebase(prefix: String)
    case Resync
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

  /** The mount prefix the client's DOM was rewritten with (design/live.md §2.6): "/" until the join
    * reports otherwise. Owned by the loop thread, like the trees it rewrites.
    */
  private var prefix: String = "/"

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
    val done = Promise[Unit]()
    mailbox.put(Msg.FromClient(event, done))
    // `Await` on a virtual thread parks rather than pins, and a `Promise` failure surfaces the
    // original exception directly — no `CompletionException` to unwrap, the reason `CompletableFuture`
    // was not the right tool for an in-house completion signal. The wait is unbounded on purpose:
    // a `handle` that never returns is a page-loop bug, not a caller's timeout to guess at.
    Await.result(done.future, Duration.Inf)
  }

  /** A topic delivery: enqueue and return, never work on the publisher's thread. On a full mailbox
    * the message is dropped and counted; the loop resyncs (class comment).
    */
  private[live] def post(transition: S => S): Unit =
    if (!mailbox.offer(Msg.FromTopic(transition))) {
      val _ = dropped.incrementAndGet()
    }

  /** Tells the page which prefix the client's DOM was mounted under, learned from the join's
    * `data-eezo-base` report. Blocking on a full mailbox rather than dropping, because a lost
    * rebase is wrong links on every later patch, not a missed frame a resync heals; the caller is
    * the socket thread, whose discipline is blocking anyway.
    */
  private[live] def rebase(prefix: String): Unit =
    mailbox.put(Msg.Rebase(prefix))

  /** Asks the loop for one full-tree frame after whatever is already queued: the first thing a
    * connecting or reconnecting socket receives, which heals both the mount-to-join gap (topic
    * messages may have moved the state while no socket was attached to carry the frames) and a
    * rejoin inside the grace window. Enqueue-and-return, like any non-client work; a full mailbox
    * is already heading for a drop-driven resync, so the request is granted either way.
    */
  private[live] def resync(): Unit =
    if (!mailbox.offer(Msg.Resync)) {
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
        // Rebase first: it moves the baseline the step diffs against and the tree the resync
        // sends, and the join enqueued it before anything that could follow it.
        work.foreach {
          case Msg.Rebase(reported) => applyPrefix(reported)
          case _                    => ()
        }
        val (resyncs, changes) =
          work.filterNot(_.isInstanceOf[Msg.Rebase]).partition(_ == Msg.Resync)
        val completions = if (changes.nonEmpty) step(changes) else Nil
        // After the changes, so the joining client gets the tree those changes produced.
        if (resyncs.nonEmpty) sendWholeTree()
        // Blocked callers are released only after the whole batch's frames are out, resyncs
        // included: "the work is finished" means everything enqueued before the event is on the
        // wire when event() returns, which is also what makes tests deterministic.
        completions.foreach { case (done, failure) =>
          failure match {
            case Some(error) => val _ = done.failure(error)
            case None        => val _ = done.success(())
          }
        }

        val lost = dropped.getAndSet(0)
        if (lost > 0) {
          log.log(
            System.Logger.Level.WARNING,
            s"page $id mailbox overflowed; $lost messages were dropped, resyncing the client"
          )
          sendWholeTree()
        }
      }
    } catch {
      case _: InterruptedException => ()
    } finally {
      subscriptions.foreach(_.cancel())
      drainPendingAsClosed()
    }
  }

  /** One batch: fold the state through every message, render once, diff against the baseline, send,
    * store. Returns each blocked caller's outcome for the loop to release once the batch's
    * remaining frames are out too.
    */
  private def step(work: List[Msg]): List[(Promise[Unit], Option[Throwable])] = {
    val (nextState, waiters) = applied(work)

    val outcome: Option[Throwable] =
      try {
        val tree    = rebased(Canonical.root(component.render(nextState)))
        val patches = Differ.diff(lastTree, tree)
        if (patches.nonEmpty) send(patches)
        state = nextState
        lastTree = tree
        None
      } catch {
        case e: Exception =>
          // The render or the diff refused. The client's DOM still matches lastTree, so the
          // baseline stays; the state does not advance either, so the two cannot drift apart.
          // The callers get the error, which is the socket's cue to answer with an error frame.
          log.log(System.Logger.Level.ERROR, s"page $id failed to re-render", e)
          Some(e)
      }

    waiters.map { case (done, failure) => (done, failure.orElse(outcome)) }
  }

  /** The state folded through the batch, with each blocked caller's individual outcome: an event
    * whose `handle` throws fails alone and moves nothing; the rest of the batch still applies. A
    * topic transition that throws is logged and skipped — nobody is waiting on it.
    */
  private def applied(work: List[Msg]): (S, List[(Promise[Unit], Option[Throwable])]) =
    work.foldLeft((state, List.empty[(Promise[Unit], Option[Throwable])])) {
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
      case ((s, acc), Msg.Rebase(_) | Msg.Resync | Msg.Stop) => (s, acc)
    } match {
      case (s, acc) => (s, acc.reverse)
    }

  /** One frame carrying the whole current tree: what a joining socket starts from, and what an
    * overflow falls back to. Always against `lastTree`, so it says exactly what the page knows.
    */
  private def sendWholeTree(): Unit =
    send(List(Patch.SetChildren(Nil, None, Vector(lastTree))))

  /** Adopts the prefix the client reported. Once: the baseline is rewritten a single time, when "/"
    * becomes something else, because `Url.Mounted` survives `under` and a second application would
    * prefix the prefix. A rejoin reporting the same prefix is a no-op, and a *different* prefix on
    * a page that already has one is a client talking nonsense, logged and ignored.
    */
  private def applyPrefix(reported: String): Unit =
    if (reported != prefix) {
      if (prefix == "/") {
        prefix = reported
        lastTree = rebased(lastTree)
      } else
        log.log(
          System.Logger.Level.WARNING,
          s"page $id reported base '$reported' but is mounted under '$prefix'; ignored"
        )
    }

  /** The tree as the client's DOM holds it: every `Url.Mounted` moved under the prefix, exactly the
    * rewrite `Response.under` applied to the initial render on its way out.
    */
  private def rebased(tree: Html.Element): Html.Element =
    if (prefix == "/") tree
    else
      tree.under(prefix) match {
        case el: Html.Element => el
        case other => throw new IllegalStateException(s"under() changed the root: $other")
      }

  /** Whoever was still queued when the page closed must not wait forever. */
  private def drainPendingAsClosed(): Unit = {
    val rest = new java.util.ArrayList[Msg]
    val _    = mailbox.drainTo(rest)
    scala.jdk.CollectionConverters.ListHasAsScala(rest).asScala.foreach {
      case Msg.FromClient(_, done) =>
        val _ = done.failure(new IllegalStateException(s"page $id closed"))
      case _ => ()
    }
  }
}

private[live] object Page {

  /** Room for a burst without room for a runaway: past this, topic deliveries drop-to-resync. */
  val DefaultMailboxCapacity: Int = 256
}
