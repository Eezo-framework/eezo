# The server

Jetty on virtual threads, what stop does, and what the framework serves without being asked.
This page is about the process your routes run in.

## Jetty, on virtual threads

eezo runs Jetty 12 with a virtual thread pool and no cap on concurrent tasks. Every request
gets a virtual thread, and the pool's default ceiling is turned off because it reintroduces
the queueing virtual threads exist to remove. A handler can block on a query, an outbound
call or a lock, and the carrier thread moves on.

Jetty is the one dependency that provides both an HTTP server and a WebSocket server in one
artifact, with a pool built for virtual threads. Nothing Jetty-shaped
leaves the http module: your handler sees `Request` and `Response`, and the WebSocket
listener sees `WsConn`, which is an opaque wrapper.

> **Daniel:** the server comparison you ran before settling on Jetty, and what the other
> candidates lost on, belongs here if you want to say it.

## Why JDK 25

A virtual thread that blocks inside a `synchronized` block used to pin its carrier, and JDBC
drivers, connection pools and parts of Jetty itself are full of `synchronized`. Under load
that turns a pool of virtual threads back into a pool of platform threads, with the queueing
that implies. JEP 491 removed the pinning in JDK 24, and JDK 25 is the first long-term release
that carries it. The floor is checked when the build loads, so a wrong JVM fails with a reason
instead of forty lines into a compile.

## Four Jetty defaults, overridden

Each of these is a default the framework measured and calls a defect:

| setting | Jetty's default | eezo |
|---|---|---|
| thread pool | platform threads | virtual threads, no task cap |
| WebSocket idle timeout | 30 seconds | 5 minutes |
| WebSocket text message cap | 64 KiB | 1 MiB, the same as the body cap |
| outgoing frame queue | unbounded | 64 frames |

The last one matters most. An unbounded queue let a single stalled client grow the server's
heap by hundreds of megabytes before anything noticed. With the cap, a send to a client that
stopped reading blocks the page's thread, which is real back-pressure, and times out after
ten seconds, which closes the connection and lets the page's grace window take over.

The body cap and the message cap are the same number, one limit to remember, and
`maxBodySize` on your `Main` sets both.

## Stopping

The server comes down with the JVM. A shutdown hook stops it, and stopping means draining:

1. New requests are refused, and an upgrade that arrives now is refused like any other.
2. Requests in flight get up to three seconds to finish. Open WebSockets are closed with 1001
   as the drain begins, so a live page never holds the stop open.
3. A connection sitting silent is closed after one second in production and a tenth of that in
   dev, where the one client is on the loopback and the browser's idle keep-alive would
   otherwise add a second to every reload.
4. `run` returns, `serve` returns, and whatever wrapped it runs its `finally`. On the umbrella
   that's `withDatabase`, which closes the pool, after the last request has been answered
   and never under one still inside a transaction.

The hook waits up to ten seconds for that unwind. The budget is set against clocks that aren't
eezo's: the dev loop's `destroyForcibly` after ten seconds, and Fly's five-second
`kill_timeout`. Three for the drain leaves the unwind room inside both. A request still
running when the drain ends is cut off and logged.

## What the framework serves

Under `/eezo`, on every eezo server:

- **`/eezo/health`** answers `ok` with a 200. It's answered before the session cookie is read
  and before your table is consulted, so it costs nothing and nothing can shadow it. A health
  check that touched the database would turn a database blip into a restart loop, so this one
  doesn't. `eezo deploy` polls it, and so does Fly.
- **`/eezo/reload`**, the dev server's WebSocket. A 404 in production.
- **`/eezo/live/:page`** and **`/eezo/live.js`**, the live socket and client, when `LiveApp`
  is in. Appended after your table, so they're in the boot listing and not in `eezo routes`.
- **`/eezo/sync`** and **`/eezo/freeze`**, the drift page's actions, only while the drift page
  is being served.

## Logging

eezo logs through the JDK's `System.Logger`. Jetty and HikariCP log through SLF4J, and the
http and db artifacts each bring `slf4j-jdk14` so those lines land in `java.util.logging` too.
One backend, one format on stderr, nothing configured. The loggers are `io.eezo.http`,
`io.eezo.db` and `io.eezo.live`, beside `org.eclipse.jetty` and `com.zaxxer.hikari`.
[Using Logback](../how-to/use-logback.md) is the swap, and it's two build lines that have to
go together.

## Where to go next

What `eezo build` stages from this process, and how a platform runs it, is
[deployment](deployment.md).
