import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class BootJdk {
  static int PORT;

  static HttpServer make() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    s.createContext("/", ex -> {
      Thread t = Thread.currentThread();
      byte[] b = ("t=" + t + " virtual=" + t.isVirtual()).getBytes();
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    return s;
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    HttpServer s = make();
    s.start();
    long t1 = System.nanoTime();
    Rig.report("JDK", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("JDK threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    for (int i = 0; i < 5; i++) {
      s.stop(0);
      long r0 = System.nanoTime();
      HttpServer s2 = make();
      s2.start();
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("JDK restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    s.stop(0);
    Thread.sleep(300);
    System.out.printf("JDK threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("JDK live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
