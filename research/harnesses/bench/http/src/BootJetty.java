import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.*;
import org.eclipse.jetty.util.Callback;

public class BootJetty {
  static int PORT;

  static Server make() {
    // Jetty 12.1's dedicated virtual-thread ThreadPool implementation.
    // setMaxConcurrentTasks(0) disables the concurrency limiter, so every task
    // gets its own virtual thread with no platform-thread ceiling.
    VirtualThreadPool tp = new VirtualThreadPool();
    tp.setMaxConcurrentTasks(0);
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server);
    c.setPort(PORT);
    server.addConnector(c);
    server.setHandler(new Handler.Abstract() {
      public boolean handle(Request rq, Response rs, Callback cb) {
        Thread t = Thread.currentThread();
        byte[] b = ("t=" + t + " virtual=" + t.isVirtual()).getBytes();
        rs.write(true, java.nio.ByteBuffer.wrap(b), cb);
        return true;
      }
    });
    return server;
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    Server s = make();
    s.start();
    long t1 = System.nanoTime();
    Rig.report("JETTY", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("JETTY threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    for (int i = 0; i < 5; i++) {
      s.stop();
      long r0 = System.nanoTime();
      Server s2 = make();
      s2.start();
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("JETTY restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    s.stop();
    Thread.sleep(300);
    System.out.printf("JETTY threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("JETTY live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
