import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.*;
import org.eclipse.jetty.util.Callback;
import java.util.concurrent.Executors;

public class BootJetty {
  static Server make() throws Exception {
    QueuedThreadPool tp = new QueuedThreadPool();
    tp.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server);
    c.setPort(0);
    server.addConnector(c);
    server.setHandler(new Handler.Abstract() {
      public boolean handle(Request rq, Response rs, Callback cb) {
        rs.write(true, java.nio.ByteBuffer.wrap(("t=" + Thread.currentThread()).getBytes()), cb);
        return true;
      }
    });
    return server;
  }
  public static void main(String[] a) throws Exception {
    long jvmStart = ProcessHandle.current().info().startInstant().get().toEpochMilli();
    long t0 = System.nanoTime();
    Server s = make();
    s.start();
    long t1 = System.nanoTime();
    System.out.printf("JETTY cold-start-ms=%.1f wallclock-from-jvm-ms=%d%n",
        (t1-t0)/1e6, System.currentTimeMillis()-jvmStart);
    // probe thread type
    var url = java.net.URI.create("http://localhost:" + ((ServerConnector)s.getConnectors()[0]).getLocalPort() + "/");
    var cl = java.net.http.HttpClient.newHttpClient();
    var resp = cl.send(java.net.http.HttpRequest.newBuilder(url).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    System.out.println("JETTY handler-thread: " + resp.body());
    for (int i = 0; i < 3; i++) {
      s.stop();
      Server s2 = make();
      long r0 = System.nanoTime();
      s2.start();
      long r1 = System.nanoTime();
      System.out.printf("JETTY in-process-restart-%d-ms=%.1f%n", i, (r1-r0)/1e6);
      s = s2;
    }
    s.stop();
  }
}
