/** Facts about the toolchain eezo is built with, and the assertions that keep them honest.
  *
  * The version numbers live here, in one place, so that `build.sbt`, the generated `BuildInfo` and
  * the CI workflow cannot drift apart.
  */
object Toolchain {

  /** The pinned language version. eezo tracks Scala 3.8.x. */
  val ScalaVersion = "3.8.4"

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
}
