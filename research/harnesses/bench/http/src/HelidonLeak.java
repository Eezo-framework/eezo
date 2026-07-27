import io.helidon.webserver.WebServer;
import java.lang.management.ManagementFactory;

/**
 * Is the Helidon thread residual observed in BootHelidon proportional to the
 * number of WebServer instances created, i.e. a genuine per-instance leak?
 * Creates N servers on the same port, each stopped before the next is built.
 */
public class HelidonLeak {
  static long count(String needle) {
    return java.util.Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
        .filter(t -> t.getThreadName().contains(needle)).count();
  }

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    int n = Integer.parseInt(a[1]);
    for (int i = 1; i <= n; i++) {
      WebServer s = WebServer.builder().port(port)
          .routing(r -> r.get("/", (rq, rs) -> rs.send("ok"))).build();
      s.start();
      s.stop();
      System.gc();
      Thread.sleep(150);
      System.out.printf("after %d server instances: idle-connection-timer=%d http-timer=%d totalThreads=%d%n",
          i, count("helidon-idle-connection-timer"), count("helidon-http-timer"),
          ManagementFactory.getThreadMXBean().getThreadCount());
    }
    System.exit(0);
  }
}
