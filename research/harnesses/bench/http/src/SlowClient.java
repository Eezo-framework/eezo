import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * A WebSocket client that completes the RFC 6455 handshake and then never reads
 * another byte. Used to fill the server's socket send buffer and force the
 * server's write path into its backpressure regime.
 */
public final class SlowClient implements Closeable {
  private final Socket sock;
  public final InputStream in;
  public final OutputStream out;

  public SlowClient(String host, int port, String path) throws Exception {
    sock = new Socket(host, port);
    sock.setTcpNoDelay(true);
    // Small receive buffer so the server's send buffer fills quickly.
    sock.setReceiveBufferSize(4096);
    in = sock.getInputStream();
    out = sock.getOutputStream();
    byte[] nonce = new byte[16];
    new java.util.Random(42).nextBytes(nonce);
    String key = Base64.getEncoder().encodeToString(nonce);
    String req = "GET " + path + " HTTP/1.1\r\n"
        + "Host: " + host + ":" + port + "\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + "Sec-WebSocket-Key: " + key + "\r\n"
        + "Sec-WebSocket-Version: 13\r\n\r\n";
    out.write(req.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    // Read exactly the handshake response headers, then stop reading forever.
    ByteArrayOutputStream hdr = new ByteArrayOutputStream();
    int state = 0;
    while (state < 4) {
      int b = in.read();
      if (b < 0) throw new EOFException("handshake truncated: " + hdr.toString(StandardCharsets.ISO_8859_1));
      hdr.write(b);
      state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
          ? state + 1 : 0;
    }
    String resp = hdr.toString(StandardCharsets.ISO_8859_1);
    if (!resp.startsWith("HTTP/1.1 101")) throw new IOException("no 101: " + resp);
    String expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
    if (!resp.contains(expect)) throw new IOException("bad accept: " + resp);
  }

  /** Send one masked text frame (client frames must be masked). */
  public void sendText(String s) throws IOException {
    byte[] payload = s.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream f = new ByteArrayOutputStream();
    f.write(0x81);
    byte[] mask = new byte[]{1, 2, 3, 4};
    if (payload.length < 126) f.write(0x80 | payload.length);
    else if (payload.length < 65536) {
      f.write(0x80 | 126);
      f.write(payload.length >> 8);
      f.write(payload.length & 0xff);
    } else {
      f.write(0x80 | 127);
      for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i)) & 0xff);
    }
    f.write(mask);
    for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i % 4]);
    out.write(f.toByteArray());
    out.flush();
  }

  public void close() throws IOException {
    sock.close();
  }
}
