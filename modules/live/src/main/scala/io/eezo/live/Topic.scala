package io.eezo.live

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An in-process publish/subscribe topic, typed by its message.
  *
  * A `Topic[A]` is a value the application creates and shares — a field on the app object, a
  * per-entity lookup, whatever names it for both sides. Anything may [[publish]]: a handler, a
  * component's `handle`, `boot`'s background work. Only [[Component.init]] may subscribe, through
  * [[Init]], which is what keeps the subscription set from growing by one per re-render.
  *
  * Delivery is a hand-off, never work: `publish` runs each subscriber's callback on the publisher's
  * thread, and that callback — built by [[Init.subscribe]] — only enqueues into the page's mailbox.
  * The re-render happens on the page's own thread (design/live.md §2.4), so a publisher holding a
  * scarce resource (a database connection, mid-transaction) never renders pages with it.
  *
  * Cross-node fan-out is a later implementation behind this same surface (design/live.md §2.8);
  * nothing here names a transport.
  */
final class Topic[A] {

  private val ids         = new AtomicLong(0)
  private val subscribers = new ConcurrentHashMap[Long, A => Unit]

  /** Delivers to every current subscriber. Fire and forget: a page that has since closed has
    * already cancelled and is simply absent.
    */
  def publish(message: A): Unit =
    subscribers.values.forEach(deliver => deliver(message))

  /** How many pages are listening. For tests and metrics, not for logic. */
  def subscriberCount: Int = subscribers.size

  /** Registration is [[Init]]'s alone; the page cancels at close through the handle. */
  private[live] def subscribe(deliver: A => Unit): Subscription = {
    val id = ids.incrementAndGet()
    val _  = subscribers.put(id, deliver)
    Subscription(() => { val _ = subscribers.remove(id) })
  }
}

/** One registration, cancellable once; cancelling twice is a no-op. */
private[live] final class Subscription(cancelOnce: () => Unit) {
  def cancel(): Unit = cancelOnce()
}

private[live] object Subscription {
  def apply(cancelOnce: () => Unit): Subscription = new Subscription(cancelOnce)
}
