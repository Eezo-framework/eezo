/** The eezo release the site is built with, and the toolchain that release was built on.
  *
  * Literals, since eezo 0.1.0 is on Maven Central: the site resolves the release like any
  * application does, so a checkout needs no `sbt publishLocalForExample` before building it. The
  * Scala version and the JDK floor are the ones `project/Toolchain.scala` in the main build pins,
  * written here by hand because this separate build cannot read that file; a release that moves
  * either moves these too.
  */
object EezoVersion {

  /** The published eezo version. */
  val value: String = "0.1.0"

  /** `Toolchain.ScalaVersion` from the main build, carried across for `scalaVersion` here. */
  val scalaVersion: String = "3.8.4"

  /** `Toolchain.JdkFloor` from the main build, carried across for `-release` here. */
  val jdkFloor: String = "25"
}
