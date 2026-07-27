import io.undertow.Undertow;
import io.undertow.server.*;
import io.undertow.util.Headers;

public class BootUndertow {
  static int PORT;

  static Undertow make() {
    return Undertow.builder().addHttpListener(PORT, "localhost")
        .setHandler(new HttpHandler() {
          public void handleRequest(HttpServerExchange ex) {
            Thread t = Thread.currentThread();
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            ex.getResponseSender().send("t=" + t + " virtual=" + t.isVirtual()
                + " inIoThread=" + ex.isInIoThread());
          }
        }).build();
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    Undertow s = make();
    s.start();
    long t1 = System.nanoTime();
    Rig.report("UNDERTOW", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("UNDERTOW threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    for (int i = 0; i < 5; i++) {
      s.stop();
      long r0 = System.nanoTime();
      Undertow s2 = make();
      s2.start();
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("UNDERTOW restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    s.stop();
    Thread.sleep(300);
    System.out.printf("UNDERTOW threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("UNDERTOW live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
