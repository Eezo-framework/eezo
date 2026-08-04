import java.io.{DataInputStream, File, FileInputStream}

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
    * first LTS that carries it. `research/http-server.md` measured a 16x throughput difference
    * against JDK 21 on exactly that behaviour, and eezo's server design depends on it. The floor
    * does not move downwards.
    */
  val JdkFloor = 25

  /** The class-file major version that `JdkFloor` stamps. Java 1.0 is 45, and it counts up by one
    * per release.
    */
  val ClassFileMajor = JdkFloor + 44

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

  /** The major version stamped into the first class file found under `dir`.
    *
    * Reads the class file header directly: four magic bytes, then the minor and major version as
    * unsigned shorts.
    */
  def classFileMajorOf(dir: File): Int = {
    val first = allClassFiles(dir).headOption
      .getOrElse(sys.error(s"no class file under $dir to read a bytecode version from"))
    val in = new DataInputStream(new FileInputStream(first))
    try {
      if (in.readInt() != 0xcafebabe) sys.error(s"$first does not start with the class file magic")
      in.readUnsignedShort() // minor
      in.readUnsignedShort() // major
    } finally in.close()
  }

  private def allClassFiles(dir: File): Seq[File] =
    Option(dir.listFiles()).toSeq.flatten.flatMap { f =>
      if (f.isDirectory) allClassFiles(f)
      else if (f.getName.endsWith(".class")) Seq(f)
      else Nil
    }
}
