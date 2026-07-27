import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Does Jetty 12.1's WebSocket write path bound its outgoing queue?
 *
 * Run with arg[1] = maxOutgoingFrames (-1 keeps the default). Measures heap
 * growth while the peer refuses to read, which is the OOM question for eezo:
 * thousands of LiveView connections each being pushed diffs.
 */
public class WsJettyQueue {
  static final CountDownLatch opened = new CountDownLatch(1);
  static volatile Session pushSession;
  static int maxOutgoing;

  public static class Endpoint implements Session.Listener.AutoDemanding {
    public void onWebSocketOpen(Session s) {
      if (maxOutgoing != -1) s.setMaxOutgoingFrames(maxOutgoing);
      pushSession = s;
      opened.countDown();
    }
    public void onWebSocketError(Throwable t) { }
    public void onWebSocketClose(int c, String r) { }
  }

  static long usedHeap() {
    System.gc();
    Runtime r = Runtime.getRuntime();
    return r.totalMemory() - r.freeMemory();
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    maxOutgoing = Integer.parseInt(a[1]);
    VirtualThreadPool tp = new VirtualThreadPool();
    tp.setMaxConcurrentTasks(0);
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server);
    c.setPort(port);
    server.addConnector(c);
    WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server);
    server.setHandler(ws);
    ws.configure(container -> container.addMapping("/ws", (rq, rs, cb) -> new Endpoint()));
    server.start();

    SlowClient sc = new SlowClient("localhost", port, "/ws");
    opened.await(5, TimeUnit.SECONDS);
    Thread.sleep(200);
    Session s = pushSession;

    long h0 = usedHeap();
    AtomicLong ok = new AtomicLong(), fail = new AtomicLong();
    AtomicReference<String> firstFail = new AtomicReference<>();
    AtomicLong firstFailAt = new AtomicLong(-1);
    String payload = "x".repeat(60_000);
    long queued = 0;
    Throwable sync = null;
    for (int i = 0; i < 5000; i++) {
      final long q = queued;
      try {
        s.sendText(payload, Callback.from(ok::incrementAndGet, e -> {
          if (fail.incrementAndGet() == 1) { firstFail.set(e.toString()); firstFailAt.set(q); }
        }));
      } catch (Throwable t) { sync = t; break; }
      queued++;
      if (fail.get() > 0) break;
    }
    Thread.sleep(500);
    long h1 = usedHeap();
    System.out.printf("maxOutgoingFrames=%d queued=%d written-ok=%d failed=%d firstFailAtDepth=%d%n",
        maxOutgoing, queued, ok.get(), fail.get(), firstFailAt.get());
    System.out.println("  synchronous throw : " + sync);
    System.out.println("  first failure     : " + firstFail.get());
    System.out.printf("  heap before=%.1f MiB after=%.1f MiB growth=%.1f MiB (payload bytes offered=%.1f MiB)%n",
        h0 / 1048576.0, h1 / 1048576.0, (h1 - h0) / 1048576.0, queued * 60_000 / 1048576.0);
    System.out.println("  session open      : " + s.isOpen());
    sc.close();
    server.stop();
    System.exit(0);
  }
}
