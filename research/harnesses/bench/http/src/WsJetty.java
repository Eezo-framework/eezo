import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Jetty 12.1 WebSocket probe for eezo #3.
 *
 * Establishes: which thread carries onOpen/onText, the default limits, whether a
 * blocking write exists, and what the write path does when the peer stops
 * reading (the backpressure regime eezo's LiveView diff push will live in).
 */
public class WsJetty {
  static final CountDownLatch echoed = new CountDownLatch(1);
  static final CountDownLatch opened = new CountDownLatch(1);
  static volatile Session pushSession;

  public static class Endpoint implements Session.Listener.AutoDemanding {
    Session s;

    public void onWebSocketOpen(Session s) {
      this.s = s;
      pushSession = s;
      Thread t = Thread.currentThread();
      System.out.println("JETTY-WS onOpen virtual=" + t.isVirtual() + " thread=" + t);
      System.out.println("JETTY-WS defaults:"
          + " idleTimeout=" + s.getIdleTimeout()
          + " maxFrameSize=" + s.getMaxFrameSize()
          + " maxTextMessageSize=" + s.getMaxTextMessageSize()
          + " maxBinaryMessageSize=" + s.getMaxBinaryMessageSize()
          + " maxOutgoingFrames=" + s.getMaxOutgoingFrames()
          + " autoFragment=" + s.isAutoFragment()
          + " outputBufferSize=" + s.getOutputBufferSize()
          + " inputBufferSize=" + s.getInputBufferSize());
      opened.countDown();
    }

    public void onWebSocketText(String m) {
      Thread t = Thread.currentThread();
      System.out.println("JETTY-WS onText virtual=" + t.isVirtual() + " thread=" + t);
      s.sendText("echo:" + m, Callback.NOOP);
    }

    public void onWebSocketClose(int code, String reason) {
      System.out.println("JETTY-WS onClose code=" + code + " reason=" + reason
          + " virtual=" + Thread.currentThread().isVirtual());
    }

    public void onWebSocketError(Throwable t) {
      System.out.println("JETTY-WS onError " + t + " virtual=" + Thread.currentThread().isVirtual());
    }
  }

  static Server make(int port) {
    VirtualThreadPool tp = new VirtualThreadPool();
    tp.setMaxConcurrentTasks(0);
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server);
    c.setPort(port);
    server.addConnector(c);
    WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server);
    server.setHandler(ws);
    ws.configure(container -> container.addMapping("/ws", (rq, rs, cb) -> new Endpoint()));
    return server;
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    long t0 = System.nanoTime();
    Server server = make(port);
    server.start();
    System.out.printf("JETTY(with-ws) cold-start-ms=%.1f%n", (System.nanoTime() - t0) / 1e6);

    // --- 1. normal echo, via the JDK client ---
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

    // --- 2. out-of-band push from an unrelated thread ---
    Thread pusher = Thread.ofVirtual().start(() -> {
      CountDownLatch done = new CountDownLatch(1);
      pushSession.sendText("push-from-outside", Callback.from(done::countDown, e -> {
        System.out.println("JETTY-WS push failed " + e);
        done.countDown();
      }));
      try { done.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
      System.out.println("JETTY-WS out-of-band push completed on thread=" + Thread.currentThread()
          + " virtual=" + Thread.currentThread().isVirtual());
    });
    pusher.join();
    ws.sendClose(1000, "bye").join();

    // --- 3. backpressure: peer stops reading ---
    System.out.println("--- JETTY backpressure ---");
    SlowClient sc = new SlowClient("localhost", port, "/ws");
    if (!opened.await(5, TimeUnit.SECONDS)) System.out.println("no open");
    Thread.sleep(200);
    Session s = pushSession;
    AtomicLong queued = new AtomicLong(), succeeded = new AtomicLong(), failed = new AtomicLong();
    AtomicReference<String> firstFailure = new AtomicReference<>();
    AtomicLong firstFailAt = new AtomicLong(-1);
    String payload = "x".repeat(60_000);
    long bp0 = System.nanoTime();
    long callReturnMaxNs = 0;
    Throwable sync = null;
    for (int i = 0; i < 5000; i++) {
      long c0 = System.nanoTime();
      try {
        s.sendText(payload, Callback.from(succeeded::incrementAndGet, e -> {
          long n = failed.incrementAndGet();
          if (n == 1) {
            firstFailure.set(e.toString());
            firstFailAt.set(queued.get());
          }
        }));
      } catch (Throwable t) {
        sync = t;
        break;
      }
      long c1 = System.nanoTime();
      callReturnMaxNs = Math.max(callReturnMaxNs, c1 - c0);
      queued.incrementAndGet();
      if (failed.get() > 0 && queued.get() > firstFailAt.get() + 50) break;
      if ((System.nanoTime() - bp0) / 1e9 > 15) { System.out.println("JETTY-WS bp: 15s cap hit"); break; }
    }
    Thread.sleep(500);
    System.out.printf("JETTY-WS bp: queued=%d succeeded=%d failed=%d firstFailAtQueueDepth=%d%n",
        queued.get(), succeeded.get(), failed.get(), firstFailAt.get());
    System.out.println("JETTY-WS bp: synchronous-throw=" + sync);
    System.out.println("JETTY-WS bp: firstFailure=" + firstFailure.get());
    System.out.printf("JETTY-WS bp: max sendText() call duration = %.3f ms (blocking? %b)%n",
        callReturnMaxNs / 1e6, callReturnMaxNs > 50_000_000L);
    System.out.println("JETTY-WS bp: session still open = " + s.isOpen());
    sc.close();

    // --- 4. in-process restart with the WS handler installed ---
    server.stop();
    long r0 = System.nanoTime();
    server.start();
    long r1 = System.nanoTime();
    System.out.printf("JETTY(with-ws) in-process-restart-ms=%.1f%n", (r1 - r0) / 1e6);
    server.stop();
    System.exit(0);
  }
}
