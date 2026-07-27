import io.helidon.webserver.WebServer;
import io.helidon.webserver.websocket.WsRouting;
import io.helidon.websocket.*;
import io.helidon.common.buffers.BufferData;
import java.util.concurrent.*;

public class WsHelidon {
  static final CountDownLatch latch = new CountDownLatch(1);
  static class Ep implements WsListener {
    public void onOpen(WsSession s) {
      System.out.println("HELIDON-WS onOpen virtual=" + Thread.currentThread().isVirtual() + " thread=" + Thread.currentThread());
    }
    public void onMessage(WsSession s, String text, boolean last) {
      System.out.println("HELIDON-WS onMessage virtual=" + Thread.currentThread().isVirtual() + " thread=" + Thread.currentThread());
      s.send("echo:"+text, true);
    }
    public void onError(WsSession s, Throwable t){ System.out.println("HELIDON-WS error "+t); latch.countDown(); }
  }
  public static void main(String[] a) throws Exception {
    long t0=System.nanoTime();
    WebServer s = WebServer.builder().port(0).addRouting(WsRouting.builder().endpoint("/ws", new Ep())).build().start();
    System.out.printf("HELIDON(with-ws) cold-start-ms=%.1f%n",(System.nanoTime()-t0)/1e6);
    var cl = java.net.http.HttpClient.newHttpClient();
    var ws = cl.newWebSocketBuilder().buildAsync(java.net.URI.create("ws://localhost:"+s.port()+"/ws"), new java.net.http.WebSocket.Listener(){
      public CompletionStage<?> onText(java.net.http.WebSocket w, CharSequence d, boolean last){
        System.out.println("CLIENT got: "+d); latch.countDown(); return null; }
    }).join();
    ws.sendText("hello", true);
    latch.await(5, TimeUnit.SECONDS);
    ws.sendClose(1000,"bye");
    s.stop();
    long r0=System.nanoTime(); s.start(); long r1=System.nanoTime();
    System.out.printf("HELIDON(with-ws) in-process-restart-ms=%.1f%n",(r1-r0)/1e6);
    s.stop(); System.exit(0);
  }
}
