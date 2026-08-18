import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** Facts about the toolchain eezo is built with, and the assertions that keep them honest.
  *
  * The version numbers live here, in one place, so that `build.sbt`, the generated `BuildInfo` and
  * the CI workflow cannot drift apart.
  */
object Toolchain {

  /** The pinned language version. eezo tracks Scala 3.8.x. */
  val ScalaVersion = "3.8.4"

  /** The Scala version the sbt 1 axis of `sbt-eezo` is built with. sbt 1 runs on Scala 2.12 and a
    * plugin has to match it, so this is sbt's version to choose, not eezo's.
    */
  val PluginScalaVersion = "2.12.21"

  /** The oldest sbt 1 that `sbt-eezo` promises to work on. Deliberately not eezo's own sbt: a floor
    * pinned to whatever this build happens to run is an accident, not a promise. 1.5.8 is the floor
    * `sbt-ci-release` itself ships against.
    */
  val Sbt1Floor = "1.5.8"

  /** The sbt 2 the second axis of `sbt-eezo` is built against, and therefore the oldest sbt 2 the
    * plugin promises to work on. Whatever this axis compiles against becomes the floor: any API the
    * route table generator reaches for that landed after 2.0.0 turns into a hard requirement. 2.0.6
    * is the current sbt 2 release, and the version recommended by `research/sbt-plugin.md` on the
    * unmerged `research/sbt-plugin` branch, so users still on 2.0.0 through 2.0.5 have to upgrade
    * before they can take the plugin.
    */
  val Sbt2Version = "2.0.6"

  /** The minimum JDK, and the bytecode target.
    *
    * JEP 491 landed in JDK 24 and removed virtual-thread pinning on `synchronized`; JDK 25 is the
    * first LTS that carries it. `research/http-server.md` measured a latency degradation of roughly
    * 16.2x to 17.2x at fixed concurrency, comparing JDK 21 against JDK 26 on exactly that
    * behaviour, and eezo's server design depends on it. The floor does not move downwards.
    */
  val JdkFloor = 25

  /** The major version of the JVM running this build. */
  def runningJdkMajor: Int = {
    val raw    = System.getProperty("java.specification.version")
    val digits = (if (raw.startsWith("1.")) raw.drop(2) else raw).takeWhile(_.isDigit)
    if (digits.isEmpty) sys.error(s"cannot read a JDK major version out of '$raw'")
    else digits.toInt
  }

  /** Fails the build, at load time and with the reason, when the JVM is below the floor. */
  def assertJdk(): Unit = {
    val running = runningJdkMajor
    if (running < JdkFloor)
      sys.error(
        s"eezo needs JDK $JdkFloor or newer to build, and this is JDK $running. " +
          "JEP 491 (JDK 24) removed virtual-thread pinning on `synchronized`, JDK 25 is the first " +
          "LTS carrying it, and eezo's server design depends on it. See research/http-server.md."
      )
  }

  private def sbtVersion(buildProperties: File): String = {
    val properties = new Properties()
    val in         = new FileInputStream(buildProperties)
    try properties.load(in)
    finally in.close()
    properties.getProperty("sbt.version")
  }

  /** Fails the build, at load time and with the reason, when `examples/hello` pins a different sbt
    * launcher version than the root build. `sbt.version` is read by the launcher before any Scala
    * code runs, so, unlike `ScalaVersion` and `JdkFloor`, it cannot be carried across through the
    * `.eezo-version` file; comparing the two `build.properties` files after the fact is the closest
    * this repo can get to one source of truth for it.
    */
  def assertExampleSbtVersionMatches(rootBaseDirectory: File): Unit = {
    val root    = sbtVersion(new File(rootBaseDirectory, "project/build.properties"))
    val example = sbtVersion(new File(rootBaseDirectory, "examples/hello/project/build.properties"))
    if (root != example)
      sys.error(
        s"sbt.version drift: project/build.properties pins $root but " +
          s"examples/hello/project/build.properties pins $example. Update " +
          "examples/hello/project/build.properties by hand to match: sbt.version is read by the " +
          "launcher before any Scala code runs, so it cannot be generated the way ScalaVersion and " +
          "JdkFloor are."
      )
  }
}
