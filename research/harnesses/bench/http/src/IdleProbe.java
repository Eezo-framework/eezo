import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.time.Duration;

/** Where can a per-session WebSocket idle timeout legally be set in Jetty 12.1? */
public class IdleProbe {
  static Duration D;
  public static class InOpen implements Session.Listener.AutoDemanding {
    public void onWebSocketOpen(Session s) { s.setIdleTimeout(D); }
  }
  public static class Plain implements Session.Listener.AutoDemanding {}

  public static void main(String[] a) throws Exception {
    int base = Integer.parseInt(a[0]);
    String[] cases = {"in-onOpen:PT30M", "in-onOpen:ZERO", "container-level:PT30M", "none"};
    for (int i = 0; i < cases.length; i++) {
      String cse = cases[i];
      int port = base + i;
      var tp = new VirtualThreadPool(); tp.setMaxConcurrentTasks(0);
      var server = new Server(tp);
      var c = new ServerConnector(server); c.setPort(port); server.addConnector(c);
      var ws = WebSocketUpgradeHandler.from(server);
      boolean inOpen = cse.startsWith("in-onOpen");
      D = cse.endsWith("ZERO") ? Duration.ZERO : Duration.ofMinutes(30);
      ws.configure(cont -> {
        if (cse.startsWith("container-level")) cont.setIdleTimeout(Duration.ofMinutes(30));
        cont.addMapping("/ws", (rq, rs, cb) -> inOpen ? new InOpen() : new Plain());
      });
      server.setHandler(ws);
      server.start();
      try {
        var sc = new SlowClient("localhost", port, "/ws");
        System.out.println(cse + " -> upgrade OK");
        sc.close();
      } catch (Exception e) {
        System.out.println(cse + " -> FAILED: " + e.getMessage().replaceAll("\r\n", " ").substring(0, Math.min(60, e.getMessage().length())));
      }
      server.stop();
    }
    System.exit(0);
  }
}
