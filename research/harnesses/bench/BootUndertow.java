import io.undertow.Undertow;
import io.undertow.server.*;
import io.undertow.util.Headers;
public class BootUndertow {
  static Undertow make() {
    return Undertow.builder().addHttpListener(0, "localhost")
      .setHandler(new HttpHandler(){ public void handleRequest(HttpServerExchange ex) {
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        ex.getResponseSender().send("t=" + Thread.currentThread() + " virtual=" + Thread.currentThread().isVirtual());
      }}).build();
  }
  public static void main(String[] a) throws Exception {
    long jvmStart = ProcessHandle.current().info().startInstant().get().toEpochMilli();
    long t0=System.nanoTime(); Undertow s = make(); s.start(); long t1=System.nanoTime();
    System.out.printf("UNDERTOW cold-start-ms=%.1f wallclock-from-jvm-ms=%d%n",(t1-t0)/1e6, System.currentTimeMillis()-jvmStart);
    int port = ((java.net.InetSocketAddress)s.getListenerInfo().get(0).getAddress()).getPort();
    var cl = java.net.http.HttpClient.newHttpClient();
    System.out.println("UNDERTOW handler-thread: " + cl.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body());
    for(int i=0;i<3;i++){ s.stop(); Undertow s2=make(); long r0=System.nanoTime(); s2.start(); long r1=System.nanoTime();
      System.out.printf("UNDERTOW in-process-restart-%d-ms=%.1f%n",i,(r1-r0)/1e6); s=s2; }
    s.stop(); System.exit(0);
  }
}
