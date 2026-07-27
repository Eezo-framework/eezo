import io.helidon.webserver.WebServer;
import io.helidon.webserver.websocket.WsRouting;
import io.helidon.websocket.*;
import io.helidon.common.buffers.BufferData;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Helidon 4.5 WebSocket probe for eezo #3. Mirrors WsJetty so the two are
 * directly comparable: carrier thread, defaults, out-of-band push, and what the
 * write path does when the peer stops reading.
 */
public class WsHelidon {
  static final CountDownLatch echoed = new CountDownLatch(1);
  static final CountDownLatch opened = new CountDownLatch(1);
  static volatile WsSession pushSession;

  static class Ep implements WsListener {
    public void onOpen(WsSession s) {
      pushSession = s;
      Thread t = Thread.currentThread();
      System.out.println("HELIDON-WS onOpen virtual=" + t.isVirtual() + " thread=" + t);
      opened.countDown();
    }

    public void onMessage(WsSession s, String text, boolean last) {
      Thread t = Thread.currentThread();
      System.out.println("HELIDON-WS onMessage virtual=" + t.isVirtual() + " thread=" + t);
      s.send("echo:" + text, true);
    }

    public void onClose(WsSession s, int status, String reason) {
      System.out.println("HELIDON-WS onClose status=" + status + " reason=" + reason
          + " virtual=" + Thread.currentThread().isVirtual());
    }

    public void onError(WsSession s, Throwable t) {
      System.out.println("HELIDON-WS onError " + t + " virtual=" + Thread.currentThread().isVirtual());
    }
  }

  static WebServer make(int port) {
    return WebServer.builder().port(port)
        .addRouting(WsRouting.builder().endpoint("/ws", new Ep()))
        .build();
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    long t0 = System.nanoTime();
    WebServer s = make(port);
    s.start();
    System.out.printf("HELIDON(with-ws) cold-start-ms=%.1f%n", (System.nanoTime() - t0) / 1e6);

    System.out.println("HELIDON-WS defaults: WsConfig.maxFrameLength="
        + io.helidon.webserver.websocket.WsConfig.create().maxFrameLength());

    // --- 1. echo ---
    var cl = java.net.http.HttpClient.newHttpClient();
    var ws = cl.newWebSocketBuilder().buildAsync(java.net.URI.create("ws://localhost:" + port + "/ws"),
        new java.net.http.WebSocket.Listener() {
          public CompletionStage<?> onText(java.net.http.WebSocket w, CharSequence d, boolean last) {
            System.out.println("CLIENT got: " + d);
            echoed.countDown();
            return null;
          }
        }).join();
    ws.sendText("hello", true);
    echoed.await(5, TimeUnit.SECONDS);

    // --- 2. out-of-band push ---
    Thread pusher = Thread.ofVirtual().start(() -> {
      long p0 = System.nanoTime();
      pushSession.send("push-from-outside", true);
      System.out.printf("HELIDON-WS out-of-band push returned in %.3f ms on thread=%s virtual=%b%n",
          (System.nanoTime() - p0) / 1e6, Thread.currentThread(), Thread.currentThread().isVirtual());
    });
    pusher.join();
    ws.sendClose(1000, "bye").join();

    // --- 3. backpressure ---
    System.out.println("--- HELIDON backpressure ---");
    SlowClient sc = new SlowClient("localhost", port, "/ws");
    if (!opened.await(5, TimeUnit.SECONDS)) System.out.println("no open");
    Thread.sleep(200);
    WsSession sess = pushSession;
    String payload = "x".repeat(60_000);
    AtomicLong sent = new AtomicLong();
    AtomicReference<String> err = new AtomicReference<>();
    AtomicLong maxCallNs = new AtomicLong();
    Thread w = Thread.ofVirtual().start(() -> {
      try {
        for (int i = 0; i < 5000; i++) {
          long c0 = System.nanoTime();
          sess.send(payload, true);
          long d = System.nanoTime() - c0;
          maxCallNs.accumulateAndGet(d, Math::max);
          sent.incrementAndGet();
        }
      } catch (Throwable t) {
        err.set(t.toString());
      }
    });
    w.join(15_000);
    boolean stillRunning = w.isAlive();
    System.out.printf("HELIDON-WS bp: sent=%d exception=%s writerStillBlocked=%b%n",
        sent.get(), err.get(), stillRunning);
    System.out.printf("HELIDON-WS bp: max send() call duration = %.3f ms (blocking? %b)%n",
        maxCallNs.get() / 1e6, maxCallNs.get() > 50_000_000L);
    // Does a second concurrent writer see an exception or interleave frames?
    AtomicReference<String> err2 = new AtomicReference<>("no-exception");
    Thread w2 = Thread.ofVirtual().start(() -> {
      try {
        sess.send("concurrent-writer", true);
        err2.set("returned-normally");
      } catch (Throwable t) {
        err2.set(t.toString());
      }
    });
    w2.join(3000);
    System.out.println("HELIDON-WS bp: concurrent second writer -> " + err2.get()
        + " (stillBlocked=" + w2.isAlive() + ")");
    sc.close();
    w.interrupt();
    Thread.sleep(300);

    // --- 4. restart ---
    s.stop();
    long r0 = System.nanoTime();
    s.start();
    long r1 = System.nanoTime();
    System.out.printf("HELIDON(with-ws) in-process-restart-ms=%.1f%n", (r1 - r0) / 1e6);
    s.stop();
    System.exit(0);
  }
}
