import com.sun.net.httpserver.*;
import java.net.InetSocketAddress; import java.util.concurrent.Executors;
public class BootJdk {
  static HttpServer make() throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    s.createContext("/", ex -> { byte[] b = ("t="+Thread.currentThread()).getBytes(); ex.sendResponseHeaders(200,b.length); ex.getResponseBody().write(b); ex.close(); });
    return s;
  }
  public static void main(String[] a) throws Exception {
    long jvmStart = ProcessHandle.current().info().startInstant().get().toEpochMilli();
    long t0=System.nanoTime(); HttpServer s = make(); s.start(); long t1=System.nanoTime();
    System.out.printf("JDK cold-start-ms=%.1f wallclock-from-jvm-ms=%d%n",(t1-t0)/1e6, System.currentTimeMillis()-jvmStart);
    var cl = java.net.http.HttpClient.newHttpClient();
    var resp = cl.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+s.getAddress().getPort()+"/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    System.out.println("JDK handler-thread: " + resp.body());
    for(int i=0;i<3;i++){ s.stop(0); HttpServer s2=make(); long r0=System.nanoTime(); s2.start(); long r1=System.nanoTime();
      System.out.printf("JDK in-process-restart-%d-ms=%.1f%n", i,(r1-r0)/1e6); s=s2; }
    s.stop(0);
  }
}
