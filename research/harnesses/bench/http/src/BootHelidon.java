import io.helidon.webserver.WebServer;

public class BootHelidon {
  static int PORT;

  static WebServer make() {
    return WebServer.builder().port(PORT)
        .routing(r -> r.get("/", (req, res) -> {
          Thread t = Thread.currentThread();
          res.send("t=" + t + " virtual=" + t.isVirtual());
        }))
        .build();
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    WebServer s = make();
    s.start();
    long t1 = System.nanoTime();
    Rig.report("HELIDON", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("HELIDON threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    for (int i = 0; i < 5; i++) {
      s.stop();
      long r0 = System.nanoTime();
      WebServer s2 = make();
      s2.start();
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("HELIDON restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    s.stop();
    Thread.sleep(300);
    System.out.printf("HELIDON threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("HELIDON live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
