import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.util.Callback;
import java.util.concurrent.*;

/**
 * Does a blocking call inside a `synchronized` block in an eezo handler pin the
 * carrier thread, and does the JDK baseline change that? (JEP 491, JDK 24.)
 *
 * /plain      : blocking sleep, no monitor. Should never pin.
 * /sync       : blocking sleep inside synchronized on a per-request lock.
 *               Under JDK 21 this pins the carrier; under JDK 24+ it must not.
 *
 * Concurrency is the observable: N simultaneous requests each sleeping D ms
 * complete in ~D ms if carriers are released, and in ~ceil(N/carriers)*D ms if
 * they are pinned.
 */
public class PinTest {
  static final int SLEEP_MS = 400;

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    int n = Integer.parseInt(a[1]);
    VirtualThreadPool tp = new VirtualThreadPool();
    tp.setMaxConcurrentTasks(0);
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server);
    c.setPort(port);
    server.addConnector(c);
    server.setHandler(new Handler.Abstract() {
      public boolean handle(Request rq, Response rs, Callback cb) {
        boolean sync = rq.getHttpURI().getPath().startsWith("/sync");
        try {
          if (sync) {
            Object lock = new Object();          // uncontended, per request
            synchronized (lock) {
              Thread.sleep(SLEEP_MS);            // blocking call while holding a monitor
            }
          } else {
            Thread.sleep(SLEEP_MS);
          }
        } catch (InterruptedException ignored) {
        }
        rs.write(true, java.nio.ByteBuffer.wrap("ok".getBytes()), cb);
        return true;
      }
    });
    server.start();

    System.out.println("carrier parallelism (jdk.virtualThreadScheduler.parallelism default) = "
        + Runtime.getRuntime().availableProcessors());

    for (String path : new String[]{"/plain", "/sync", "/plain", "/sync"}) {
      var cl = java.net.http.HttpClient.newBuilder()
          .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
      var uri = java.net.URI.create("http://localhost:" + port + path);
      var futures = new java.util.ArrayList<CompletableFuture<?>>();
      long t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
        futures.add(cl.sendAsync(java.net.http.HttpRequest.newBuilder(uri).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      double ms = (System.nanoTime() - t0) / 1e6;
      System.out.printf("%-7s n=%d wall=%.0f ms  ideal=%d ms  ratio=%.2f%n",
          path, n, ms, SLEEP_MS, ms / SLEEP_MS);
      cl.close();
    }
    server.stop();
    System.exit(0);
  }
}
