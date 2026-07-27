import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import java.nio.charset.StandardCharsets;

/** Minimal raw-Netty HTTP/1.1 server, to price "use Netty directly". */
public class BootNetty {
  static int PORT;

  static class Boss {
    EventLoopGroup boss, work;
    Channel ch;
  }

  static Boss make() throws Exception {
    Boss b = new Boss();
    b.boss = new NioEventLoopGroup(1);
    b.work = new NioEventLoopGroup();
    ServerBootstrap sb = new ServerBootstrap();
    sb.group(b.boss, b.work)
        .channel(NioServerSocketChannel.class)
        .childHandler(new ChannelInitializer<SocketChannel>() {
          protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpObjectAggregator(65536));
            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
              protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                Thread t = Thread.currentThread();
                byte[] body = ("t=" + t + " virtual=" + t.isVirtual()).getBytes(StandardCharsets.UTF_8);
                FullHttpResponse rs = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
                rs.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                rs.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                ctx.writeAndFlush(rs);
              }
            });
          }
        });
    b.ch = sb.bind("localhost", PORT).sync().channel();
    return b;
  }

  static void stop(Boss b) throws Exception {
    b.ch.close().sync();
    b.boss.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();
    b.work.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();
  }

  public static void main(String[] a) throws Exception {
    PORT = Integer.parseInt(a[0]);
    long jvmStart = Rig.jvmStartMs();
    Rig.warmClient();
    int t0threads = Rig.liveThreads();
    long t0 = System.nanoTime();
    Boss s = make();
    long t1 = System.nanoTime();
    Rig.report("NETTY", (t1 - t0) / 1e6, System.currentTimeMillis() - jvmStart, Rig.probe(PORT));
    System.out.printf("NETTY threads-after-boot=%d (delta=%d)%n", Rig.liveThreads(), Rig.liveThreads() - t0threads);

    for (int i = 0; i < 5; i++) {
      stop(s);
      long r0 = System.nanoTime();
      Boss s2 = make();
      long r1 = System.nanoTime();
      String body = Rig.probe(PORT);
      System.out.printf("NETTY restart-%d-same-port-ms=%.1f threads=%d ok=%b%n",
          i, (r1 - r0) / 1e6, Rig.liveThreads(), body.startsWith("t="));
      s = s2;
    }
    stop(s);
    Thread.sleep(300);
    System.out.printf("NETTY threads-after-final-stop=%d (boot-baseline=%d)%n", Rig.liveThreads(), t0threads);
    System.out.println("NETTY live-threads-at-exit: " + Rig.threadNames());
    System.exit(0);
  }
}
