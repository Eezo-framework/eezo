import java.lang.management.ManagementFactory;

/**
 * Server side of the "thousands of long-lived WebSocket connections" question.
 *
 * Starts a WebSocket echo endpoint plus a /stats endpoint reporting heap and
 * thread count after a full GC. The load is applied from a separate JVM
 * (ConnLoad) so the client's own socket bookkeeping does not contaminate the
 * server's heap measurement.
 *
 * args: <port>
 */
public class ConnScaleJetty {
  static String stats() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try { Thread.sleep(150); } catch (InterruptedException ignored) {}
    }
    var mu = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return "heapUsedBytes=" + mu.getUsed()
        + " threads=" + ManagementFactory.getThreadMXBean().getThreadCount()
        + " peakThreads=" + ManagementFactory.getThreadMXBean().getPeakThreadCount();
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    jetty(port);
    Thread.sleep(Long.MAX_VALUE);
  }

  /** Named listener; an anonymous AutoDemanding implementation makes the
   *  upgrade fail with HTTP 500 under Jetty 12.1.11. */
  public static class Held implements org.eclipse.jetty.websocket.api.Session.Listener.AutoDemanding {
    public void onWebSocketOpen(org.eclipse.jetty.websocket.api.Session s) { }
  }

  static void jetty(int port) throws Exception {
    var tp = new org.eclipse.jetty.util.thread.VirtualThreadPool();
    tp.setMaxConcurrentTasks(0);
    var server = new org.eclipse.jetty.server.Server(tp);
    var c = new org.eclipse.jetty.server.ServerConnector(server);
    c.setPort(port);
    server.addConnector(c);
    var ws = org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler.from(server);
    ws.configure(container -> {
      container.setIdleTimeout(java.time.Duration.ofMinutes(30));  // no idle close during the run
      container.addMapping("/ws", (rq, rs, cb) -> new Held());
    });
    ws.setHandler(new org.eclipse.jetty.server.Handler.Abstract() {
      public boolean handle(org.eclipse.jetty.server.Request rq,
                            org.eclipse.jetty.server.Response rs,
                            org.eclipse.jetty.util.Callback cb) {
        rs.write(true, java.nio.ByteBuffer.wrap(stats().getBytes()), cb);
        return true;
      }
    });
    server.setHandler(ws);
    server.start();
    System.out.println("jetty ready baseline " + stats());
  }


}
