import io.helidon.webserver.WebServer;
public class BootHelidon {
  static WebServer make() {
    return WebServer.builder().port(0)
      .routing(r -> r.get("/", (req, res) -> res.send("t=" + Thread.currentThread())))
      .build();
  }
  public static void main(String[] a) throws Exception {
    long jvmStart = ProcessHandle.current().info().startInstant().get().toEpochMilli();
    long t0 = System.nanoTime();
    WebServer s = make(); s.start();
    long t1 = System.nanoTime();
    System.out.printf("HELIDON cold-start-ms=%.1f wallclock-from-jvm-ms=%d%n",(t1-t0)/1e6, System.currentTimeMillis()-jvmStart);
    var cl = java.net.http.HttpClient.newHttpClient();
    var resp = cl.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+s.port()+"/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    System.out.println("HELIDON handler-thread: " + resp.body());
    for (int i=0;i<3;i++){ s.stop(); WebServer s2 = make(); long r0=System.nanoTime(); s2.start(); long r1=System.nanoTime();
      System.out.printf("HELIDON in-process-restart-%d-ms=%.1f%n", i, (r1-r0)/1e6); s = s2; }
    s.stop();
  }
}
