import sbt._

/** Every external dependency eezo takes, and its version, in one place.
  *
  * Modules refer to the vals below rather than writing coordinates inline, so that a version bump
  * is a one-line change and two modules cannot disagree about a version.
  */
object Dependencies {

  object V {
    val munit = "1.3.4"
  }

  /** The test framework. Every module gets it; nothing else is shared by default. */
  val munit = "org.scalameta" %% "munit" % V.munit % Test
}
