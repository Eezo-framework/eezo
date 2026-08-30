package io.eezo.db.engine

/** Which kind of scope is open on a thread. */
enum ScopeKind {
  case Read, Write

  def label: String = this match {
    case Read  => "read"
    case Write => "transact"
  }
}

/** A second scope opened while one was already open on this thread. */
final case class ReentrantScope(msg: String) extends IllegalStateException(msg)

/** The reentry check.
  *
  * `summonFrom` rejects nesting that is visible in one method body, and capture checking rejects a
  * scope escaping its block. Neither can see a helper that opens its own scope, because that fact
  * lives in the helper's body and nothing at the call site can read a body. This is what catches
  * it, one layer later, in every build rather than only in capture-checked ones.
  */
private[eezo] object Scope {

  /** A virtual thread's name is empty unless one was given, and "forked ... on ." helps nobody. */
  private def threadName(t: Thread): String =
    if (t.getName.isEmpty) s"an unnamed virtual thread (#${t.threadId})" else t.getName

  private final class Open(val kind: ScopeKind, val owner: Thread)

  private val current = new InheritableThreadLocal[Open | Null]

  def enter[A](kind: ScopeKind)(body: => A): A = {
    val outer = current.get()
    if (outer != null)
      throw (
        if (outer.owner eq Thread.currentThread()) reentrant(outer.kind, kind)
        else forked(outer, kind)
      )
    current.set(new Open(kind, Thread.currentThread()))
    // Restore rather than remove: `detached` nests correctly, and a pooled worker thread can never
    // inherit a stale marker from the request before it — which would turn this check into a source
    // of phantom failures, the one outcome worse than not having it.
    try body
    finally current.set(outer)
  }

  /** Clears the marker for the duration of `body`. The deliberate escape hatch, named so that "this
    * commits whether or not the caller does" is a statement rather than an accident.
    */
  def detached[A](body: => A): A = {
    val outer = current.get()
    current.set(null)
    try body
    finally current.set(outer)
  }

  /** Built only on the failure path: at the moment of the violation the outer scope is still on the
    * call stack, so both sites can be recovered without capturing anything per call.
    */
  private def reentrant(outer: ScopeKind, inner: ScopeKind): ReentrantScope = {
    val frames  = callers()
    val where   = frames.headOption.fold("")(f => s" at $f")
    val outerAt =
      frames.drop(1).headOption.fold("")(f => s"\n  outer scope: ${outer.label}, still open at $f")
    ReentrantScope(
      s"""|a ${outer.label} scope is already open on this thread, and this ${inner.label} would open a second one$where.$outerAt
          |
          |A second scope takes another connection. It cannot see the outer scope's uncommitted
          |rows, and it will block until the connection times out if it touches a row the outer
          |transaction has locked.
          |
          |Fix: let the inner function join the caller's scope instead of opening its own —
          |    def audit(e: String)(using Tx): Unit = ...      // was: = transact { ... }
          |
          |If it must commit independently of the caller, say so:
          |    detached { ... }
          |""".stripMargin
    )
  }

  /** A fork cannot join the scope it was forked from.
    *
    * Rejected even if the parent scope has since closed: whether it is caught would otherwise
    * depend on a race between the fork starting and the parent committing, and a check that fires
    * intermittently is worse than one that is strict.
    */
  private def forked(outer: Open, inner: ScopeKind): ReentrantScope = {
    val where = callers().headOption.fold("")(f => s" at $f")
    ReentrantScope(
      s"""|this ${inner.label} is running on a thread forked inside a ${outer.kind.label} scope on ${threadName(
           outer.owner
         )}$where.
          |
          |A forked task cannot join the scope it was forked from — a JDBC connection is not safe
          |for concurrent use — so this would open a second, independent transaction. Its work is
          |then committed whether or not the outer transaction commits, and it cannot see rows the
          |outer transaction has not committed yet.
          |
          |Fix: do the work before forking, or inside the outer scope —
          |    transact { insert(book); audit(book) }
          |
          |Or, if it really is independent work that should commit on its own:
          |    Thread.ofVirtual().start(() => detached { audit(book) })
          |""".stripMargin
    )
  }

  /** Frames from eezo's own machinery and from the JVM's thread plumbing are noise; the first
    * application frame is what the user needs to see. Filtered on the raw class name, before
    * prettifying, because the entry points live in a synthetic `Transact$package` class.
    */
  private val noise = List(
    "io.eezo.db.Scope",
    "io.eezo.db.Transact",
    "scala.Function",
    "scala.runtime",
    "java.lang.Thread",
    "java.lang.VirtualThread"
  )

  private def callers(): List[String] =
    StackWalker.getInstance.walk { s =>
      s.filter(f => !noise.exists(f.getClassName.startsWith))
        .map(f =>
          pretty(s"${f.getClassName}.${f.getMethodName}(${f.getFileName}:${f.getLineNumber})")
        )
        .limit(4)
        .toArray
        .toList
        .map(_.toString)
    }

  /** `Books$` and `createBad$$anonfun$1` are compiler artefacts; the user wrote `Books.createBad`.
    */
  private def pretty(frame: String): String =
    frame
      .replace("$$anonfun", "")
      .replaceAll("""\$\d+""", "")
      .replace("$.", ".")
      .replace("$package.", ".")
}
