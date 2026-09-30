package site

/** Whether the generated API docs are in the jar. They are when `sbt unidoc` ran at the root before
  * this build, which CI does and a laptop may not have: a suite that needs them requires them under
  * `CI` and skips otherwise.
  */
object ApiDocs {

  val present: Boolean = Assets.readUnder("api", "index.html").isDefined

  locally {
    if (sys.env.contains("CI") && !present)
      throw new IllegalStateException(
        "CI builds the site without the API docs: run `sbt unidoc` first"
      )
  }

  /** Whether a link into `/api` lands on a file, or is unverifiable because the docs are absent. */
  def linkOk(href: String): Boolean = {
    val raw  = href.stripPrefix("/api/").takeWhile(c => c != '#' && c != '?')
    val file = if (raw.isEmpty || raw.endsWith("/")) raw + "index.html" else raw
    !present || Assets.readUnder("api", file).isDefined
  }
}
