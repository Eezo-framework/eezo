package io.eezo.sbt.dev

/** The quiet window between the last file event and the build it triggers.
  *
  * An editor save is rarely one write: a rename touches every file that names the symbol, a
  * formatter writes a file twice, and `research/build-reload.md` §1.5 measured sbt's own watch
  * firing twice and building twice on such a save. Events are collected here until none has arrived
  * for `quietMillis`, then released as one set. The clock is a parameter so the suite can drive it.
  */
private[sbt] final class Debounce[A](quietMillis: Long, clock: () => Long) {

  private var collected: Set[A] = Set.empty
  private var last: Long        = 0L

  def offer(a: A): Unit = synchronized {
    collected += a
    last = clock()
  }

  /** The collected set when the window has closed, otherwise `None`; empties on release. */
  def due(): Option[Set[A]] = synchronized {
    if (collected.isEmpty || clock() - last < quietMillis) None
    else {
      val out = collected
      collected = Set.empty
      Some(out)
    }
  }

  def pending: Boolean = synchronized(collected.nonEmpty)
}
