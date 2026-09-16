package io.eezo.http

import java.time.Duration
import java.util.concurrent.TimeUnit

import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session

/** A live WebSocket connection.
  *
  * An opaque wrapper over Jetty's `Session`, so `modules/live` never sees Jetty. The outgoing
  * surface is the regime `research/http-server.md` §5.2 measured and chose: block until the frame
  * is on the wire, but give up on a stalled client. Blocking is real backpressure (a virtual thread
  * parks rather than pins on the `join`), and the timeout is what keeps one dead client from
  * parking a page loop forever — `send` throwing is the caller's cue to close the connection and
  * let the page's grace window take over.
  */
opaque type WsConn = Session

object WsConn {

  private[http] def apply(session: Session): WsConn = session

  extension (conn: WsConn) {

    def isOpen: Boolean = conn.isOpen

    def close(code: Int, reason: String): Unit = conn.close(code, reason, Callback.NOOP)

    /** Blocks until the frame is on the wire, or throws: a `TimeoutException` (wrapped in
      * `CompletionException`) for a client that stopped reading, whatever Jetty failed with for a
      * connection that is gone. Never silently drops.
      */
    def send(text: String, within: Duration = Duration.ofSeconds(10)): Unit = {
      val _ = Callback.Completable
        .`with`(callback => conn.sendText(text, callback))
        .orTimeout(within.toMillis, TimeUnit.MILLISECONDS)
        .join()
    }
  }
}

/** What an application does with a WebSocket connection.
  *
  * The runtime calls Jetty's `demand()`, never user code: a listener that forgets to demand is a
  * socket that silently stops, and that failure should be unrepresentable. The contract that
  * creates is **finish the work before you return** — a listener that hands the message to another
  * thread and returns at once receives the next message immediately, discarding the read
  * backpressure Jetty was chosen for.
  *
  * There is no `onBinary`. Jetty's binary event hands over a pooled `ByteBuffer` with a `Callback`
  * that releases it, so exposing it means either copying every payload or documenting a use after
  * free. Text arrives as a `String` and has no such problem.
  */
trait WsListener {

  def onOpen(conn: WsConn): Unit = ()

  def onText(conn: WsConn, text: String): Unit = ()

  def onClose(code: Int, reason: String): Unit = ()

  def onError(cause: Throwable): Unit = ()
}
