import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/** Isolates whether an ANONYMOUS Session.Listener implementation upgrades. */
public class AnonProbe {
  public static class Named implements Session.Listener.AutoDemanding {}
  public static void main(String[] a) throws Exception {
    for (boolean anon : new boolean[]{true, false}) {
      int port = Integer.parseInt(a[0]) + (anon ? 0 : 1);
      var tp = new VirtualThreadPool(); tp.setMaxConcurrentTasks(0);
      var server = new Server(tp);
      var c = new ServerConnector(server); c.setPort(port); server.addConnector(c);
      var ws = WebSocketUpgradeHandler.from(server);
      ws.configure(cont -> cont.addMapping("/ws", (rq, rs, cb) ->
          anon ? new Session.Listener.AutoDemanding() {} : new Named()));
      server.setHandler(ws);
      server.start();
      try { new SlowClient("localhost", port, "/ws"); System.out.println("anonymous=" + anon + " -> upgrade OK"); }
      catch (Exception e) { System.out.println("anonymous=" + anon + " -> " + e); }
      server.stop();
    }
    System.exit(0);
  }
}
