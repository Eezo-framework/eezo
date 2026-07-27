# Research: JVM HTTP server on virtual threads, with WebSocket

Resolves [rcardin/eezo#3](https://github.com/rcardin/eezo/issues/3). Part of #1.

Date of investigation: 2026-07-27. All version, licence and advisory claims were
verified on that date against `maven-metadata.xml` on Maven Central, the GitHub
REST API, and the projects' own source.

**Empirical basis.** Most of the load-bearing claims here were measured, not
read. Toolchain: Temurin **JDK 21.0.11+10-LTS** (eezo's stated floor) and
Temurin **JDK 26+35**, on an **Apple M4 Pro, 14 cores, 24 GiB, macOS 15.7.4**.
Artifacts resolved with `coursier` from Maven Central at the versions current on
2026-07-27: **Jetty 12.1.11**, **Helidon 4.5.1**, **Undertow 2.4.2.Final**,
**Vert.x 5.1.5**, **Netty 4.2.16.Final**, plus the JDK's own
`com.sun.net.httpserver`. Every number marked **measured** was produced on that
machine by a rig preserved in
[`research/harnesses/bench/http/`](harnesses/bench/http/). Every number marked
**cited** carries its source inline. Reproduction is in Appendix A.

The four rigs salvaged from the 2026-07-26 run were read, three bugs in them
were fixed (see Appendix A.0), two candidates they never covered were added, and
all of their numbers were re-measured. Nothing from that salvage is quoted.

---

## 1. The findings that reframe the question

There are four, and only the last one is about picking a server.

### 1.1 The virtual-thread question is objectively decidable, and it eliminates half the field before any other axis is considered

The ticket asks whether each candidate offers "genuine per-request virtual
threads, or a thread pool with a virtual-thread executor bolted on". That framing
turns out to be too generous to three of the six candidates. Measured, by having
each server's handler print its own carrier thread:

| Server | Thread that runs the handler | Virtual? |
|---|---|---|
| Jetty 12.1.11 + `VirtualThreadPool` | `VirtualThread[#33]/runnable@ForkJoinPool-1-worker-2` | **yes** |
| Helidon 4.5.1 | `VirtualThread[#40,[0x0a5d64fd 0x0e480e89] WebServer socket]` | **yes** |
| JDK `HttpServer` + VT executor | `VirtualThread[#26]/runnable@ForkJoinPool-1-worker-1` | **yes** |
| Undertow 2.4.2 | `Thread[#33,XNIO-1 I/O-1,5,main]`, `isInIoThread=true` | **no** |
| Vert.x 5.1.5 | `Thread[#33,vert.x-eventloop-thread-0,5,main]` | **no** |
| Netty 4.2.16 | `Thread[#33,nioEventLoopGroup-3-1,10,main]` | **no** |

For Undertow, Vert.x and Netty this is not a missing configuration flag. It is
the architecture: the handler is invoked **on the event loop**, and a blocking
JDBC call there does not block "a platform thread", it blocks **one of a small
fixed number of I/O threads that also serve every other connection**. The
remedy in all three is the same and it is the thing eezo exists to avoid: hand
off to a separate executor and write the rest of your program in continuation
style. Undertow's own API says so out loud, by exposing `exchange.isInIoThread()`
so that you can branch and `dispatch()` elsewhere.

So the honest shape of this survey is **two serious candidates (Jetty, Helidon),
one toy (the JDK server, which has no WebSocket support at all, §5.6), and
three servers that are architecturally the opposite of what eezo is**. Everything
after this section is really Jetty versus Helidon.

There is an objective JVM-level fingerprint that corroborates this. After the
boot rigs finish, Jetty and Helidon leave `Read-Poller` / `Write-Poller` /
`Read-Updater` / `Write-Updater` threads behind. These are the JDK's internal
`sun.nio.ch.Poller` machinery, which only materialises when virtual threads
perform blocking socket I/O. Undertow, Vert.x and Netty never produce them,
because they never block a virtual thread on a socket. Measured; see
`logs/jdk21.log`.

### 1.2 eezo's "JDK 21 floor" is a bigger threat to eezo's design than any server choice, and it is fixable by moving the floor

This is the finding I did not expect to be this large. The ticket asked me to
check pinning specifically. Measured, on Jetty with per-request virtual threads,
200 concurrent requests each sleeping 400 ms, where the only difference is
whether the sleep happens inside a `synchronized` block on an **uncontended,
per-request** lock:

| | JDK 21.0.11 | JDK 26+35 |
|---|---|---|
| `/plain` (no monitor) | 490 ms, 413 ms | 504 ms, 424 ms |
| `/sync` (blocking inside `synchronized`) | **6887 ms, 6483 ms** | **419 ms, 422 ms** |
| degradation | **16.2x to 17.2x** | **1.05x to 1.06x** |

On JDK 21 the JVM names the culprit itself, with `-Djdk.tracePinnedThreads=short`:

```
VirtualThread[#2005]/runnable@ForkJoinPool-1-worker-14 reason:MONITOR
    PinTest$1.handle(PinTest.java:37) <== monitors:1
```

Concurrency collapses to the carrier count (14 on this machine). This is
[JEP 491, *Synchronize Virtual Threads without Pinning*](https://openjdk.org/jeps/491),
**delivered in JDK 24**, which "arrang[es] for virtual threads that block in
such constructs to release their underlying platform threads".

The consequence for eezo is blunt. eezo's pitch is "direct style on virtual
threads: just write blocking code". On JDK 21 that pitch is **false for any
call path that crosses a `synchronized` block**, which includes an unknown
amount of third-party code the user will inevitably pull in. eezo cannot audit
its users' dependencies.

I checked eezo's own inherited stack, by counting `ACC_SYNCHRONIZED` methods and
`monitorenter` bytecodes in the shipped jars (measured):

- **HikariCP 7.0.2**: 9 synchronized methods, 3 `monitorenter`. The
  administrative ones (`shutdown`, `suspendPool`, `resumePool`, `fillPool`) do
  not matter. The three that sit on the per-statement path do:
  `ProxyConnection.trackStatement`, `.untrackStatement`, `.closeStatements`.
  None of them block while holding the monitor, and a `ProxyConnection` is used
  by one thread at a time, so contention is near zero. **Low risk, not zero.**
- **pgjdbc 42.7.8**: 6 synchronized methods, and every one of them is in
  `org.postgresql.util.LazyCleaner`, a background reaper, not the query path.
  The driver has already been converted off `synchronized`. **Effectively clean.**

So eezo's *own* stack survives JDK 21. Its users' will not reliably. **The
recommendation is to raise eezo's floor from JDK 21 to JDK 25 (the current LTS,
which contains JEP 491), and to say plainly in the docs that this is why.** That
is a change to an "inherited without debate" decision, which is why it is
flagged here rather than buried: this ticket is where the evidence surfaced.
This is independent of the server choice. It is true for Jetty, Helidon and the
JDK server alike.

### 1.3 Jetty's WebSocket write path queues without bound and reports nothing; Helidon's blocks. Both defaults are wrong for eezo, in opposite directions

eezo's LiveView runtime pushes diffs to thousands of long-lived connections. The
question that decides whether that is safe is what happens when **one** client
stops reading. Measured, with a client that completes the RFC 6455 handshake and
then never reads another byte, while the server offers 60 KB text frames:

| | frames offered | frames actually written | failures reported | heap growth | writer blocked? |
|---|---|---|---|---|---|
| **Jetty 12.1.11**, defaults | 5000 | **10** | **0** | **+293.6 MiB** | no (max call 7 ms) |
| **Jetty**, `maxOutgoingFrames=32` | 43 | 10 | 1 | +1.9 MiB | no |
| **Helidon 4.5.1**, defaults | 11 | 11 | 0 | negligible | **yes, indefinitely** |

Jetty's default is **unbounded silent accumulation**: 286 MiB of payload offered
from a single connection, ten frames on the wire, not one error raised, session
still `isOpen()`. This is not my inference. It is Jetty's documented default.
[`Configuration.getMaxOutgoingFrames()`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/Configuration.java):

> The default value is -1, this indicates there is no limit on how many frames
> can be queued to be sent by the implementation.

and [`WebSocketConstants`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/WebSocketConstants.java):

```java
public static final int DEFAULT_MAX_OUTGOING_FRAMES = -1;
```

**The prototype has this bug today.** `../skiff`, on Jetty 12.0.16, pushes every
LiveView patch with
`s.sendText(message, WsCallback.NOOP)`
(`modules/runtime/src/main/scala/skiff/runtime/Server.scala:186`)
and never calls `setMaxOutgoingFrames`, `setMaxTextMessageSize`,
`setMaxFrameSize` or `setIdleTimeout` anywhere in the repository. So every write
failure is discarded and every stalled client accumulates without limit. That is
evidence the trap is real and easy to fall into, not a hypothetical.

Helidon's opposite default is not obviously better. `WsSession.send` blocked for
over 15 seconds and never returned; a second concurrent writer on the same
session also blocked, with no exception (so writes are serialised, not
corrupted). This is Helidon's documented design:
[`ListenerConfigBlueprint`](https://github.com/helidon-io/helidon/blob/helidon-4.x/webserver/webserver/src/main/java/io/helidon/webserver/ListenerConfigBlueprint.java)
declares `@Option.DefaultInt(0) int writeQueueLength()`, i.e. zero queue and synchronous
writes. There is **no timeout and no way to observe the backlog**. One
unresponsive client parks the pushing thread forever.

### 1.4 Helidon cannot be hot-reloaded in the same JVM, and Jetty can: the leak, attributed to a specific line of Helidon's source

Given that edit-to-visible under three seconds is stated as existential, this is
close to decisive on its own.

The test: boot the server inside a throwaway `URLClassLoader` whose parent is the
platform loader, serve a request, stop it, drop every reference, force GC, and
ask whether a `WeakReference` to the loader clears. That is exactly what a
classloader-swapping dev loop does on every edit.

```
JettyBoot   iteration 1..4: loaders still reachable = 0 / 1 .. 0 / 4
JettyBoot   FINAL: loaders still reachable after aggressive GC = 0 / 4
JettyBoot   => classloader RELEASED: classloader-swap hot reload is viable.

HelidonBoot iteration 1..4: loaders still reachable = 1 / 1 .. 4 / 4
HelidonBoot FINAL: loaders still reachable after aggressive GC = 4 / 4
HelidonBoot => classloader RETAINED: classloader-swap hot reload leaks.
```

Helidon retains **every** loader. The cause is a leaked platform thread, and it
is exactly one per `WebServer` instance. Measured, perfectly linear to ten
instances, each one created and stopped before the next:

```
after  1 server instances: idle-connection-timer=1  totalThreads=9
after  5 server instances: idle-connection-timer=5  totalThreads=13
after 10 server instances: idle-connection-timer=10 totalThreads=18
```

The source attribution is unambiguous. `io.helidon.webserver.ServerListener.startIt()`
constructs `new IdleTimeoutHandler(java.util.Timer, ListenerConfig, Supplier)`
and calls `.start()`; `IdleTimeoutHandler extends java.util.TimerTask` and its
entire public surface is a constructor plus `run()` plus `start()`. **There is no
`stop()`, no `cancel()`, and `ServerListener` never calls `Timer.cancel()`.**
(Measured by disassembly of `helidon-webserver-4.5.1.jar`.) A live `Timer`
thread is a GC root, and it holds a `TimerTask` whose class was loaded by the
old loader, so it pins the entire previous application. I searched the Helidon
issue tracker and found **no open issue** describing this.

Separately, and independently: Helidon `WebServer` instances cannot be restarted
at all. Measured:

```
java.lang.IllegalStateException: Server cannot be stopped and restarted, please create a new server
    at io.helidon.webserver.LoomServer.start(LoomServer.java:141)
```

You must build a new instance, which is fine in itself, and is exactly what
leaks a thread.

---

## 2. Comparison table

Every cell is measured on this machine unless marked *(cited)*. Boot and restart
figures are medians of 7 runs on JDK 21.0.11.

| | **Jetty 12.1.11** | **Helidon 4.5.1** | **Undertow 2.4.2** | **Vert.x 5.1.5** | **Netty 4.2.16** | **JDK `HttpServer`** |
|---|---|---|---|---|---|---|
| **Handler thread** | virtual | virtual | XNIO I/O (platform) | event loop (platform) | event loop (platform) | virtual |
| **Virtual-thread model** | per **task**, `VirtualThreadPool` | per **connection**, one VT parked per socket | none on the request path | opt-in VT *verticles* only *(cited)* | none | per request, via `setExecutor` |
| **Blocking JDBC in a handler** | safe | safe | **blocks an I/O thread** | **blocks the event loop** | **blocks the event loop** | safe |
| **WebSocket** | yes, first-class | yes | yes (XNIO / JSR-356) | yes | yes (codec only) | **none at all** |
| **Blocking WS write** | via `Callback.Completable` (10-line wrapper), **with timeout** | native, **no timeout** | callback/XNIO | `Future`/`drainHandler` | raw `ChannelFuture` | n/a |
| **WS write backpressure default** | **unbounded queue, silent** | **blocks forever** | queue | `writeQueueFull` + `drainHandler` *(cited)* | manual | n/a |
| **WS idle timeout default** | **30 s**, per session | **5 min**, per listener, no per-session knob | configurable | configurable | manual | n/a |
| **WS max message default** | 64 KiB text / 64 KiB binary | 1 MiB frame, no message cap | configurable | configurable | manual | n/a |
| **Heap per idle WS connection** | **8.0 KiB** | **25.0 KiB** | not measured | not measured | not measured | n/a |
| **Boot (construct+start), JDK 21** | **64.0 ms** | 147.1 ms | 87.4 ms | 113.6 ms | 54.6 ms | **3.3 ms** |
| **Wall clock from JVM start** | **229 ms** | 311 ms | 250 ms | 287 ms | 218 ms | **162 ms** |
| **Restart on the same port** | **0.7 ms** | 1.9 ms (new instance required) | 1.2 ms | 0.3 ms | 0.6 ms | 0.2 ms |
| **Classloader released on swap** | **yes (0/4 retained)** | **no (4/4 retained)** | not measured | not measured | not measured | yes (JDK-internal) |
| **Thread leak per restart** | none | **1 platform thread per instance** | none | none | none | none |
| **Jars / total size** | **10 / 2.58 MiB** | 37 / 2.79 MiB | 15 / 3.53 MiB | 23 / **9.67 MiB** | 9 / 3.27 MiB | **0 / 0** |
| **Licence (from published POMs)** | **EPL-2.0 OR Apache-2.0** | Apache-2.0 | Apache-2.0 | **Apache-2.0 OR EPL-2.0** | Apache-2.0 | GPLv2+CPE |
| **Java baseline** (class-file major) | **17** (61) | **21** (65) | **17** (61) | **11** (55; MR-jar to 21) | **8** (52; MR-jar to 9) | n/a |
| **Releases in last 12 mo.** | monthly, dual-track | ~6 | ~6 | ~12 | ~14 | with the JDK |
| **Distinct committers, 12 mo.** | 22 | 33 | 34 | 24 | **79** | n/a |
| **Advisories affecting current major, 12 mo.** | **2 medium** | **0 (ever, in the GH DB)** | 5 (1 critical) | 2 medium | **13**, one WS-specific | n/a |

---

## 3. Method

Six boot rigs (`src/Boot*.java`), driven by `run.sh`, each: capture a thread
baseline, construct and start a server on a **fixed** port, probe it once to
learn the handler thread, then stop and rebuild on the **same** port five times,
then dump the surviving thread names. Seven repeats per JDK. The salvaged rigs
used port 0, which does not test rebinding, and created a fresh `HttpClient` per
probe, whose selector threads are never reclaimed, and that alone made every server
look like it leaked four threads per restart. Both were fixed (Appendix A.0).

WebSocket behaviour was probed with `src/SlowClient.java`, a hand-rolled RFC 6455
client that completes the handshake and then stops reading, forcing the server's
send buffer to fill. This is the only reliable way to reach a server's
backpressure regime; a well-behaved client never gets there.

Classloader retention was tested with `src/ClassLoaderSwap.java` plus a per-server
boot shim under `swap/`, loaded into a `URLClassLoader` parented to the platform
loader.

Connection-scale numbers used two JVMs (`ConnScaleJetty` / `ConnScaleHelidon`
serving, `ConnLoad` holding N connections open from a separate process), so the
client's own socket bookkeeping never lands on the server's heap.

---

## 4. Virtual threads, examined properly

### 4.1 What carries the request

**Jetty 12.1.11.** Jetty 12.1 ships a dedicated
`org.eclipse.jetty.util.thread.VirtualThreadPool` implementing `ThreadPool`,
which the docs describe as creating "only virtual threads (no platform threads)"
([Jetty 12.1 programming guide, threads](https://jetty.org/docs/jetty/12.1/programming-guide/arch/threads.html)).
This supersedes the older `QueuedThreadPool.setVirtualThreadsExecutor(...)` that
the salvaged rig and `../skiff` both use, and about which the same docs warn that
"Jetty cannot enforce that the Executor passed to `setVirtualThreadsExecutor` uses
virtual threads." `setMaxConcurrentTasks(0)` disables the built-in `Semaphore`
limiter. Measured with that configuration, the handler runs on
`VirtualThread[#33]/runnable@ForkJoinPool-1-worker-2`.

The important structural point is that Jetty's virtual threads are **per task**,
not per connection. Selectors remain on platform threads and only dispatch a
virtual thread when there is work. Measured at 5000 idle WebSocket connections,
Jetty's platform thread count did not move at all (25 → 25) and heap rose
4.53 → 44.39 MiB, i.e. **8.0 KiB per connection**. Replicated at 3000
connections: 8.2 KiB.

One configuration warning from Jetty's own docs is worth carrying into eezo's
defaults verbatim, because it is a foot-gun:

> The number of selectors must always be less than the number of carrier
> threads, to leave some of the carrier threads free to run virtual threads.

**Helidon 4.5.1.** Helidon's server class is literally called `LoomServer`, and
the docs state: "It uses virtual threads and can handle nearly unlimited
concurrent requests" ([Helidon 4 SE WebServer](https://helidon.io/docs/v4/se/webserver)).
Measured, the handler thread is
`VirtualThread[#40,[0x0a5d64fd 0x0e480e89] WebServer socket]`. The name encodes
the socket, because the model is **one virtual thread per connection**, parked in
a blocking read for the connection's whole life. `ExecutorsFactory` uses
`Executors.newVirtualThreadPerTaskExecutor()` and `Thread.ofVirtual()`
(disassembled).

Per-connection is the more elegant model and the more expensive one. A parked
virtual thread's continuation stack lives on the heap. Measured at 5000 idle
WebSocket connections, Helidon's heap rose 4.21 → 129.06 MiB, i.e. **25.0 KiB
per connection**, a bit over 3x Jetty. Replicated at 3000: 26.4 KiB. At eezo's
stated target of "thousands of long-lived stateful connections" this is the
difference between ~40 MiB and ~125 MiB of server overhead before eezo has
stored a single byte of LiveView state. Survivable on a VPS, but it is real, and
it scales with exactly the number eezo wants to grow.

**Undertow 2.4.2.** The handler runs on `XNIO-1 I/O-n` with
`exchange.isInIoThread() == true`. Undertow's threading is XNIO's, which predates
Loom by a decade. A `Thread.sleep` or JDBC call in that handler stalls one of a
small number of I/O threads shared across all connections. Undertow expects you
to call `exchange.dispatch(executor, handler)`, which you *can* point at a
virtual-thread executor, but that is precisely "a thread pool with a
virtual-thread executor bolted on", and it costs a hop per request.

**Vert.x 5.1.5.** The handler runs on `vert.x-eventloop-thread-0`. Vert.x 5 does
support virtual threads, but only as an alternative *verticle* deployment mode:
"A virtual thread verticle is just like a standard verticle but it's executed
using virtual threads, rather than using an event loop"
([Vert.x 5 core manual](https://vertx.io/docs/vertx-core/java/)), selected with
`setThreadingModel(ThreadingModel.VIRTUAL_THREAD)`. The event loop that owns the
socket is still a platform thread; the verticle is a second layer. For eezo that
is an entire concurrency framework (verticles, the event bus, `Future`) sitting
between eezo and the socket, to reach a threading model Jetty and Helidon give
directly.

**Netty 4.2.16.** The handler runs on `nioEventLoopGroup-3-1`. Netty is the
substrate the others are built on, not a server. Choosing it means eezo writes
its own HTTP lifecycle, its own WebSocket session management, its own
backpressure, and owns Netty's CVE cadence (§9), all for no threading benefit,
since Netty has no virtual-thread request model at all.

**JDK `com.sun.net.httpserver.HttpServer`.** `setExecutor(Executors.newVirtualThreadPerTaskExecutor())`
genuinely gives per-request virtual threads; measured,
`VirtualThread[#26]/runnable@ForkJoinPool-1-worker-1`. And it boots in 3.3 ms
with zero dependencies. It is disqualified for one reason only, in §5.6.

### 4.2 Pinning and the JDK baseline

Covered in §1.2 and it is the single most consequential measurement in this
document. Restating the operative conclusion: **JEP 491 landed in JDK 24, and on
JDK 21 a blocking call inside an uncontended `synchronized` block costs 16x.**
eezo's floor should move to **JDK 25 LTS**. Nothing about this depends on which
server is chosen.

Two secondary notes:

- Jetty's own docs carry a matching warning for the Java 19 to 23 window: "If you
  have less than N CPU cores in your system, then by default all carriers will be
  pinned … leaving no carrier to execute virtual threads, and therefore completely
  locking up your system."
- Raising the floor to 25 does **not** cost eezo anything on the server axis.
  Measured from class-file major versions in the published jars, every candidate
  targets Java 21 or lower. Jetty 12.1 compiles to **major 61 (Java 17)**,
  Helidon 4.5 to **major 65 (Java 21)**, Undertow to 17, Vert.x to 11 (with
  multi-release classes up to 21), Netty to 8. Nothing is excluded by moving up.
  Note that Jetty's *bytecode* target of 17 does not mean eezo can run on 17:
  `VirtualThreadPool` needs a JVM with virtual threads, so 21 is the real floor
  regardless, and this ticket argues for 25.

Startup was measured on both JDKs and JDK 26 is very slightly *slower* across the
board (Jetty 64.0 → 68.7 ms, Helidon 147.1 → 157.9 ms). The differences are a few
percent of a budget dominated by JVM start, so raising the floor is free in
reload terms.

---

## 5. WebSocket, in depth

This is the hard requirement, so it gets the most space.

### 5.1 Jetty 12.1: exact API shape

```java
public interface Session extends Configurable, Closeable {
  void demand();
  void sendText(String, Callback);
  void sendPartialText(String, boolean, Callback);
  void sendBinary(ByteBuffer, Callback);
  void sendPartialBinary(ByteBuffer, boolean, Callback);
  void sendPing(ByteBuffer, Callback);
  void sendPong(ByteBuffer, Callback);
  void close(int, String, Callback);
  void disconnect();
  boolean isOpen();
  UpgradeRequest getUpgradeRequest();
  void addIdleTimeoutListener(Predicate<WebSocketTimeoutException>);
  // ... plus everything in Configurable, below
}

public interface Session.Listener {
  default void onWebSocketOpen(Session session);
  default void onWebSocketText(String message);
  default void onWebSocketBinary(ByteBuffer payload, Callback callback);
  default void onWebSocketPartialText(String payload, boolean fin);
  default void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback);
  default void onWebSocketFrame(Frame frame, Callback callback);
  default void onWebSocketPing(ByteBuffer payload);
  default void onWebSocketPong(ByteBuffer payload);
  default void onWebSocketError(Throwable cause);
  default void onWebSocketClose(int statusCode, String reason);
}
```

Every lifecycle event eezo needs is a distinct method: open, text, close (with
code and reason), error. Measured, all of them run on a **virtual** thread.
Registration:

```java
WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server);
ws.configure(container -> container.addMapping("/live", (rq, rs, cb) -> new MyEndpoint()));
ws.setHandler(ordinaryHttpHandler);   // non-upgrade traffic falls through
server.setHandler(ws);
```

**Read backpressure is explicit and this is a genuine advantage.** Implementing
`Session.Listener` means no further message is delivered until you call
`session.demand()`. The docs:

> In the case you want to implement the `Session.Listener` interface, remember
> that you have to explicitly demand to receive the next WebSocket event. Use
> `Session.Listener.AutoDemanding` to automate the demand for simple use cases.

([Jetty 12.1 WebSocket server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html))

For eezo this matters: a LiveView client that fires events faster than the server
can apply them should be throttled, not queued. `AutoDemanding` throws that away.
**eezo should implement `Session.Listener`, not `AutoDemanding`, and call
`demand()` after each event is fully processed.** All the rigs here use
`AutoDemanding` for simplicity; production eezo should not.

### 5.2 Jetty: the callback API does not infect eezo's direct style

The ticket's sharpest WebSocket question is whether Jetty's callback API forces
eezo into continuation style. **It does not**, and the escape hatch is in Jetty's
own API rather than something eezo has to invent:
`org.eclipse.jetty.websocket.api.Callback.Completable extends CompletableFuture<Void> implements Callback`.

Measured, against the same never-reading client:

```
blocking-adapter: sent=11 stillBlocked=true err=none maxCall=5106 ms heapUsed=5.6 MiB
with-timeout(500ms): CompletionException / TimeoutException after 503 ms
```

That is, `Callback.Completable.with(cb -> session.sendText(msg, cb)).join()`
blocks at frame 11, **the identical point where Helidon's native blocking send
blocks**, with heap flat at 5.6 MiB instead of 293 MiB. And `.orTimeout(...)`
works, firing at 503 ms. Helidon has no equivalent.

So the three regimes eezo can choose from, per call site:

| eezo wants | Jetty | Helidon |
|---|---|---|
| block until on the wire | `Completable.with(…).join()` | `send(msg, true)` |
| block, but give up on a stalled client | `.orTimeout(d).join()` | **impossible** |
| never block; drop or coalesce if backed up | `setMaxOutgoingFrames(n)` + fail callback | **impossible** |
| unbounded fire-and-forget | default (**do not use**) | impossible |

The concrete wrapper eezo should ship, in direct style, is small enough to write
out here so #14 and #23 can build against it:

```scala
opaque type WsConn = org.eclipse.jetty.websocket.api.Session

object WsConn:
  extension (c: WsConn)
    /** Blocks until the frame is on the wire. Applies real backpressure.
      * Throws if the connection failed or the deadline passed. */
    def send(text: String, within: Duration = 10.seconds): Unit =
      Callback.Completable
        .`with`(cb => c.sendText(text, cb))
        .orTimeout(within.toMillis, MILLISECONDS)
        .join()

    /** Never blocks. Returns false when the outgoing queue is full, so the
      * caller can coalesce diffs instead of buffering them. Requires
      * setMaxOutgoingFrames(n) at open time. */
    def offer(text: String): Boolean = ...

    def isOpen: Boolean = c.isOpen
    def close(code: Int, reason: String): Unit = ...
```

Because `join()` on a virtual thread parks rather than pins, this is
virtual-thread-native. Nothing about `CompletableFuture` leaks into eezo's public
API.

### 5.3 Jetty: configuration knobs and their defaults

Measured at runtime, and confirmed against
[`WebSocketConstants`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/WebSocketConstants.java):

```
idleTimeout=PT30S  maxFrameSize=65536  maxTextMessageSize=65536
maxBinaryMessageSize=65536  maxOutgoingFrames=-1  autoFragment=true
outputBufferSize=4096  inputBufferSize=4096
```

Three of these are wrong for eezo out of the box:

1. **`idleTimeout = 30 s`.** A LiveView connection with no user activity is
   closed after 30 seconds unless something is sent. eezo must either raise this
   or ship a heartbeat. `../skiff` does neither. It never calls
   `setIdleTimeout`, and its only client-side timer is a reconnect loop in the
   *dev* client (`skiff-dev-client.js:55`). Its production LiveView connections
   are therefore being closed and re-established on idle. Whether that is
   tolerable is #23's call, but it should be a decision rather than an accident.
2. **`maxTextMessageSize = 64 KiB`.** A large initial LiveView render or a big
   patch batch exceeding 64 KiB is rejected. eezo must set this deliberately, and
   #23 must decide whether patches are chunked or the limit is raised.
3. **`maxOutgoingFrames = -1`.** §1.3. eezo must set a bound, or use the blocking
   adapter, or both.

All are per-session (`Session extends Configurable`) or per-container, so eezo
can set them centrally and expose only what users need.

### 5.4 Helidon 4.5: exact API shape

```java
public interface WsListener {
  default void onOpen(WsSession session);
  default void onMessage(WsSession session, String text, boolean last);
  default void onMessage(WsSession session, BufferData buffer, boolean last);
  default void onPing(WsSession session, BufferData data);
  default void onPong(WsSession session, BufferData data);
  default void onClose(WsSession session, int status, String reason);
  default void onError(WsSession session, Throwable t);
  default Optional<Headers> onHttpUpgrade(HttpPrologue p, Headers h) throws WsUpgradeException;
}

public interface WsSession {
  WsSession send(String text, boolean last);      // blocking, returns this
  WsSession send(BufferData data, boolean last);  // blocking
  WsSession ping(BufferData data);
  WsSession pong(BufferData data);
  WsSession close(int status, String reason);
  WsSession terminate();
  Optional<String> subProtocol();
  SocketContext socketContext();
}
```

This is a genuinely nicer surface for direct style: `send` is blocking and
returns `this`, and `onHttpUpgrade` gives a clean hook for auth at upgrade time,
which Jetty expresses less directly through the upgrade lambda's
`UpgradeRequest`. Measured, an out-of-band push from an unrelated virtual thread
returned in 0.036 ms.

The configuration surface is the problem. The only WebSocket-level knob is
`WsConfig.maxFrameLength`, measured at **1 048 576** (1 MiB). There is **no
per-session idle timeout, no max message size, no outgoing-queue control**.
Everything else comes from the listener-wide `ListenerConfig`
([source](https://github.com/helidon-io/helidon/blob/helidon-4.x/webserver/webserver/src/main/java/io/helidon/webserver/ListenerConfigBlueprint.java)):

```java
@Option.DefaultInt(0)      int writeQueueLength();      // 0 = synchronous writes
@Option.DefaultBoolean(false) boolean smartAsyncWrites();
@Option.DefaultInt(4096)   int writeBufferSize();
@Option.Default("PT5M")    Duration idleConnectionTimeout();
@Option.Default("PT2M")    Duration idleConnectionPeriod();
@Option.DefaultInt(-1)     int maxTcpConnections();
@Option.DefaultInt(-1)     int maxConcurrentRequests();
@Option.DefaultInt(1024)   int backlog();
```

The 5-minute idle default is friendlier to LiveView than Jetty's 30 seconds. But
it is **listener-wide**, so eezo cannot give WebSocket connections a different
idle policy from ordinary HTTP keep-alives on the same port, which is precisely
what a LiveView server wants.

### 5.5 Server-to-client push outside a request

Both work, and both were measured, because this is the whole LiveView premise:
hold the session object from `onOpen` in eezo's own registry and write to it from
any thread later.

- **Jetty**: `pushSession.sendText("push-from-outside", Callback.from(…))`,
  measured completing on an unrelated virtual thread.
- **Helidon**: `pushSession.send("push-from-outside", true)`, measured returning
  in 0.036 ms on an unrelated virtual thread.

Neither imposes a "must write from the reading thread" restriction, which some
WebSocket stacks do. Note that Helidon's *HTTP* response API does, since the docs
state one of the `send` variants "MUST be invoked in the same thread the request
is started in". But that is HTTP, not WebSocket.

### 5.6 The JDK's own server, and why it is out

`com.sun.net.httpserver` has **no WebSocket support of any kind**. The JDK ships
a WebSocket *client* (`java.net.http.WebSocket`, JEP 321) and no server
counterpart. Implementing RFC 6455 framing, masking, continuation, close
handshake, ping/pong and permessage-deflate on top of `HttpExchange` is not a
weekend, and it would be eezo's code to keep correct and secure forever. Given
that WebSocket is stated as a hard requirement, this ends the JDK server's
candidacy despite it winning boot time by 20x and dependency weight outright.

Worth recording for a different ticket: the JDK server is an excellent choice for
a **dev-mode control channel** or a health endpoint, precisely because it costs
3.3 ms and zero jars.

### 5.7 Scala-native options, named and dismissed

- **Cask** (`com.lihaoyi::cask`, latest `0.11.3`, published 2025-10-18) is built
  directly on **Undertow 2.3.18.Final** (confirmed from its published POM). It
  therefore inherits §1.1's disqualification exactly, and its most recent release
  is nine months old.
- **http4s Ember** (`org.http4s::http4s-ember-server`, latest `1.0.0-M47`) is a
  cats-effect `IO` server. It is the precise opposite of eezo's direct style, and
  1.0.0 has been in milestone since 2020.
- **Tapir's server backends** (`tapir-jdkhttp-server` etc., `1.13.29`) are
  interpreters over someone else's server, not a server, and the JDK-HTTP backend
  inherits §5.6.
- **Ox** and **Gears** are ruled out by the ticket and are not servers anyway.

There is no Scala-native JVM HTTP server that changes this decision. eezo will be
wrapping a Java server whatever it does, which is the right outcome. A thin,
opinionated Scala 3 face over a boring Java core is the framework's whole thesis.

---

## 6. Startup time

Medians of 7 runs, fixed port, JDK 21.0.11. `construct+start` is the time eezo
controls; `wall clock from JVM start` includes JVM boot and class loading and is
the number that actually lands in the reload budget.

| | construct+start | min to max | wall clock from JVM start | restart on same port |
|---|---|---|---|---|
| JDK `HttpServer` | **3.3 ms** | 3.2 to 4.0 | **162 ms** | 0.2 ms |
| Netty | 54.6 ms | 52.0 to 64.6 | 218 ms | 0.6 ms |
| **Jetty** | **64.0 ms** | 60.7 to 76.3 | **229 ms** | **0.7 ms** |
| Undertow | 87.4 ms | 77.7 to 96.1 | 250 ms | 1.2 ms |
| Vert.x | 113.6 ms | 104.8 to 135.5 | 287 ms | 0.3 ms |
| **Helidon** | **147.1 ms** | 132.2 to 168.9 | **311 ms** | 1.9 ms |

Two observations that matter more than the ranking.

**First, none of these numbers threatens a three-second budget on their own.**
The spread between best and worst realistic candidate is 83 ms of a 3000 ms
budget, under 3%. Anyone arguing for a server on startup grounds is optimising
the wrong term; `research/capture-checking.md` §5.3 already measured incremental
Scala recompilation at ~0.5 s for an 80-file project, and that is where the
budget goes.

**Second, and this is the number that actually matters for reload:** the
restart-on-the-same-port column. If eezo's dev loop can swap a classloader
instead of forking a JVM, the cost of bringing the server back is **0.7 ms**, not
229 ms. That saves the whole JVM-start term, which is 165 ms of Jetty's 229 ms.
Which is exactly why §1.4's classloader result is load-bearing: Helidon forfeits
that entire strategy.

Helidon's own log is worth quoting against its own number, because it shows how
vendor startup claims are constructed:

```
Started all channels in 14 milliseconds. 242 milliseconds since JVM startup.
```

Fourteen milliseconds is real, and it is the last 10% of the work. The other
133 ms is builder construction, service-registry initialisation and feature
scanning, which happen before "channels" exist. My 147.1 ms figure brackets the
whole thing, which is what eezo's reload budget will feel.

---

## 7. Dependency weight

Resolved transitively from a clean fetch, so these are the jars that actually
land on disk and in a fat jar.

| | jars | total | notable transitives |
|---|---|---|---|
| **JDK `HttpServer`** | **0** | **0** | none |
| **Netty** (`netty-codec-http`) | 9 | 3.27 MiB | all `io.netty` |
| **Jetty** (`jetty-server` + `jetty-websocket-jetty-server`) | **10** | **2.58 MiB** | **`slf4j-api` only** |
| **Undertow** (`undertow-core`) | 15 | 3.53 MiB | XNIO, JBoss Threads, WildFly Common, 7 SmallRye modules |
| **Vert.x** (`vertx-core`) | 23 | **9.67 MiB** | 13 Netty modules, **and both Jackson 3.1.5 and Jackson 2.21.5** |
| **Helidon** (`helidon-webserver` + `-websocket`) | 37 | 2.79 MiB | 35 `io.helidon.*` modules incl. config, metrics-api, service-registry |

Jetty is the outright winner on this axis, and the shape matters more than the
totals. **Jetty's entire non-Jetty footprint is one MIT-licensed logging facade.**
Helidon's byte count is nearly as small, but it arrives as 37 artifacts including
a config system, a metrics API and a service registry that eezo does not want and
cannot easily exclude. And it is the service registry, resolved through the
*thread context classloader*, that makes Helidon fail to boot at all under a
custom loader unless the TCCL is set (measured, §1.4).

Vert.x shipping two major versions of Jackson simultaneously is a version-conflict
hazard for any user who also uses Jackson.

---

## 8. Licences

Resolved by walking each artifact's POM parent chain on Maven Central, which is
the metadata that actually governs redistribution.

| Artifact | Declared licence(s) | SPDX | Declared at |
|---|---|---|---|
| `org.eclipse.jetty:jetty-server:12.1.11` | EPL 2.0 **and** Apache 2.0 | `EPL-2.0 OR Apache-2.0` | `org.eclipse.jetty:jetty-project` |
| `org.eclipse.jetty.websocket:*:12.1.11` | same | `EPL-2.0 OR Apache-2.0` | same |
| `org.slf4j:slf4j-api:2.0.17` | MIT | `MIT` | `slf4j-bom` |
| `io.helidon.webserver:*:4.5.1` | Apache 2.0 | `Apache-2.0` | `io.helidon:helidon-parent` |
| `io.undertow:undertow-core:2.4.2.Final` | Apache 2.0 | `Apache-2.0` | `undertow-parent` |
| `org.jboss.xnio:xnio-api`, `org.wildfly.common`, `org.jboss.logging` | Apache 2.0 | `Apache-2.0` | own POMs |
| `io.vertx:vertx-core:5.1.5` | Apache 2.0 **and** EPL 2.0 | `Apache-2.0 OR EPL-2.0` | `vertx-core-aggregator` |
| `io.netty:*:4.2.16.Final` | Apache 2.0 | `Apache-2.0` | `netty-parent` |
| `tools.jackson.core:jackson-databind:3.1.5` | Apache 2.0 | `Apache-2.0` | own POM |

**Nothing here constrains eezo's own licence choice.** There is no CDDL, no LGPL,
no GPL, no copyleft anywhere in any candidate's transitive tree. The two
dual-licensed projects, Jetty and Vert.x, are both `X OR Apache-2.0`, and a
redistributor may simply elect Apache-2.0 and ignore the EPL branch entirely,
which sidesteps EPL-2.0's source-availability and secondary-licence provisions.

**Message for [#11 (Licence)](https://github.com/rcardin/eezo/issues/11): the
server choice constrains you not at all.** eezo may be MIT, Apache-2.0, BSD, MPL,
or a copyleft licence, and any of the six candidates remains compatible. If eezo
chooses Jetty, the one obligation is to carry Jetty's `NOTICE` and state that
Jetty is used under Apache-2.0 (the elected branch of its dual licence). That is
a paragraph in a `THIRD-PARTY` file, not a design constraint.

---

## 9. Maintenance health

From the GitHub REST API on 2026-07-27. "Committers" counts distinct commit
authors on the default branch since 2025-07-27, sampled up to 600 commits.

| | latest release | cadence | committers (12 mo.) | open issues | archived |
|---|---|---|---|---|---|
| **Jetty** | 12.1.11 (2026-07-07) | monthly, **two supported lines in lockstep** (12.0.x + 12.1.x, released the same day, every month, back through 12.1.8/12.0.34) | 22 | 295 | no |
| **Helidon** | 4.5.1 (2026-07-21) | ~6/yr, 4.x and 3.x released together | 33 | 563 | no |
| **Undertow** | 2.4.2.Final (2026-07-10, Maven; GitHub releases page lags at 2.4.1) | ~6/yr | 34 | 45 | no |
| **Vert.x** | 5.1.5 (2026-07-20, Maven; **no GitHub releases published at all**) | ~monthly | 24 | 218 | no |
| **Netty** | 4.2.16 / 4.1.136 (2026-07-07) | ~monthly, two lines | **79** | 665 | no |

All five are alive. Jetty's dual-line monthly discipline is the most legible
release process of the group and is what you want from a dependency you cannot
easily fork.

### Advisories

From the GitHub Security Advisories API, filtered to each candidate's current
major line and the last twelve months. Named with IDs as the ticket asks.

**Jetty 12.1.x: two, both medium, both patched.**
- [GHSA-7p3p-8qv8-m2vh / CVE-2026-6790](https://github.com/advisories/GHSA-7p3p-8qv8-m2vh), HTTP Authority/Host mismatch, affects `>= 12.1.0, <= 12.1.8`, patched **12.1.9**.
- [GHSA-f4v5-65jj-pcr2 / CVE-2026-10051](https://github.com/advisories/GHSA-f4v5-65jj-pcr2), **cross-request leakage for trailers on HTTP/1.1 keep-alive connections**, affects `>= 12.1.0, <= 12.1.9`, patched **12.1.10**.
- (12.0.x line: [GHSA-xxh7-fcf3-rj7f / CVE-2026-1605](https://github.com/advisories/GHSA-xxh7-fcf3-rj7f), high, gzip request memory leak, patched 12.0.32.)

The trailer-leakage one is the more interesting of the two for eezo, because it
is a cross-request data leak on keep-alive connections and eezo will sit behind
Caddy, which uses keep-alive to the origin aggressively. **12.1.11 is clear of
both.** No advisory has ever been filed against
`org.eclipse.jetty.websocket:jetty-websocket-core-common`.

**Helidon: zero advisories in the GitHub database, ever, for
`io.helidon.webserver:helidon-webserver`.** I want to flag this honestly rather
than score it as a win. Oracle publishes security fixes through its own quarterly
Critical Patch Update process, and absence from GitHub's database is weak
evidence of absence of vulnerabilities. Helidon 4's Níma server core is also
young (a from-scratch HTTP/1.1 and HTTP/2 implementation first shipped in 2023),
whereas Jetty's parser has been attacked continuously since 1998. The two
advisories against Jetty 12.1 are evidence that people are *looking*.

**Undertow: five in the window**, including
[GHSA-j382-5jj3-vw4j / CVE-2025-12543](https://github.com/advisories/GHSA-j382-5jj3-vw4j)
(**critical**, Host header not validated) and
[GHSA-95h4-w6j8-2rp8 / CVE-2025-9784](https://github.com/advisories/GHSA-95h4-w6j8-2rp8)
(high, MadeYouReset HTTP/2 DDoS). Undertow has by far the heaviest historical
advisory traffic of the six.

**Vert.x: two, both medium.**
[CVE-2026-6860](https://github.com/advisories/GHSA-3g76-f9xq-8vp6) (unbounded SNI
cache growth, patched 5.0.12) and
[CVE-2026-1002](https://github.com/advisories/GHSA-cphf-4846-3xx9).

**Netty: thirteen in the window**, seven of them published on a single day
(2026-07-22), including one directly on the WebSocket path:
[GHSA-4mp9-239f-g9hg / CVE-2026-59898](https://github.com/advisories/GHSA-4mp9-239f-g9hg),
"WebSockets V07/V08 handshaker missing Connection/Upgrade validation". This is
not a criticism of Netty, which is the most scrutinised network library on the JVM
and patches fast. But it is a direct measure of what "use Netty directly"
would cost eezo in ongoing patch vigilance.

---

## 10. What sitting behind Caddy changes

Confirmed against the
[Caddy `reverse_proxy` documentation](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy):

> The proxy also supports WebSocket connections, performing the HTTP upgrade
> request then transitioning the connection to a bidirectional tunnel.

**It imposes nothing on the origin.** No special headers, no
`Connection: Upgrade` forwarding configuration, no HTTP/2-to-origin requirement.
eezo's server needs plain HTTP/1.1 on localhost and nothing else.

Two consequences, one in each direction.

**It lowers the bar substantially.** TLS, ALPN, HTTP/2 and HTTP/3 are Caddy's
problem. Every candidate's TLS story, certificate handling and ALPN support
becomes irrelevant to the ranking, which removes what would otherwise be one of
Jetty's largest advantages over the JDK server and one of Netty's over everyone.
It also means the `jetty-alpn-*` and `jetty-http2-*` modules never enter eezo's
dependency tree, so the 10-jar / 2.58 MiB figure in §7 is the real shipping
footprint, not a stripped-down one.

**It adds two knobs eezo's deploy templates must set**, which belong to the
deploy ticket but surfaced here:

- Caddy **forcibly closes WebSocket connections on config reload** by default.
  `stream_close_delay` (the docs suggest `5m` as a reasonable starting point)
  keeps LiveView sessions alive across a Caddy reload.
- `stream_timeout` defaults to no timeout, which is what eezo wants; it must not
  be set to a small value or every LiveView connection dies on a fixed schedule.

Neither changes the server ranking. Both should be pre-seeded in the Caddyfile
that `research/deploy-target.md` describes.

---

## 11. In-process restart and hot reload

The ticket asks whether each server can be stopped and a fresh one started in the
same JVM without leaking threads, ports or classloaders. Measured, five
stop/rebuild cycles on a fixed port per run, seven runs:

| | rebinds same port | restart cost | threads returned to baseline | classloader released |
|---|---|---|---|---|
| **Jetty** | yes, 35/35 | 0.7 ms | yes; residual is JVM-global VT infrastructure (`ForkJoinPool-1` carriers, `Read-Poller`/`Write-Poller`), not per-instance | **yes, 0/4 retained** |
| **Helidon** | yes, but only via a **new instance** (`stop(); start()` on the same instance throws) | 1.9 ms | **no: +1 platform thread per instance, forever** | **no, 4/4 retained** |
| Undertow | yes | 1.2 ms | yes, exactly | not measured |
| Vert.x | yes | 0.3 ms (server only) / 2.5 ms (full `Vertx` recreate) | yes | not measured |
| Netty | yes | 0.6 ms | yes | not measured |
| JDK | yes | 0.2 ms | yes | n/a |

The Jetty residual deserves a note so it is not misread as a leak: after five
restarts Jetty's process holds 12 `ForkJoinPool-1-worker-N` threads and four
`Read-Poller`/`Write-Poller`/`Read-Updater`/`Write-Updater` threads. All sixteen
are **JDK-global**: the default virtual-thread scheduler (bounded by
`availableProcessors`, 14 here) and the JDK's virtual-thread socket pollers. They
are created once and shared, not per server. The classloader test confirms it:
zero retention across four full cycles.

Helidon's is a leak, attributed to source in §1.4.

**This is the axis on which the two serious candidates genuinely diverge, and it
is the one eezo said is existential.** If eezo's dev loop swaps a classloader
(which [#6](https://github.com/rcardin/eezo/issues/6) and
[#16](https://github.com/rcardin/eezo/issues/16) own), Helidon leaks the entire
previous application on every keystroke-triggered reload, including every previous version
of every user class, every static, every cache. Fifty edits in a session is fifty
retained applications. The only remedy is to fork a JVM per reload, which
reinstates the ~165 ms of JVM start that §6 shows is the largest single
server-side term in the budget, and forfeits warm JIT state besides.

One practical detail for whoever builds that loop: **Helidon does not boot at all
under a custom classloader unless the thread context classloader is set to it**
(measured: the upgrade path returns no bytes and the client sees "HTTP/1.1
header parser received no bytes", because Helidon resolves protocol providers via
`ServiceLoader`). Jetty needs no such ceremony. That is a smaller point than the
leak but points the same way.

---

## 12. Recommendation

**Build eezo on Jetty 12.1, and raise eezo's JDK floor from 21 to 25.**

Concretely, the configuration eezo should standardise on:

```scala
val pool = VirtualThreadPool()
pool.setMaxConcurrentTasks(0)                 // no semaphore ceiling
val server = Server(pool)
val connector = ServerConnector(server)       // selectors < carriers; default is fine
connector.setPort(port)                       // localhost, plain HTTP, behind Caddy
val ws = WebSocketUpgradeHandler.from(server)
ws.configure { container =>
  container.setIdleTimeout(Duration.ofMinutes(5))   // NOT the 30s default
  container.setMaxTextMessageSize(1 * 1024 * 1024)  // NOT the 64 KiB default
  container.setMaxOutgoingFrames(64)                // NOT the -1 default
  container.addMapping("/eezo/live", …)             // Session.Listener, explicit demand()
}
ws.setHandler(eezoHttpHandler)
server.setHandler(ws)
```

Every one of those four "NOT the default" lines is a defect the prototype ships
today. They are the deliverable of this ticket as much as the server name is.

**So: does Jetty 12 still win? Yes, but not for the reasons Skiff picked it, and
not in the configuration Skiff uses.** Skiff uses `QueuedThreadPool` with a
virtual-thread executor (the weaker option that Jetty's own docs caveat),
`Callback.NOOP` on every LiveView push (unbounded silent queueing), and every
WebSocket default unchanged (30-second idle close, 64 KiB message cap). Jetty
wins on evidence Skiff never gathered: classloader release, per-connection heap,
and dependency shape. A prototype that chose right for weak reasons is still a
prototype that chose right, and re-deriving it independently is the useful
outcome here.

Ranked reasoning:

1. **Hot reload (§1.4, §11).** Jetty releases the classloader; Helidon retains it
   and leaks a platform thread per instance with no cancel path in its source and
   no issue filed. Against a constraint the project calls existential, this alone
   would decide it.
2. **WebSocket control (§5.2, §5.3).** Jetty's callback API looked like the
   objection and turns out to be the advantage: `Callback.Completable` gives eezo
   blocking-with-timeout, bounded non-blocking, or fire-and-forget, chosen per
   call site, all virtual-thread-native, in about ten lines of Scala. Helidon
   offers exactly one mode, blocking with no timeout and no visibility, and no
   per-session idle or size limits at all.
3. **Per-connection cost (§4.1).** 8.0 KiB versus 25.0 KiB per idle WebSocket
   connection, measured twice at two scales. For a server whose stated job is
   thousands of long-lived stateful connections, a 3x difference in the dominant
   resource is not a rounding error.
4. **Dependency shape (§7).** Ten jars, 2.58 MiB, and the only non-Jetty
   artifact is `slf4j-api`. Helidon's 37 modules include a config system, a
   metrics API and a service registry eezo does not want. And the service
   registry is what breaks classloader isolation.
5. **Startup (§6).** Jetty is 2.3x faster to boot than Helidon and second only to
   the JDK server among realistic candidates. This is the *least* important
   reason: the whole spread is 3% of the reload budget. Do not argue for Jetty on
   startup.

What Helidon is genuinely better at, stated plainly so this is not a hatchet job:
a nicer direct-style API (`send` returns `this`; `onHttpUpgrade` is a clean auth
hook), a 5-minute idle default that suits LiveView better than 30 seconds, a
per-connection threading model that is conceptually cleaner, and a zero-advisory
record. If the classloader leak were fixed and per-session WebSocket limits were
added, this would be a much closer call. As of 4.5.1 it is not close.

### Confidence

**Very high (97%) that Undertow, Vert.x and Netty are disqualified.** Measured
directly: the handler runs on a platform event-loop thread in all three, and this
is architectural rather than configurational. The only way I am wrong is if eezo
decides a dispatch hop per request is acceptable, which contradicts the project's
stated premise.

**Very high (95%) that the JDK `HttpServer` is disqualified.** It has no
WebSocket server support and WebSocket is a stated hard requirement. This is a
fact about the JDK, not a judgement.

**Very high (95%) that Helidon's classloader retention and per-instance thread
leak are real.** Measured with a `WeakReference` test across four cycles, a
linear leak count across ten instances, and attributed to specific bytecode with
no cancel path. The residual uncertainty is whether some Helidon configuration I
did not find disables `IdleTimeoutHandler` entirely. `ListenerConfig` exposes
`idleConnectionTimeout` and `idleConnectionPeriod` but nothing that skips
constructing the handler.

**High (92%) that Jetty's WebSocket write queue is unbounded by default and this
is a real OOM vector for eezo.** Measured (293.6 MiB of heap growth from a single
stalled connection) and confirmed against Jetty's own javadoc and
`WebSocketConstants`. The remaining 8% is whether a realistic LiveView diff
stream could ever reach that depth before the 30-second idle timeout fires. The
timeout is on *reads*, so I believe it cannot save you, but I did not measure the
interaction.

**High (90%) that eezo's JDK floor should move from 21 to 25.** The 16x pinning
measurement is unambiguous and JEP 491's delivery in JDK 24 is documented. The
uncertainty is entirely about eezo's audience: if the project has a hard reason to
support JDK 21 users, the mitigation is to document the hazard loudly rather than
to pretend it does not exist. I did *not* establish that any specific library eezo
will ship pins badly, since HikariCP and pgjdbc both came out clean enough (§1.2), so
this is a claim about the risk surface of users' code, not about eezo's own.

**High (88%) that Jetty is the right choice overall.** This aggregates five
independent measured axes that all point the same way. The residual is that I
weighted hot reload heavily on the strength of the project's own statement that
it is existential; if the dev loop ends up forking a JVM per reload for unrelated
reasons (which [#6](https://github.com/rcardin/eezo/issues/6) may well conclude),
reason 1 evaporates and the decision rests on reasons 2 to 4, which still favour
Jetty but by a much smaller margin. **This recommendation should be revisited if
#6 concludes that classloader swapping is not viable for Scala 3 anyway.**

**Medium-high (75%) on the per-connection memory figures.** Two servers, two
scales each, consistent within 5%. The caveat is that both were measured with
idle connections holding no application state, `-Xmx2g`, and a forced GC before
sampling; real LiveView sessions carry eezo's own state on top and the ratio may
compress.

**Medium (70%) that Helidon's clean advisory record reflects reality rather than
reporting.** Oracle publishes through its own CPU process. I could not
independently verify Helidon 4's vulnerability history from a source that would
have recorded one.

**Medium (65%) that `Session.Listener` with explicit `demand()` is the right
choice over `AutoDemanding` for eezo.** The read-backpressure argument is sound
in principle, but I did not measure a LiveView-shaped event storm, and explicit
demand is easy to get wrong in a way that silently stops delivering messages.
[#23](https://github.com/rcardin/eezo/issues/23) should decide this with a real
workload.

**Lower confidence (55%) on the claim that Jetty's 30-second idle default is
actively harmful rather than merely surprising.** It is measured and it is
certainly a change eezo must make deliberately. Whether the prototype's implicit
reliance on reconnect is a bug or an acceptable design is #23's judgement, not
mine.

---

## 13. Open items handed onward

**[#14: HTTP server, Request and Response, handler signature.](https://github.com/rcardin/eezo/issues/14)**
The concrete Jetty 12.1 surface to build against is
`Handler.Abstract.handle(Request, Response, Callback): Boolean`. Note that it
returns `Boolean` (handled / not handled) and takes a `Callback` that **must** be
completed exactly once, including on the exception path, which is where `../skiff`
does `callback.failed(t)`. eezo's `Handler` type should make double-completion and
non-completion unrepresentable. Also: `WebSocketUpgradeHandler` is a
`Handler.Wrapper`: the HTTP handler is installed as its child and non-upgrade
traffic falls through to it. The HTTP and WebSocket routing tables are therefore
not independent, and #14 should model that relationship rather than discover it.

**[#23: LiveView wire: structural diff, patch protocol, client runtime, reconnect.](https://github.com/rcardin/eezo/issues/23)**
Four things land squarely here:
1. **Pick a backpressure policy per push, and encode it in the API.** The three
   regimes are laid out in §5.2. My suggestion is bounded non-blocking with diff
   coalescing (if the queue is full, merge the pending diff into the next one)
   rather than blocking, because blocking converts one slow client into one stuck
   pusher.
2. **`maxTextMessageSize` is 64 KiB by default.** Decide whether patches are
   chunked or the limit is raised, and make the initial full render's size an
   explicit budget.
3. **Idle timeout is 30 s by default.** LiveView needs a heartbeat, a raised
   timeout, or a documented reconnect. Pick one deliberately; the prototype
   picked none.
4. **Explicit `demand()` vs `AutoDemanding`**, per §5.2 and the 65% confidence note.

**[#16: Build tool and dev-loop architecture](https://github.com/rcardin/eezo/issues/16)
and [#6: fast edit-to-running-code loops](https://github.com/rcardin/eezo/issues/6).**
The classloader-swap strategy is **viable with Jetty** (measured, 0/4 retained)
and **not viable with Helidon**. Restart cost on a warm JVM is 0.7 ms versus
229 ms for a fresh JVM, so the swap strategy is worth ~165 ms of the three-second
budget plus warm JIT. If #6 concludes classloader swapping is impractical for
Scala 3 for unrelated reasons, tell #3. It weakens the top reason for choosing
Jetty (see the 88% confidence note).

**[#10: measure edit-to-visible latency.](https://github.com/rcardin/eezo/issues/10)**
Two server-side constants for the budget, both measured here: **229 ms** wall
clock from JVM start to a Jetty server serving its first request, or **0.7 ms** if
the JVM is warm and only the server is rebuilt. The rigs in
`research/harnesses/bench/http/` can be reused directly.

**[#11: Licence.](https://github.com/rcardin/eezo/issues/11)**
The server choice constrains you not at all (§8). No copyleft anywhere in any
candidate's tree. If Jetty is chosen, elect the Apache-2.0 branch of its dual
licence and carry Jetty's `NOTICE`; that is the entire obligation.

**[#4: DB / query layer.](https://github.com/rcardin/eezo/issues/4)**
Two measurements made here belong to you. First, on JDK 21 a blocking call inside
a `synchronized` block costs 16x (§1.2). If #4 evaluates any JDBC driver other
than pgjdbc, count its `ACC_SYNCHRONIZED` methods on the query path before
adopting it (the one-liner is in Appendix A.6). Second, **HikariCP 7.0.2 has
three synchronized methods on the per-statement path**
(`ProxyConnection.trackStatement`, `.untrackStatement`, `.closeStatements`) and
**pgjdbc 42.7.8 is effectively clean** (all six of its synchronized methods are in
`LazyCleaner`, a background reaper). Neither blocks while holding its monitor, so
the risk is low, but on a JDK 21 floor it is not zero.

**A new item with no ticket: raise the JDK floor to 25.** This contradicts an
"inherited without debate" decision and belongs to whoever owns the project's
platform baseline. The evidence is §1.2. It costs nothing on the server axis
(no candidate targets above Java 21, and Jetty's `VirtualThreadPool` already
requires a JVM with virtual threads, so 21 was the floor anyway) and it makes
eezo's core claim, "just write blocking code", true instead of conditionally
true.

**A second new item: the deploy Caddyfile needs `stream_close_delay`.**
Belongs with `research/deploy-target.md`. Caddy closes WebSocket connections on
config reload by default (§10), which would drop every LiveView session on any
Caddy reconfiguration.

---

## Appendix A: reproduction

All rigs are preserved under
[`research/harnesses/bench/http/`](harnesses/bench/http/). Classpaths in
`cp_*.txt` were produced with `coursier fetch --classpath` on 2026-07-27 and are
absolute paths into the local Coursier cache; regenerate with the commands in
A.1 if they do not resolve.

### A.0 What was wrong with the salvaged rigs

Three bugs, all fixed:

1. **`Rig.probe` built a fresh `java.net.http.HttpClient` per probe.** Its
   selector and worker threads are never reclaimed, so every server appeared to
   leak exactly four threads per restart. That artefact is why the salvaged run's
   thread counts are meaningless. Now a single static client, warmed before the
   baseline is taken.
2. **Every rig bound to port 0.** That never tests rebinding the *same* port
   after `stop()`, which is the actual question. Now a fixed port passed as
   `args[0]`.
3. **Restart timing excluded construction** in some rigs and included it in
   others. Now uniformly `stop()` → construct → `start()` → probe.

Two candidates the salvage never covered (Vert.x, Netty) were added.

### A.1 Resolve classpaths

```bash
coursier fetch --classpath org.eclipse.jetty:jetty-server:12.1.11 \
  org.eclipse.jetty.websocket:jetty-websocket-jetty-server:12.1.11 > cp_jetty_ws.txt
coursier fetch --classpath io.helidon.webserver:helidon-webserver:4.5.1 \
  io.helidon.webserver:helidon-webserver-websocket:4.5.1 > cp_helidon_ws.txt
coursier fetch --classpath io.undertow:undertow-core:2.4.2.Final > cp_undertow.txt
coursier fetch --classpath io.vertx:vertx-core:5.1.5             > cp_vertx.txt
coursier fetch --classpath io.netty:netty-codec-http:4.2.16.Final > cp_netty.txt
```

### A.2 Boot, restart and handler-thread identity (§1.1, §6, §11)

```bash
./run.sh /path/to/temurin-21.0.11/Contents/Home 7 > logs/jdk21.log
./run.sh ~/.sdkman/candidates/java/26-tem       7 > logs/jdk26.log
```

Summarise with the parser in this document's history, or simply:

```bash
grep -E "cold-start|restart-|threads-after-final-stop|handler-thread" logs/jdk21.log
```

### A.3 Pinning (§1.2)

```bash
javac -cp "out:$(cat cp_jetty_ws.txt)" -d out src/PinTest.java
java -Djdk.tracePinnedThreads=short -cp "out:$(cat cp_jetty_ws.txt)" PinTest 18150 200   # JDK 21
java -cp "out:$(cat cp_jetty_ws.txt)" PinTest 18151 200                                  # JDK 24+
```

`/plain` and `/sync` differ only in whether the 400 ms sleep happens inside a
`synchronized` block on a fresh per-request lock.

### A.4 WebSocket behaviour and backpressure (§1.3, §5)

```bash
java -cp "out:$(cat cp_jetty_ws.txt)"   WsJetty          18100   # defaults, push, backpressure
java -cp "out:$(cat cp_helidon_ws.txt)" WsHelidon        18101
java -Xmx2g -cp "out:$(cat cp_jetty_ws.txt)" WsJettyQueue 18120 -1   # unbounded
java -Xmx2g -cp "out:$(cat cp_jetty_ws.txt)" WsJettyQueue 18121 32   # bounded
java -Xmx2g -cp "out:$(cat cp_jetty_ws.txt)" WsJettyBlocking 18270   # Callback.Completable
```

`src/SlowClient.java` is the RFC 6455 client that handshakes and then stops
reading; it is what forces the servers into their backpressure regimes.

### A.5 Classloader retention and the Helidon leak (§1.4, §11)

```bash
javac -cp "$(cat cp_jetty_ws.txt)"   -d swapout/jetty   swap/JettyBoot.java
javac -cp "$(cat cp_helidon_ws.txt)" -d swapout/helidon swap/HelidonBoot.java
java -cp out ClassLoaderSwap JettyBoot   swapout/jetty   "$(cat cp_jetty_ws.txt)"   18140 4
java -cp out ClassLoaderSwap HelidonBoot swapout/helidon "$(cat cp_helidon_ws.txt)" 18141 4
java -cp "out:$(cat cp_helidon_ws.txt)" HelidonLeak 18130 10
```

`ClassLoaderSwap` sets the thread context classloader during boot; without that
Helidon does not serve at all.

### A.6 Connection scale (§4.1) and the synchronized census (§1.2)

Two JVMs, so the client's sockets do not pollute the server's heap:

```bash
java -Xmx2g -cp "out:$(cat cp_jetty_ws.txt)" ConnScaleJetty 18210 &
java -cp out ConnLoad 18210 5000 /
java -Xmx2g -cp "out:$(cat cp_helidon_ws.txt)" ConnScaleHelidon 18211 &
java -cp out ConnLoad 18211 5000 /stats
```

Synchronized-method census for any jar:

```bash
mkdir -p /tmp/xj && (cd /tmp/xj && unzip -q "$JAR" '*.class')
cls=$(cd /tmp/xj && find . -name '*.class' | sed 's|^\./||;s|\.class$||;s|/|.|g')
echo "$cls" | xargs javap -p -c -cp /tmp/xj 2>/dev/null > /tmp/xj.txt
grep -cE '^  [a-z ]*synchronized ' /tmp/xj.txt
grep -c monitorenter /tmp/xj.txt
```

### A.7 Java baseline of each artifact (§2, §4.2)

Read the class-file major version rather than trusting release notes. Note that
`unzip -p … | xxd` is easy to misread across a multi-jar loop, so do it in one
process:

```python
import zipfile, struct
z = zipfile.ZipFile(jar)
minor, major = struct.unpack('>HH', z.read('org/eclipse/jetty/server/Server.class')[4:8])
# 52=Java 8, 55=11, 61=17, 65=21, 69=25
# also scan every entry for the max, to catch multi-release jars
```

### A.8 Negative results, preserved deliberately

`src/AnonProbe.java` and `src/IdleProbe.java` exist because a transient HTTP 500
on WebSocket upgrade appeared during the connection-scale work and I suspected
either anonymous `Session.Listener.AutoDemanding` implementations or
`Session.setIdleTimeout` inside `onWebSocketOpen`. **Neither reproduces**: all
four combinations upgrade cleanly. The claim was therefore dropped from this
document rather than published. The rigs are kept so nobody re-derives the false
lead.

---

## Appendix B: sources

**Primary, project source and documentation**
- Jetty 12.1: [`WebSocketConstants.java`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/WebSocketConstants.java), [`Configuration.java`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/Configuration.java), [`WebSocketCoreSession.java`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/WebSocketCoreSession.java), [`FrameFlusher.java`](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-websocket/jetty-websocket-core-common/src/main/java/org/eclipse/jetty/websocket/core/internal/FrameFlusher.java)
- Jetty 12.1 programming guide: [threads / virtual threads](https://jetty.org/docs/jetty/12.1/programming-guide/arch/threads.html), [server WebSocket](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)
- Helidon 4: [`ListenerConfigBlueprint.java`](https://github.com/helidon-io/helidon/blob/helidon-4.x/webserver/webserver/src/main/java/io/helidon/webserver/ListenerConfigBlueprint.java), [SE WebServer docs](https://helidon.io/docs/v4/se/webserver)
- Vert.x 5: [core manual](https://vertx.io/docs/vertx-core/java/)
- Caddy: [`reverse_proxy` directive](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
- OpenJDK: [JEP 491, Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)

**Primary, registries and APIs (all queried 2026-07-27)**
- Maven Central `maven-metadata.xml` for every candidate (current versions), and each artifact's POM parent chain (licences)
- GitHub REST API: `/repos/{owner}/{repo}` (licence, activity), `/releases` (cadence), `/commits?since=` (committer counts)
- GitHub Security Advisories API: `/advisories?ecosystem=maven&affects=…`

**Primary, disassembly of published artifacts**
`helidon-webserver-4.5.1.jar` (`ServerListener`, `IdleTimeoutHandler`,
`LoomServer`, `ExecutorsFactory`), `jetty-websocket-jetty-api-12.1.11.jar`
(`Session`, `Session.Listener`, `Callback`, `Callback.Completable`,
`Configurable`), `jetty-util-12.1.11.jar` (`VirtualThreadPool`),
and the class-file major version of `jetty-server`, `helidon-webserver`,
`undertow-core`, `vertx-core` and `netty-codec-http`;
`HikariCP-7.0.2.jar` and `postgresql-42.7.8.jar` (synchronized census).

**Evidence, not authority**
`../skiff` at v0.9.9, read-only: `project/Dependencies.scala` (Jetty 12.0.16),
`modules/runtime/src/main/scala/skiff/runtime/Server.scala` (thread pool
configuration, `Callback.NOOP` send path), and the absence of any
`setMaxOutgoingFrames` / `setMaxTextMessageSize` / `setIdleTimeout` call in the
repository.

**Measured locally, cited to nothing**
Every figure in §1.1 to §1.4, §2, §4.1, §4.2, §5.2, §5.3 (runtime values), §5.4
(runtime value), §5.5, §6, §7, §11, and the synchronized census in §1.2.
