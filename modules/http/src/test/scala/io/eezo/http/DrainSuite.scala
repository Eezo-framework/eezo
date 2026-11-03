package io.eezo.http

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            // The finally of `inFlight` always releases; the bound is for a test that never got
            // that far, so a stuck handler ends on its own rather than holding a thread for good.
            // It is the request's own bound, since a handler outliving the request that waits on
            // it holds a thread for nobody.
            release.await(requestTimeout.toMillis, TimeUnit.MILLISECONDS)
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
      body: (HttpServer, Int, java.util.concurrent.CompletableFuture[HttpResponse[String]]) => Unit
  ): Unit =
    serving(held.routes) { (server, port) =>
      try {
        val answer = client.sendAsync(
          request(port, "/held").GET().build(),
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
      val stopper = stopping(server.jetty)
      // Held past the silence that closes idle connections during the drain, which is not a race
      // but the point: a handler that is working rather than talking must not be cut by it.
      // Half as long again, and still well inside the stop timeout, so the silence is what is
      // tested rather than the timeout.
      Thread.sleep(
        HttpServer.shutdownIdleTimeout(dev = false).multipliedBy(3).dividedBy(2).toMillis
      )
      held.release.countDown()
      val response = answer.get(5, TimeUnit.SECONDS)
      assertEquals(response.statusCode(), 200)
      assertEquals(response.body(), plainly("finished"))
      stopper.join(5000)
      assert(!stopper.isAlive, "stop did not return once the in flight request finished")
    }
  }

  /** How long the late request waits on a far end that says nothing, to connect and then to be
    * answered. Both are shorter than [[HttpServer.StopTimeout]], so a request that is going to be
    * judged is judged while the drain is still open, and neither is so short that a loaded runner
    * turns a refusal, which is immediate, into a timeout.
    */
  private val lateTimeout = Duration.ofSeconds(1)

  /** Sends a request that arrives once stop has begun, and asserts the server refuses it while the
    * drain is still open. Returns the port when the request timed out instead, and `None` once the
    * server has refused it.
    *
    * A timeout is not a refusal. The server refuses by closing its listening socket, so a
    * connection that is neither refused nor answered did not reach the server at all, the case
    * `reachable` describes: the port is shared with a process that holds it and says nothing.
    * Reading that as a refusal would pass a test that proved nothing, and waiting on it was the
    * hang this bound replaced, so the caller tries again on a fresh server instead.
    */
  private def lateRequest(): Option[Int] = {
    val held     = new Held
    var timedOut = Option.empty[Int]
    inFlight(held) { (server, port, _) =>
      val stopper = stopping(server.jetty)
      // A client with no connection to reuse, so the late request needs a new one, which is the
      // case a platform health check or a browser hitting a stopping machine is in.
      val late = java.net.http.HttpClient.newBuilder().connectTimeout(lateTimeout).build()
      try {
        val answered = late.send(
          request(port, HttpServer.HealthPath, lateTimeout).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        fail(s"the late request was answered ${answered.statusCode()} instead of refused")
      } catch {
        case _: java.net.http.HttpTimeoutException => timedOut = Some(port)
        case _: java.net.ConnectException          =>
          assert(stopper.isAlive, "the drain closed before the late request was refused")
      } finally late.close()
    }
    timedOut
  }

  test("a request that arrives once stop has begun is refused while the drain is still open") {
    def refused(attempts: Int, timedOutOn: List[Int]): Unit =
      lateRequest() match {
        case None                       => ()
        case Some(port) if attempts > 1 => refused(attempts - 1, port :: timedOutOn)
        case Some(port)                 =>
          fail(
            s"the late request timed out instead of being refused, on ports " +
              s"${(port :: timedOutOn).reverse.mkString(", ")}: each is one another process " +
              "holds on the loopback"
          )
      }
    refused(attempts = 3, timedOutOn = Nil)
  }

  test("a handler that never finishes holds stop for the stop timeout and no longer") {
    val held = new Held
    inFlight(held) { (server, _, _) =>
      // Jetty stops everything anyway and then reports the drain it cut short by throwing. Jetty's
      // own stop, through the door, because the handle's turns that throw into a log line.
      val took = timed(intercept[java.util.concurrent.TimeoutException](server.jetty.stop()))
      assert(
        clue(took).compareTo(HttpServer.StopTimeout) >= 0,
        "stop returned while the request was still in flight"
      )
      assert(
        clue(took).compareTo(HttpServer.StopTimeout.plusSeconds(1)) < 0,
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
            URI.create(s"ws://$Loopback:$port/live")
          )
          .get(5, TimeUnit.SECONDS)
        assert(opened.await(5, TimeUnit.SECONDS), "the socket never opened on the server")
        val took = timed(server.stop())
        assert(
          clue(took).compareTo(HttpServer.StopTimeout.dividedBy(3)) < 0,
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
      assertEquals(get(port, HttpServer.HealthPath).statusCode(), 200)
      val took = timed(server.stop())
      assert(clue(took).compareTo(Duration.ofMillis(500)) < 0, "the idle connection held stop")
    }
  }

  /** A slow client, whose request is still in flight while the drain's silence window runs, is the
    * one case where the dev and production windows must disagree. Three times the dev window
    * outlasts it and still sits inside the production one, so one pause tells the two apart.
    *
    * A raw socket, since no client lets a test pause halfway through a body.
    */
  private def pausedUpload(dev: Boolean): String = {
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
    var answer = ""
    val bound  = 5000
    serving(routes, dev = dev) { (server, port) =>
      val socket = new java.net.Socket()
      socket.connect(new InetSocketAddress(Loopback, port), bound)
      try {
        socket.setSoTimeout(bound)
        val out = socket.getOutputStream
        out.write(
          ("GET /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\n" +
            "Content-Length: 10\r\n\r\nhello").getBytes(StandardCharsets.US_ASCII)
        )
        out.flush()
        val graceful = Option(server.jetty.getDescendant(classOf[GracefulHandler]))
          .getOrElse(fail("the handler tree has no GracefulHandler"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (graceful.getCurrentRequestCount == 0) {
          if (System.nanoTime() > deadline)
            fail("the server took the upload but never handled it")
          Thread.onSpinWait()
        }
        val stopper = stopping(server.jetty)
        // The pause is the scenario rather than a wait on something.
        Thread.sleep(HttpServer.shutdownIdleTimeout(dev = true).multipliedBy(3).toMillis)
        answer =
          try {
            out.write("world".getBytes(StandardCharsets.US_ASCII))
            out.flush()
            new String(socket.getInputStream.readAllBytes(), StandardCharsets.US_ASCII)
          } catch { case _: java.io.IOException => "" }
        stopper.join(5000)
        assert(!stopper.isAlive, "stop did not return once the upload was over")
      } finally socket.close()
    }
    answer
  }

  test("a client that pauses mid upload during a production drain still gets its answer") {
    // A phone on a slow link pauses between packets as a matter of course, and its request is as
    // much in flight as one whose handler is busy: a deploy owes it the same answer.
    val answer = pausedUpload(dev = false)
    assert(clue(answer).startsWith("HTTP/1.1 200"), "the paused upload was cut by the drain")
    assert(answer.endsWith(plainly("helloworld")), "the answer lost its body")
  }

  test("the same pause during a dev drain is cut, the price of a restart the browser cannot hold") {
    val answer = pausedUpload(dev = true)
    assert(
      !clue(answer).startsWith("HTTP/1.1 200"),
      "the dev window no longer cuts a paused client"
    )
  }
}
