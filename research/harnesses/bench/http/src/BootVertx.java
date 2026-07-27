import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public class BootVertx {
  static int PORT;
  static Vertx vertx;

  static HttpServer make() {
    return vertx.createHttpServer().requestHandler(req -> {
      Thread t = Thread.currentThread();
      req.response().end("t=" + t + " virtual=" + t.isVirtual());
    });
  }

  static void start(HttpServer s) {
    s.listen(PORT, "localhost").toCompletionStage().toCompletableFuture().join();
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    vertx = Vertx.vertx();
    HttpServer s = make();
    start(s);
    long t1 = System.nanoTime();
    Rig.report("VERTX", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("VERTX threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    // Restart the HttpServer only (Vertx instance retained; closing Vertx would
    // tear down the whole event-loop group, which is the closer analogue of a
    // JVM restart than of a handler swap). Both variants reported below.
    for (int i = 0; i < 5; i++) {
      s.close().toCompletionStage().toCompletableFuture().join();
      long r0 = System.nanoTime();
      HttpServer s2 = make();
      start(s2);
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("VERTX restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    s.close().toCompletionStage().toCompletableFuture().join();

    // Full Vertx teardown + recreate, the classloader-swap analogue.
    vertx.close().toCompletionStage().toCompletableFuture().join();
    Thread.sleep(300);
    System.out.printf("VERTX threads-after-vertx-close=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    for (int i = 0; i < 3; i++) {
      long r0 = System.nanoTime();
      vertx = Vertx.vertx();
      HttpServer s2 = make();
      start(s2);
      long r1 = System.nanoTime();
      System.out.printf("VERTX full-recreate-%d-ms=%.1f threads=%d%n", i, (r1 - r0) / 1e6, Rig.liveThreads());
      s2.close().toCompletionStage().toCompletableFuture().join();
      vertx.close().toCompletionStage().toCompletableFuture().join();
    }
    Thread.sleep(300);
    System.out.printf("VERTX threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("VERTX live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
