/**
 * Opens N WebSocket connections to ConnScale and holds them open, then reads
 * the server's /stats. Run in its own JVM so the client's socket bookkeeping
 * does not land on the server's heap.
 *
 * args: <port> <n> <statsPath>
 */
public class ConnLoad {
  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    int n = Integer.parseInt(a[1]);
    String statsPath = a.length > 2 ? a[2] : "/";
    System.out.println("baseline: " + stats(port, statsPath));
    var held = new java.util.ArrayList<SlowClient>(n);
    long t0 = System.nanoTime();
    int failed = 0;
    for (int i = 0; i < n; i++) {
      try {
        held.add(new SlowClient("localhost", port, "/ws"));
      } catch (Exception e) {
        failed++;
        if (failed < 3) System.out.println("  connect failed at " + i + ": " + e);
        if (failed > 20) break;
      }
    }
    double openMs = (System.nanoTime() - t0) / 1e6;
    System.out.printf("opened=%d failed=%d in %.0f ms (%.2f ms/conn)%n",
        held.size(), failed, openMs, openMs / Math.max(1, held.size()));
    Thread.sleep(1500);
    System.out.println("loaded  : " + stats(port, statsPath));
    for (SlowClient s : held) s.close();
    Thread.sleep(2500);
    System.out.println("released: " + stats(port, statsPath));
    System.exit(0);
  }

  static String stats(int port, String path) throws Exception {
    var cl = java.net.http.HttpClient.newHttpClient();
    var r = cl.send(java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://localhost:" + port + path)).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    cl.close();
    return r;
  }
}
