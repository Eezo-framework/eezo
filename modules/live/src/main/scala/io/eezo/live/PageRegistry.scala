package io.eezo.live

import java.security.SecureRandom
import java.time.Duration

/** Every live page the process holds, by id, with the lifecycle design/live.md §2.5 lays out.
  *
  * The id is the capability to the page until an auth layer exists: 128 bits from `SecureRandom`,
  * minted here and nowhere else, and a page accepts exactly one connection at a time. The registry
  * is capped because every mount runs `init` — usually a query — with no requirement that a socket
  * ever connect, which is a cheap amplification vector left uncapped.
  *
  * Reaping is pull, not a timer of its own: the caller (M3's live endpoint; a test) invokes
  * [[reap]] on its own cadence with whatever clock it injected, which is what makes the grace
  * arithmetic testable without sleeping.
  *
  * Plain `synchronized` over a map, deliberately: every operation here happens once per page
  * lifetime (mount, connect, drop, close) — nothing is per-event — and a lock that is trivially
  * correct beats a lock-free design nobody benchmarked.
  */
private[live] final class PageRegistry(
    cap: Int = PageRegistry.DefaultCap,
    neverConnectedTtl: Duration = PageRegistry.DefaultNeverConnectedTtl,
    disconnectedGrace: Duration = PageRegistry.DefaultDisconnectedGrace,
    clock: () => Long = () => System.currentTimeMillis()
) {

  import PageRegistry.{ConnectRefusal, Status}

  private final case class Slot(page: Page[?], status: Status)

  private val slots  = scala.collection.mutable.HashMap.empty[String, Slot]
  private val random = new SecureRandom()

  /** Mints an id and registers the page built for it, or `None` at the cap — the mount's cue to
    * render the page dead rather than fail the response (M3 decides the rendering).
    */
  def register(create: String => Page[?]): Option[Page[?]] = synchronized {
    if (slots.size >= cap) None
    else {
      val id   = newId()
      val page = create(id)
      slots.update(id, Slot(page, Status.NeverConnected(clock())))
      Some(page)
    }
  }

  /** Claims the page for a socket. One connection at a time: a second upgrade on a page that is
    * already connected is refused, not shared — two writers through one mailbox would be legal, but
    * two browsers believing one DOM is not a page, it is a bug kept.
    */
  def connect(id: String): Either[ConnectRefusal, Page[?]] = synchronized {
    slots.get(id) match {
      case None       => Left(ConnectRefusal.Unknown)
      case Some(slot) =>
        slot.status match {
          case Status.Connected => Left(ConnectRefusal.AlreadyConnected)
          case _                =>
            slots.update(id, slot.copy(status = Status.Connected))
            Right(slot.page)
        }
    }
  }

  /** The socket dropped without a close frame: the page holds on for the grace window, so a
    * reconnecting client resyncs instead of losing its state.
    */
  def disconnect(id: String): Unit = synchronized {
    slots.get(id).foreach { slot =>
      if (slot.status == Status.Connected)
        slots.update(id, slot.copy(status = Status.Disconnected(clock())))
    }
  }

  /** A clean close frees immediately. */
  def close(id: String): Unit = {
    val removed = synchronized(slots.remove(id))
    removed.foreach(_.page.close())
  }

  /** Removes what expired: never-connected pages past their TTL, dropped pages past the grace
    * window. Returns the reaped ids, so the caller's log line can say what happened.
    */
  def reap(): List[String] = {
    val now     = clock()
    val expired = synchronized {
      val gone = slots.collect {
        case (id, Slot(_, Status.NeverConnected(since)))
            if now - since >= neverConnectedTtl.toMillis =>
          id
        case (id, Slot(_, Status.Disconnected(since)))
            if now - since >= disconnectedGrace.toMillis =>
          id
      }.toList
      gone.flatMap(id => slots.remove(id).map(id -> _))
    }
    expired.foreach { case (_, slot) => slot.page.close() }
    expired.map { case (id, _) => id }
  }

  def size: Int = synchronized(slots.size)

  private def newId(): String = {
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    bytes.map(b => f"$b%02x").mkString
  }
}

private[live] object PageRegistry {

  val DefaultCap: Int                    = 10000
  val DefaultNeverConnectedTtl: Duration = Duration.ofSeconds(30)
  val DefaultDisconnectedGrace: Duration = Duration.ofSeconds(60)

  enum Status {
    case NeverConnected(since: Long)
    case Connected
    case Disconnected(since: Long)
  }

  enum ConnectRefusal {
    case Unknown, AlreadyConnected
  }
}
