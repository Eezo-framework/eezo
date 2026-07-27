import java.util.function.IntConsumer;

/** Helidon counterpart of JettyBoot. See ClassLoaderSwap. */
public class HelidonBoot implements IntConsumer {
  public void accept(int port) {
    try {
      var s = io.helidon.webserver.WebServer.builder().port(port)
          .addRouting(io.helidon.webserver.websocket.WsRouting.builder()
              .endpoint("/ws", new io.helidon.websocket.WsListener() {}))
          .routing(r -> r.get("/", (rq, rs) -> rs.send("ok")))
          .build();
      s.start();
      var cl = java.net.http.HttpClient.newHttpClient();
      cl.send(java.net.http.HttpRequest.newBuilder(
              java.net.URI.create("http://localhost:" + port + "/")).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      cl.close();
      s.stop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
