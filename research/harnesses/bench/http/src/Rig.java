import java.lang.management.ManagementFactory;

/**
 * Shared measurement helpers for the eezo HTTP server boot / restart rigs.
 *
 * NOTE: the probe HttpClient is a single static instance. An earlier version of
 * this rig built a fresh HttpClient per probe, whose selector/worker threads are
 * never reclaimed, which made every server look like it leaked ~4 threads per
 * restart. That was the rig, not the servers.
 */
public final class Rig {
  private static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newHttpClient();

  public static long jvmStartMs() {
    return ProcessHandle.current().info().startInstant().get().toEpochMilli();
  }

  public static int liveThreads() {
    return ManagementFactory.getThreadMXBean().getThreadCount();
  }

  /** Names of live platform threads, for leak attribution. */
  public static java.util.List<String> threadNames() {
    var tmx = ManagementFactory.getThreadMXBean();
    var out = new java.util.ArrayList<String>();
    for (var ti : tmx.dumpAllThreads(false, false)) out.add(ti.getThreadName());
    java.util.Collections.sort(out);
    return out;
  }

  public static String probe(int port) throws Exception {
    var rq = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/")).build();
    return CLIENT.send(rq, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
  }

  /** Warm the probe client's own threads so they do not pollute the baseline. */
  public static void warmClient() {
    try {
      var rq = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:1/")).build();
      CLIENT.send(rq, java.net.http.HttpResponse.BodyHandlers.ofString());
    } catch (Exception ignored) {
    }
  }

  public static void report(String name, double coldMs, long wallMs, String handlerThread) {
    System.out.printf("%s cold-start-ms=%.1f wallclock-from-jvm-ms=%d%n", name, coldMs, wallMs);
    System.out.println(name + " handler-thread: " + handlerThread);
  }
}
