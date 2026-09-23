package io.eezo.http

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import io.eezo.core.html.Html
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.GracefulHandler
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.StatusCode
import org.eclipse.jetty.websocket.client.WebSocketClient

/** Stopping a server lets the requests it is already answering finish.
  *
  * Whatever wrapped `run` closes the database the moment `stop` returns, so a request still inside
  * a transaction then would lose its connection under it. Every test here coordinates with latches
  * and with the server's own shutdown flag rather than with sleeps, because a drain is a race by
  * nature and a sleep only moves where the race is lost.
  */
class DrainSuite extends munit.FunSuite with ServerFixtures {

  /** A request the test holds inside the drain for as long as it likes: `entered` lets the test
    * know the handler is answering without sleeping on a guess, and `release` makes the drain's
    * length the test's choice rather than the scheduler's.
    */
  private final class Held {
    val entered: CountDownLatch = new CountDownLatch(1)
    val release: CountDownLatch = new CountDownLatch(1)
    val routes: RouteTable      = RouteTable(
      Seq(
        Route.Http(
          Method.GET,
          PathPattern.parse("/held"),
          _ => {
            entered.countDown()
            release.await()
            Response.Ok(Html.text("finished"))
          }
        )
      )
    )
  }

  /** Every test here needs a request already inside a handler when stop begins. The release in
    * `finally` is there because a failed assertion would otherwise leave that handler blocked, and
    * the fixture's own stop would then wait out the whole stop timeout on it.
    */
  private def inFlight(held: Held)(
      body: (Server, Int, java.util.concurrent.CompletableFuture[HttpResponse[String]]) => Unit
  ): Unit =
    serving(held.routes) { (server, port) =>
      try {
        val answer = client.sendAsync(
          HttpRequest.newBuilder(URI.create(s"http://localhost:$port/held")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        assert(held.entered.await(5, TimeUnit.SECONDS), "the held handler never started")
        body(server, port, answer)
      } finally held.release.countDown()
    }

  private def timed(action: => Any): Duration = {
    val began = System.nanoTime()
    val _     = action
    Duration.ofNanos(System.nanoTime() - began)
  }

  /** Begins `server.stop()` on a thread of its own, since it blocks for the whole drain, and
    * returns once the server has started refusing, so what the test does next happens during the
    * drain rather than before it.
    *
    * Refusing has two parts, and the handler's flag is not enough on its own: Jetty shuts the
    * handler down before the connector closes its listening socket, so a connection made between
    * the two is still accepted and answered 503.
    */
  private def stopping(server: Server): Thread = {
    val stopper = new Thread(() => server.stop(), "eezo-drain-stop")
    stopper.setDaemon(true)
    stopper.start()
    val graceful = Option(server.getDescendant(classOf[GracefulHandler]))
      .getOrElse(fail("the handler tree has no GracefulHandler, so stop does not drain"))
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    def refusing = graceful.isShutdown && server.getConnectors.forall {
      case listening: NetworkConnector => !listening.isOpen
      case _                           => true
    }
    while (!refusing) {
      if (System.nanoTime() > deadline) fail("stop never began shutting the server down")
      Thread.onSpinWait()
    }
    stopper
  }

  test("a request in flight when stop begins still answers 200 with its whole body") {
    val held = new Held
    inFlight(held) { (server, _, answer) =>
      val stopper = stopping(server)
      // Held past the silence that closes idle connections during the drain, which is not a race
      // but the point: a handler that is working rather than talking must not be cut by it.
      // Half as long again, and still well inside the stop timeout, so the silence is what is
      // tested rather than the timeout.
      Thread.sleep(Eezo.shutdownIdleTimeout(dev = false).multipliedBy(3).dividedBy(2).toMillis)
      held.release.countDown()
      val response = answer.get(5, TimeUnit.SECONDS)
      assertEquals(response.statusCode(), 200)
      assertEquals(response.body(), "finished")
      stopper.join(5000)
      assert(!stopper.isAlive, "stop did not return once the in flight request finished")
    }
  }

  test("a request that arrives once stop has begun is refused while the drain is still open") {
    val held = new Held
    inFlight(held) { (server, port, _) =>
      val stopper = stopping(server)
      // A client with no connection to reuse, so the late request needs a new one, which is the
      // case a platform health check or a browser hitting a stopping machine is in.
      val late = java.net.http.HttpClient.newHttpClient()
      try
        intercept[java.net.ConnectException](
          late.send(
            HttpRequest.newBuilder(URI.create(s"http://localhost:$port${Eezo.HealthPath}")).build(),
            HttpResponse.BodyHandlers.ofString()
          )
        )
      finally late.close()
      assert(stopper.isAlive, "the drain closed before the late request was refused")
    }
  }

  test("a handler that never finishes holds stop for the stop timeout and no longer") {
    val held = new Held
    inFlight(held) { (server, _, _) =>
      // Jetty stops everything anyway and then reports the drain it cut short by throwing.
      val took = timed(intercept[java.util.concurrent.TimeoutException](server.stop()))
      assert(
        clue(took).compareTo(Eezo.StopTimeout) >= 0,
        "stop returned while the request was still in flight"
      )
      assert(
        clue(took).compareTo(Eezo.StopTimeout.plusSeconds(1)) < 0,
        "stop waited past the stop timeout"
      )
    }
  }

  test("an open WebSocket does not hold the drain, and its client is told the server is going") {
    val opened = new CountDownLatch(1)
    val routes = RouteTable(
      Seq(
        Route.Ws(
          PathPattern.parse("/live"),
          _ =>
            new WsListener {
              override def onOpen(conn: WsConn): Unit = opened.countDown()
            }
        )
      )
    )
    val closes  = new java.util.concurrent.LinkedBlockingQueue[(Int, String)]()
    val sockets = new WebSocketClient()
    sockets.start()
    try
      serving(routes) { (server, port) =>
        val _ = sockets
          .connect(
            new Session.Listener.AbstractAutoDemanding {
              override def onWebSocketClose(status: Int, reason: String): Unit = {
                val _ = closes.offer((status, reason))
              }
            },
            URI.create(s"ws://localhost:$port/live")
          )
          .get(5, TimeUnit.SECONDS)
        assert(opened.await(5, TimeUnit.SECONDS), "the socket never opened on the server")
        val took = timed(server.stop())
        assert(
          clue(took).compareTo(Eezo.StopTimeout.dividedBy(3)) < 0,
          "the open socket held the drain"
        )
        assertEquals(
          Option(closes.poll(5, TimeUnit.SECONDS)).map(_._1),
          Some(StatusCode.SHUTDOWN),
          "the client was not told the server is shutting down"
        )
      }
    finally sockets.stop()
  }

  test("an idle kept alive connection does not hold the stop") {
    // A browser keeps its connection open between requests, so every stop of a server someone has
    // visited has one: the dev server's restart on every save, above all.
    serving(RouteTable.empty, dev = true) { (server, port) =>
      assertEquals(get(port, Eezo.HealthPath).statusCode(), 200)
      val took = timed(server.stop())
      assert(clue(took).compareTo(Duration.ofMillis(500)) < 0, "the idle connection held stop")
    }
  }

  /** Whether `socket` reached `server` or some other process that holds the same port.
    *
    * The server listens on every address, and the kernel will hand it a port another process
    * already holds on the loopback alone. A connection to localhost then goes to that process, the
    * more specific listener, and the server never sees it. Only the server's own connector can say
    * which one answered, by the far end of each connection it holds.
    */
  private def acceptedBy(server: Server, socket: java.net.Socket): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    def accepted = server.getConnectors.exists(
      _.getConnectedEndPoints.asScala.exists(_.getRemoteSocketAddress match {
        case far: InetSocketAddress => far.getPort == socket.getLocalPort
        case _                      => false
      })
    )
    while (!accepted && System.nanoTime() < deadline) Thread.onSpinWait()
    accepted
  }

  /** A slow client, whose request is still in flight while the drain's silence window runs, is the
    * one case where the dev and production windows must disagree. Three times the dev window
    * outlasts it and still sits inside the production one, so one pause tells the two apart.
    *
    * A raw socket, since no client lets a test pause halfway through a body, and so a fresh server
    * when the port turns out to be shared: the socket then never reached the server, and waiting on
    * the server to see its request would wait on nothing.
    */
  private def pausedUpload(dev: Boolean, attempts: Int = 3): String = {
    val routes = RouteTable(
      Seq(
        Route.Http(
          // A GET, so that the pause is the only thing in the way: the CSRF check refuses an
          // unsafe verb without a token before the body matters.
          Method.GET,
          PathPattern.parse("/upload"),
          request => Response.Ok(Html.text(new String(request.body, StandardCharsets.UTF_8)))
        )
      )
    )
    var answer = Option.empty[String]
    serving(routes, dev = dev) { (server, port) =>
      val socket = new java.net.Socket("localhost", port)
      try
        if (acceptedBy(server, socket)) {
          socket.setSoTimeout(5000)
          val out = socket.getOutputStream
          out.write(
            ("GET /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\n" +
              "Content-Length: 10\r\n\r\nhello").getBytes(StandardCharsets.US_ASCII)
          )
          out.flush()
          val graceful = Option(server.getDescendant(classOf[GracefulHandler]))
            .getOrElse(fail("the handler tree has no GracefulHandler"))
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (graceful.getCurrentRequestCount == 0) {
            if (System.nanoTime() > deadline)
              fail("the server took the upload but never handled it")
            Thread.onSpinWait()
          }
          val stopper = stopping(server)
          // The pause is the scenario rather than a wait on something.
          Thread.sleep(Eezo.shutdownIdleTimeout(dev = true).multipliedBy(3).toMillis)
          answer = Some(
            try {
              out.write("world".getBytes(StandardCharsets.US_ASCII))
              out.flush()
              new String(socket.getInputStream.readAllBytes(), StandardCharsets.US_ASCII)
            } catch { case _: java.io.IOException => "" }
          )
          stopper.join(5000)
          assert(!stopper.isAlive, "stop did not return once the upload was over")
        }
      finally socket.close()
    }
    answer.getOrElse {
      if (attempts > 1) pausedUpload(dev, attempts - 1)
      else fail("every port the server was given was one another process holds on the loopback")
    }
  }

  test("a client that pauses mid upload during a production drain still gets its answer") {
    // A phone on a slow link pauses between packets as a matter of course, and its request is as
    // much in flight as one whose handler is busy: a deploy owes it the same answer.
    val answer = pausedUpload(dev = false)
    assert(clue(answer).startsWith("HTTP/1.1 200"), "the paused upload was cut by the drain")
    assert(answer.endsWith("helloworld"), "the answer lost its body")
  }

  test("the same pause during a dev drain is cut, the price of a restart the browser cannot hold") {
    val answer = pausedUpload(dev = true)
    assert(
      !clue(answer).startsWith("HTTP/1.1 200"),
      "the dev window no longer cuts a paused client"
    )
  }
}
