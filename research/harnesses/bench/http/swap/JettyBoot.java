import java.util.function.IntConsumer;

/**
 * Loaded into a throwaway URLClassLoader by ClassLoaderSwap. Boots a Jetty
 * server, serves one request, stops it, and returns. Everything it touches must
 * become unreachable when the loader is dropped, or a classloader-swapping hot
 * reload leaks the whole application on every edit.
 */
public class JettyBoot implements IntConsumer {
  public void accept(int port) {
    try {
      var tp = new org.eclipse.jetty.util.thread.VirtualThreadPool();
      tp.setMaxConcurrentTasks(0);
      var server = new org.eclipse.jetty.server.Server(tp);
      var c = new org.eclipse.jetty.server.ServerConnector(server);
      c.setPort(port);
      server.addConnector(c);
      var ws = org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler.from(server);
      server.setHandler(ws);
      ws.configure(container -> container.addMapping("/ws",
          (rq, rs, cb) -> new org.eclipse.jetty.websocket.api.Session.Listener.AutoDemanding() {}));
      server.start();
      var cl = java.net.http.HttpClient.newHttpClient();
      cl.send(java.net.http.HttpRequest.newBuilder(
              java.net.URI.create("http://localhost:" + port + "/")).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      cl.close();
      server.stop();
      server.destroy();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
