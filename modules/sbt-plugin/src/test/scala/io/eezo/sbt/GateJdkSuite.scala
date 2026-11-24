package io.eezo.sbt

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit

import munit.FunSuite

class GateJdkSuite extends FunSuite {

  import GateJdkSuite._

  test("with the JDK CI builds on first on the PATH, bin/fast says nothing and changes nothing") {
    val box = new Box
    val jdk = box.jdk("jdks/25.0.2-open", "25.0.2")
    val run = box.fast(path = jdk, javaHome = Some(jdk))
    assertEquals(run.err, "")
    assertEquals(
      run.out,
      s"fast: in .: sbt -batch compile\nsbt java ${jdk}/bin/java\nsbt JAVA_HOME $jdk\n"
    )
    assertEquals(run.code, 0)
  }

  test("with a newer JDK on the PATH, bin/fast runs sbt on the sdkman JDK CI builds on") {
    val box   = new Box
    val newer = box.jdk("home/.sdkman/candidates/java/26-tem", "26")
    box.jdk("home/.sdkman/candidates/java/24.0.2-open", "24.0.2")
    val ci  = box.jdk("home/.sdkman/candidates/java/25.0.2-open", "25.0.2")
    val run = box.fast(path = newer, javaHome = Some(newer), jdkDirs = None)
    assertEquals(run.err, "")
    assertEquals(
      run.out,
      s"fast: the java on the PATH is JDK 26 at $newer, so sbt runs on JDK 25 at $ci, the JDK CI builds on\n" +
        "fast: in .: sbt -batch compile\n" +
        s"sbt java $ci/bin/java\n" +
        s"sbt JAVA_HOME $ci\n"
    )
  }

  test(
    "with a newer JDK on the PATH and JAVA_HOME at the JDK CI builds on, sbt runs on JAVA_HOME"
  ) {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val ci    = box.jdk("elsewhere/jdk-25", "25")
    val run   = box.fast(path = newer, javaHome = Some(ci), jdkDirs = Some(box.dir("jdks")))
    assertEquals(lines(run.out).drop(2), List(s"sbt java $ci/bin/java", s"sbt JAVA_HOME $ci"))
  }

  test("with a newer JDK on the PATH, a JDK CI builds on in a macOS bundle is found") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val ci    = box.jdk("jdks/temurin-25.jdk/Contents/Home", "25.0.1")
    val run   = box.fast(path = newer, javaHome = None)
    assertEquals(lines(run.out).drop(2), List(s"sbt java $ci/bin/java", s"sbt JAVA_HOME $ci"))
  }

  test("with a newer JDK on the PATH and none CI builds on, bin/fast warns and runs sbt on it") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val run   = box.fast(path = newer, javaHome = Some(newer))
    assertEquals(run.err, warning("fast", newer, box.dir("jdks")))
    assertEquals(
      run.out,
      s"fast: in .: sbt -batch compile\nsbt java $newer/bin/java\nsbt JAVA_HOME $newer\n"
    )
    assertEquals(run.code, 0)
  }

  test("with a newer JDK on the PATH and none CI builds on, bin/gate refuses before any step") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val run   = box.gate(path = newer)
    assertEquals(run.err, warning("gate", newer, box.dir("jdks")))
    assertEquals(
      run.out,
      "gate: the gate runs what CI runs, and CI builds on JDK 25. EEZO_GATE_ANY_JDK=1 runs it on " +
        "this JDK anyway, and its green is then not CI's green.\nGATE RED: jdk\n"
    )
    assertEquals(run.code, 1)
  }

  test("EEZO_GATE_ANY_JDK=1 runs the gate on a JDK CI does not build on, and says so") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val run   = box.gate(path = newer, extra = Map("EEZO_GATE_ANY_JDK" -> "1"))
    assertEquals(run.err, warning("gate", newer, box.dir("jdks")))
    assertEquals(
      lines(run.out).head,
      "gate: EEZO_GATE_ANY_JDK=1, so the gate runs on this JDK, and its green is not CI's green"
    )
    assertEquals(lines(run.out).last, "GATE GREEN")
    assertEquals(run.code, 0)
  }

  test("with a newer JDK on the PATH, bin/gate takes the JDK CI builds on and runs its steps") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    val ci    = box.jdk("jdks/25.0.2-open", "25.0.2")
    val run   = box.gate(path = newer)
    assertEquals(run.err, "")
    assertEquals(
      lines(run.out).head,
      s"gate: the java on the PATH is JDK 26 at $newer, so sbt runs on JDK 25 at $ci, the JDK CI builds on"
    )
    assertEquals(lines(run.out).last, "GATE GREEN")
    assertEquals(run.code, 0)
  }

  test("the JDK CI builds on is the JdkFloor value, not a commented line or a scaladoc mention") {
    val box   = new Box
    val newer = box.jdk("jdks/26-tem", "26")
    box.jdk("jdks/21-tem", "21")
    box.jdk("jdks/27-tem", "27")
    val run = box.fast(path = newer, javaHome = Some(newer))
    assertEquals(run.err, warning("fast", newer, box.dir("jdks")))
  }

  test("the JDK scripts use nothing macOS's /bin/bash 3.2 lacks") {
    val bash4 =
      List("declare -A", "local -A", "typeset -A", "mapfile", "readarray", ",,}", "^^}", "shopt")
    val used = scripts.flatMap(script => bash4.filter(read(repository.resolve(script)).contains))
    assertEquals(used, Nil)
  }
}

object GateJdkSuite {

  final case class Run(code: Int, out: String, err: String)

  val scripts: List[String] = List("bin/fast", "bin/gate", "bin/lib/env.sh")

