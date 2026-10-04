package io.eezo.http

/** `HttpApp`'s overrides as one value, read by the server, the failure boundary and the reload
  * endpoint.
  *
  * One value so that a new setting is a field here rather than a parameter at every hop between
  * `HttpApp.serve` and the place that reads it. The route table is not one of them: it is what the
  * server serves, not how, and the boundary and the reload endpoint, which read these settings,
  * never dispatch, so a table here would be one they are handed and must not touch.
  */
private[eezo] final case class HttpConfig(
    maxBodySize: Long = HttpConfig.DefaultMaxBodySize,
    dev: Boolean = HttpConfig.DefaultDev,
    problems: PartialFunction[Throwable, Problem] = HttpConfig.DefaultProblems,
    secret: Secret = HttpConfig.DefaultSecret,
    layout: Layout = Layout.plain
)

private[eezo] object HttpConfig {

  /** The one place each setting's default is stated: the case class's parameter defaults read off
    * these, and so do `HttpApp`'s `maxBodySize` and `problems`. The secret is a `def`: a fresh
    * throwaway per call, which is what a test wants and what `HttpApp.secret` replaces with the
    * configured one. The layout has no entry here: its default is `Layout.plain`, a public value an
    * application can name itself, so a second name for it would be one more thing to keep equal.
    */
  private[http] val DefaultMaxBodySize: Long                             = 1.MiB
  private[http] val DefaultDev: Boolean                                  = false
  private[http] val DefaultProblems: PartialFunction[Throwable, Problem] = PartialFunction.empty
  private[http] def DefaultSecret: Secret                                = Secret.throwaway()
}

/** A size, so that a byte count in a signature reads as one.
  *
  * The body cap and the WebSocket text message cap are deliberately the same number, so there is
  * one limit to remember rather than two. It is the framework's own: an application states its cap
  * as a `Long`, and an `Int` extension that `import io.eezo.http.*` carried into every application
  * would be public API nobody chose.
  */
extension (n: Int) {
  private[eezo] def MiB: Long = n.toLong * 1024 * 1024
}
