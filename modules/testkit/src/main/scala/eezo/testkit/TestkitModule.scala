package eezo.testkit

/** Testkit: booting the real server on an ephemeral port against a real database, then driving it
  * over HTTP and WebSocket. No mocks.
  *
  * It is the only integration check the framework has until the demo application exists, so it is
  * load-bearing. The implementation lands in #47. This marker exists only so the module has
  * something to compile; delete it when the first real type arrives.
  */
object TestkitModule