  val toolchain: String =
    """object Toolchain {
      |
      |  /** The minimum JDK, the same value as `val JdkFloor = 27` would be elsewhere.
      |    * val JdkFloor = 27
      |    */
      |  // val JdkFloor = 21
      |  val JdkFloor = 25
      |}
      |""".stripMargin

  def warning(tool: String, running: Path, dirs: Path): String = {
    val w = s"$tool: WARNING:"
    s"$w the java on the PATH is JDK 26 at $running, and CI builds on JDK 25.\n" +
      s"$w the compiler reads the JDK it runs on, so code that compiles here can fail on CI.\n" +
      s"$w no JDK 25 in JAVA_HOME or under EEZO_JDK_DIRS, which is $dirs.\n" +
      s"$w install a JDK 25 (sdkman: sdk list java, then sdk install java <a 25 version>; " +
      "Debian or Ubuntu: apt install openjdk-25-jdk; or https://adoptium.net), or set JAVA_HOME to one.\n"
  }

  final class Box {
    val root: Path          = Files.createTempDirectory("eezo-gate-jdk").toRealPath()
    val checkout: Path      = Files.createDirectories(root.resolve("checkout"))
    private val tools: Path = Files.createDirectories(root.resolve("tools"))

    scripts.foreach { script =>
      val target = checkout.resolve(script)
      Files.createDirectories(target.getParent)
      Files.copy(repository.resolve(script), target, StandardCopyOption.COPY_ATTRIBUTES)
    }
    write(
      Files.createDirectories(checkout.resolve("project")).resolve("Toolchain.scala"),
      toolchain
    )
    executable(
      tools.resolve("sbt"),
      "#!/bin/sh\necho \"sbt java $(command -v java)\"\necho \"sbt JAVA_HOME ${JAVA_HOME:-}\"\n"
    )
    exec(root, List("git", "init", "-q", checkout.toString))
    exec(root, List("git", "-C", checkout.toString, "config", "core.hooksPath", ".githooks"))

    def dir(relative: String): Path = Files.createDirectories(root.resolve(relative))

    def jdk(relative: String, version: String): Path = {
      val home = dir(relative)
      val bin  = Files.createDirectories(home.resolve("bin"))
      write(home.resolve("release"), s"""IMPLEMENTOR="test"\nJAVA_VERSION="$version"\n""")
      val major = version.takeWhile(_.isDigit)
      executable(
        bin.resolve("java"),
        s"#!/bin/sh\necho '    java.home = $home' >&2\necho '    java.specification.version = $major' >&2\n"
      )
      home
    }

    def fast(path: Path, javaHome: Option[Path], jdkDirs: Option[Path] = Some(dir("jdks"))): Run =
      run(
        List(checkout.resolve("bin/fast").toString, "compile"),
        path,
        javaHome,
        jdkDirs,
        Map.empty
      )

    def gate(path: Path, extra: Map[String, String] = Map.empty): Run =
      run(
        List(checkout.resolve("bin/gate").toString, "--dry-run"),
        path,
        Some(path),
        Some(dir("jdks")),
        extra
      )

    private def run(
        command: List[String],
        jdk: Path,
        javaHome: Option[Path],
        jdkDirs: Option[Path],
        extra: Map[String, String]
    ): Run = {
      val out     = root.resolve("stdout").toFile
      val err     = root.resolve("stderr").toFile
      val builder = new ProcessBuilder(javaList(command))
        .directory(checkout.toFile)
        .redirectInput(new File("/dev/null"))
        .redirectOutput(out)
        .redirectError(err)
      val env = builder.environment()
      env.clear()
      env.put("PATH", s"$jdk/bin:$tools:/usr/bin:/bin")
      env.put("HOME", dir("home").toString)
      env.put("TMPDIR", dir("tmp").toString)
      javaHome.foreach(home => env.put("JAVA_HOME", home.toString))
      jdkDirs.foreach(dirs => env.put("EEZO_JDK_DIRS", dirs.toString))
      extra.foreach { case (k, v) => env.put(k, v) }
      val process = builder.start()
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor()
        throw new AssertionError(
          s"${command.mkString(" ")} did not finish in 60 seconds:\n${read(err.toPath)}"
        )
      }
      Run(process.exitValue(), read(out.toPath), read(err.toPath))
    }
  }

  lazy val repository: Path = {
    def upwards(from: File, left: Int): Option[File] =
      if (new File(from, "bin/lib/env.sh").isFile) Some(from)
      else if (left == 0 || from.getParentFile == null) None
      else upwards(from.getParentFile, left - 1)
    val here = new File(".").getCanonicalFile
    upwards(here, 4)
      .getOrElse(throw new AssertionError(s"bin/lib/env.sh not found from $here"))
      .toPath
  }

  private def javaList(items: List[String]): java.util.List[String] = {
    val list = new java.util.ArrayList[String]()
    items.foreach(list.add)
    list
  }

  private def lines(text: String): List[String] = text.split("\n").toList

  private def exec(root: Path, command: List[String]): Unit = {
    val process = new ProcessBuilder(javaList(command))
      .redirectOutput(root.resolve("exec.out").toFile)
      .redirectError(root.resolve("exec.err").toFile)
      .start()
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
      throw new AssertionError(s"${command.mkString(" ")} did not finish in 30 seconds")
    }
    if (process.exitValue() != 0) throw new AssertionError(s"${command.mkString(" ")} failed")
  }

  private def write(path: Path, text: String): Unit = {
    Files.write(path, text.getBytes(UTF_8))
    ()
  }

  private def executable(path: Path, text: String): Unit = {
    write(path, text)
    if (!path.toFile.setExecutable(true)) throw new AssertionError(s"cannot make $path executable")
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)
}
