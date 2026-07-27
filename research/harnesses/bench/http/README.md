# HTTP server rigs (eezo #3)

Backs every measured claim in [`research/http-server.md`](../../../http-server.md).
Built 2026-07-27 on Temurin JDK 21.0.11 and JDK 26+35, Apple M4 Pro, macOS 15.7.4.

Nothing here compiles on its own; each rig needs the matching `cp_*.txt`
classpath on `-cp` alongside `out/`. Regenerate the classpaths with the
`coursier fetch` commands in that document's Appendix A.1 if the cached paths no
longer resolve.

| File | Answers |
|---|---|
| `run.sh` | Driver: compiles all six boot rigs against a given `JAVA_HOME` and runs N repeats on fixed ports. |
| `src/Rig.java` | Shared helpers. The static `HttpClient` matters: a per-probe client leaks threads and fakes a server-side leak. |
| `src/Boot{Jetty,Helidon,Undertow,Jdk,Vertx,Netty}.java` | Boot time, same-port rebinding after `stop()`, handler carrier thread, thread residue. |
| `src/PinTest.java` | Does a blocking call inside `synchronized` pin the carrier? Run on JDK 21 and JDK 24+. |
| `src/SlowClient.java` | RFC 6455 client that handshakes then never reads. The tool that forces a server into its write backpressure regime. Reusable. |
| `src/Ws{Jetty,Helidon}.java` | WebSocket defaults, carrier thread, out-of-band push, backpressure. |
| `src/WsJettyQueue.java` | Is Jetty's outgoing frame queue bounded? Heap growth with and without `maxOutgoingFrames`. |
| `src/WsJettyBlocking.java` | Can `Callback.Completable` give a blocking, timeout-capable, virtual-thread-friendly write? |
| `src/ClassLoaderSwap.java` + `swap/` | Does the server release its classloader after `stop()`? The hot-reload question. |
| `src/HelidonLeak.java` | Is Helidon's thread residue proportional to the number of `WebServer` instances? |
| `src/ConnScale{Jetty,Helidon}.java`, `src/ConnLoad.java`, `vtcount.sh` | Heap and threads at thousands of live WebSocket connections, measured across two JVMs. |
| `src/AnonProbe.java`, `src/IdleProbe.java` | **Negative results.** Isolate a suspected Jetty upgrade failure that did not reproduce. Kept so the false lead is not re-derived. |
| `logs/jdk21.log`, `logs/jdk26.log` | Raw output of seven repeats of the boot suite on each JDK. |
