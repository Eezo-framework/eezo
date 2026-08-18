package eezo.http

import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session

/** A live WebSocket connection.
  *
  * An opaque wrapper over Jetty's `Session`, so `modules/live` never sees Jetty. The outgoing
  * surface is deliberately not settled here: `research/http-server.md` section 5.2 lays out three
  * send regimes, and choosing between them means choosing what to do when the outgoing queue is
  * full, which cannot be evaluated without a diff protocol to coalesce.
  */
opaque type WsConn = Session

object WsConn {

  private[http] def apply(session: Session): WsConn = session

  extension (conn: WsConn) {

    def isOpen: Boolean = conn.isOpen

    def close(code: Int, reason: String): Unit = conn.close(code, reason, Callback.NOOP)
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
