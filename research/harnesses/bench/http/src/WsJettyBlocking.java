import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Can Jetty 12.1's callback-only WebSocket write API be adapted to a blocking,
 * direct-style, virtual-thread-friendly call with real backpressure?
 *
 * Callback.Completable extends CompletableFuture<Void>, so:
 *     Callback.Completable.with(cb -> session.sendText(msg, cb)).join()
 * If the callback completes only when the frame reaches the socket, join()
 * blocks once the peer stops reading — which is exactly the semantic eezo's
 * diff-push loop needs, and unlike Helidon it can carry a timeout.
 */
public class WsJettyBlocking {
  static final CountDownLatch opened = new CountDownLatch(1);
  static volatile Session sess;

  public static class Ep implements Session.Listener.AutoDemanding {
    public void onWebSocketOpen(Session s) { sess = s; opened.countDown(); }
    public void onWebSocketError(Throwable t) { }
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    var tp = new VirtualThreadPool(); tp.setMaxConcurrentTasks(0);
    var server = new Server(tp);
    var c = new ServerConnector(server); c.setPort(port); server.addConnector(c);
    var ws = WebSocketUpgradeHandler.from(server);
    ws.configure(cont -> cont.addMapping("/ws", (rq, rs, cb) -> new Ep()));
    server.setHandler(ws);
    server.start();

    SlowClient sc = new SlowClient("localhost", port, "/ws");
    opened.await(5, TimeUnit.SECONDS);
    Thread.sleep(200);
    String payload = "x".repeat(60_000);

    // --- blocking adapter, no timeout ---
    AtomicLong sent = new AtomicLong();
    AtomicLong maxCallNs = new AtomicLong();
    AtomicReference<String> err = new AtomicReference<>("none");
    Thread w = Thread.ofVirtual().start(() -> {
      try {
        for (int i = 0; i < 5000; i++) {
          long t0 = System.nanoTime();
          Callback.Completable.with(cb -> sess.sendText(payload, cb)).join();
          maxCallNs.accumulateAndGet(System.nanoTime() - t0, Math::max);
          sent.incrementAndGet();
        }
      } catch (Throwable t) { err.set(t.toString()); }
    });
    w.join(10_000);
    Runtime r = Runtime.getRuntime();
    System.gc(); Thread.sleep(200);
    System.out.printf("blocking-adapter: sent=%d stillBlocked=%b err=%s maxCall=%.0f ms heapUsed=%.1f MiB%n",
        sent.get(), w.isAlive(), err.get(), maxCallNs.get() / 1e6,
        (r.totalMemory() - r.freeMemory()) / 1048576.0);
    System.out.println("  => backpressure applied at frame " + sent.get()
        + " (unbounded fire-and-forget reached 5000 with 293 MiB queued)");

    // --- same adapter with a timeout, which Helidon's blocking send cannot do ---
    long t0 = System.nanoTime();
    String outcome;
    try {
      Callback.Completable.with(cb -> sess.sendText(payload, cb))
          .orTimeout(500, TimeUnit.MILLISECONDS).join();
      outcome = "completed";
    } catch (Throwable t) {
      outcome = t.getClass().getSimpleName() + " / " + (t.getCause() == null ? "" : t.getCause().getClass().getSimpleName());
    }
    System.out.printf("with-timeout(500ms): %s after %.0f ms%n", outcome, (System.nanoTime() - t0) / 1e6);

    w.interrupt();
    sc.close();
    server.stop();
    System.exit(0);
  }
}
