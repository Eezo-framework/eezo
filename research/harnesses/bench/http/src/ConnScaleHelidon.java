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
public class ConnScaleHelidon {
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
    helidon(port);
    Thread.sleep(Long.MAX_VALUE);
  }

  static void helidon(int port) {
    var s = io.helidon.webserver.WebServer.builder().port(port)
        .addRouting(io.helidon.webserver.websocket.WsRouting.builder()
            .endpoint("/ws", new io.helidon.websocket.WsListener() {}))
        .routing(r -> r.get("/stats", (rq, rs) -> rs.send(stats())))
        .build();
    s.start();
    System.out.println("helidon ready baseline " + stats());
  }
}

