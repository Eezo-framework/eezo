import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.util.concurrent.*;

public class WsJetty {
  static final CountDownLatch latch = new CountDownLatch(1);
  public static class Endpoint implements Session.Listener.AutoDemanding {
    Session s;
    public void onWebSocketOpen(Session s) { this.s = s;
      System.out.println("JETTY-WS onOpen virtual=" + Thread.currentThread().isVirtual() + " thread=" + Thread.currentThread());
      System.out.println("JETTY-WS defaults: idleTimeout=" + s.getIdleTimeout() + " maxFrameSize=" + s.getMaxFrameSize()
        + " maxTextMessageSize=" + s.getMaxTextMessageSize() + " maxBinaryMessageSize=" + s.getMaxBinaryMessageSize()
        + " maxOutgoingFrames=" + s.getMaxOutgoingFrames() + " autoFragment=" + s.isAutoFragment()
        + " outputBufferSize=" + s.getOutputBufferSize() + " inputBufferSize=" + s.getInputBufferSize());
    }
    public void onWebSocketText(String m) {
      System.out.println("JETTY-WS onText virtual=" + Thread.currentThread().isVirtual() + " thread=" + Thread.currentThread());
      s.sendText("echo:"+m, Callback.NOOP);
    }
    public void onWebSocketError(Throwable t) { System.out.println("JETTY-WS error "+t); latch.countDown(); }
  }
  public static void main(String[] a) throws Exception {
    QueuedThreadPool tp = new QueuedThreadPool();
    tp.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
    Server server = new Server(tp);
    ServerConnector c = new ServerConnector(server); c.setPort(0); server.addConnector(c);
    WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server);
    server.setHandler(wsHandler);
    wsHandler.configure(container -> container.addMapping("/ws", (rq, rs, cb) -> new Endpoint()));
    long t0 = System.nanoTime(); server.start();
    System.out.printf("JETTY(with-ws) cold-start-ms=%.1f%n", (System.nanoTime()-t0)/1e6);
    int port = c.getLocalPort();
    var cl = java.net.http.HttpClient.newHttpClient();
    var ws = cl.newWebSocketBuilder().buildAsync(java.net.URI.create("ws://localhost:"+port+"/ws"), new java.net.http.WebSocket.Listener(){
      public CompletionStage<?> onText(java.net.http.WebSocket w, CharSequence d, boolean last){
        System.out.println("CLIENT got: "+d); latch.countDown(); return null; }
    }).join();
    ws.sendText("hello", true);
    latch.await(5, TimeUnit.SECONDS);
    ws.sendClose(1000,"bye");
    server.stop();
    long r0=System.nanoTime(); server.start(); long r1=System.nanoTime();
    System.out.printf("JETTY(with-ws) in-process-restart-ms=%.1f%n",(r1-r0)/1e6);
    server.stop();
    System.exit(0);
  }
}
