package io.eezo.sbt

import java.io.{ByteArrayOutputStream, File, InputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, StandardCopyOption}
import java.security.MessageDigest

/** The Tailwind CLI, as the resource generator `eezoTailwind` fetches and runs it.
  *
  * Tailwind reads the application's sources for the class names they use and writes the rules those
  * names need, so the stylesheet is a function of the sources the way the route table is. The
  * generator runs the CLI once per build rather than keeping its own watcher alive: the dev loop
  * already rebuilds on every save, and a process writing into the resource directory on its own
  * clock would race the copy that puts the stylesheet on the classpath.
  *
  * The CLI is the standalone binary Tailwind publishes with each release, so no Node toolchain is
  * needed on the machine. It is fetched once per version into the tools directory under `EEZO_HOME`
  * (`~/.eezo` by default, the directory the launcher already keeps its version file in), checked
  * against the digest list published beside it, and reused by every project on the machine. A build
  * that cannot reach GitHub names a binary of its own through `eezoTailwindCli`.
  *
  * Pure functions first, so the platform table, the addresses and the digest parsing are pinned
  * without a network; the two effectful entry points, [[ensureBinary]] and [[run]], are below them.
  * Plain JDK APIs throughout, and nothing past Java 8, because this source compiles for sbt 1 on
  * Scala 2.12 as well as sbt 2 on Scala 3.
  */
private[sbt] object Tailwind {

  /** The release the plugin fetches when a build names none. */
  val DefaultVersion: String = "4.3.3"

  private val Releases: String = "https://github.com/tailwindlabs/tailwindcss/releases/download"

  /** The one sentence every failure to obtain the binary ends with. */
  private val Hatch: String =
    "set eezoTailwindCli to a Tailwind CLI already on this machine to skip the download"

  /** The release asset for a platform, from the JVM's `os.name` and `os.arch`, or why there is
    * none. Tailwind publishes one Windows binary, for x64, and an arm64 Windows runs it through
    * emulation, so both architectures map to it there.
    */
  def asset(osName: String, osArch: String): Either[String, String] = {
    val os       = osName.toLowerCase
    val platform =
      if (os.startsWith("linux")) Right("linux")
      else if (os.startsWith("mac")) Right("macos")
      else if (os.startsWith("windows")) Right("windows")
      else Left(s"Tailwind publishes no CLI for the operating system '$osName'")
    val arch = osArch.toLowerCase match {
      case "amd64" | "x86_64" | "x64" => Right("x64")
      case "aarch64" | "arm64"        => Right("arm64")
      case other => Left(s"Tailwind publishes no CLI for the architecture '$other'")
    }
    for {
      p <- platform
      a <- arch
    } yield if (p == "windows") "tailwindcss-windows-x64.exe" else s"tailwindcss-$p-$a"
  }

  def downloadUrl(version: String, asset: String): String = s"$Releases/v$version/$asset"

  def sumsUrl(version: String): String = s"$Releases/v$version/sha256sums.txt"

  /** `EEZO_HOME/tools`, with `EEZO_HOME` defaulting to `.eezo` under the user's home. */
  def toolsDir(eezoHome: Option[String], userHome: String): File =
    new File(eezoHome.getOrElse(new File(userHome, ".eezo").getPath), "tools")

  /** Where one version of the CLI lives under the tools directory. */
  def cachedBinary(tools: File, version: String, windows: Boolean): File =
    new File(
      new File(tools, s"tailwindcss-$version"),
      if (windows) "tailwindcss.exe" else "tailwindcss"
    )

  /** The digest the release lists for one asset. The list is `sha256sum` output, one `<hex> <name>`
    * per line, with the name spelled `./tailwindcss-linux-x64`; the whole name has to match, since
    * `tailwindcss-linux-x64` is a prefix of `tailwindcss-linux-x64-musl`.
    */
  def expectedSha256(sums: String, asset: String): Option[String] =
    sums.split("\\r?\\n").iterator.map(_.trim).collectFirst {
      case line if named(line) == asset => line.takeWhile(c => !c.isWhitespace).toLowerCase
    }

  private def named(line: String): String =
    line.dropWhile(c => !c.isWhitespace).trim.stripPrefix("*").stripPrefix("./")

  def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in     = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](64 * 1024)
      var read   = in.read(buffer)
      while (read >= 0) {
        digest.update(buffer, 0, read)
        read = in.read(buffer)
      }
    } finally in.close()
    digest.digest().map(b => f"$b%02x").mkString
  }

  /** The command line: one input, one output, minified. The output is minified in development too,
    * because nobody reads a generated stylesheet and the browser's inspector formats it.
    */
  def command(cli: File, input: File, output: File): Seq[String] =
    Seq(
      cli.getAbsolutePath,
      "--input",
      input.getAbsolutePath,
      "--output",
      output.getAbsolutePath,
      "--minify"
    )

  /** The cached binary for `version`, fetched and verified when it is not there yet.
    *
    * The download lands in a `.part` file beside its final name and is moved into place in one
    * step, so a download that dies halfway never leaves a binary that looks whole. Two builds
    * fetching the same version at once both verify their own copy and the second move finds the
    * file already there, which is the outcome both wanted.
    */
  def ensureBinary(version: String, tools: File, log: String => Unit): File = {
    val windows = sys.props("os.name").toLowerCase.startsWith("windows")
    val target  = cachedBinary(tools, version, windows)
    if (target.isFile) target
    else {
      val name = asset(sys.props("os.name"), sys.props("os.arch")) match {
        case Right(name)  => name
        case Left(reason) => sys.error(s"eezoTailwind: $reason; $Hatch")
      }
      val url      = downloadUrl(version, name)
      val expected = expectedSha256(fetchText(sumsUrl(version)), name).getOrElse(
        sys.error(s"eezoTailwind: the release v$version lists no digest for $name; $Hatch")
      )
      Files.createDirectories(target.getParentFile.toPath)
      val part = Files.createTempFile(target.getParentFile.toPath, "tailwindcss", ".part")
      try {
        log(s"eezoTailwind: fetching $url")
        fetchFile(url, part.toFile)
        val actual = sha256(part.toFile)
        if (actual != expected)
          sys.error(
            s"eezoTailwind: $url does not match the digest the release lists for it " +
              s"(expected $expected, downloaded $actual); $Hatch"
          )
        if (!windows) part.toFile.setExecutable(true, false): Unit
        try Files.move(part, target.toPath, StandardCopyOption.ATOMIC_MOVE): Unit
        catch { case _: FileAlreadyExistsException => () }
      } finally Files.deleteIfExists(part): Unit
      target
    }
  }

  /** Runs the CLI to completion with nothing inherited: its output, the one `Done in` line on
    * success and the diagnostics on failure, is read back here. Returned rather than logged on
    * failure, so the caller fails the task with the diagnostics in the message and the `eezo dev`
    * console shows them the way it shows a compile error.
    */
  def run(cli: File, input: File, output: File, cwd: File): Either[String, Unit] = {
    val arguments = new java.util.ArrayList[String]
    command(cli, input, output).foreach(argument => arguments.add(argument): Unit)
    val builder = new ProcessBuilder(arguments)
    builder.directory(cwd)
    builder.redirectErrorStream(true)
    val process = builder.start()
    process.getOutputStream.close()
    val printed = readAll(process.getInputStream)
    val exit    = process.waitFor()
    if (exit == 0) Right(()) else Left(printed.trim)
  }

  private def open(url: String): HttpURLConnection = {
    val connection = new URI(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    connection.setConnectTimeout(15000)
    connection.setReadTimeout(60000)
    connection.setInstanceFollowRedirects(true)
    val status = connection.getResponseCode
    if (status != 200) sys.error(s"eezoTailwind: $url answered $status; $Hatch")
    connection
  }

  private def fetchText(url: String): String = {
    val in = open(url).getInputStream
    try readAll(in)
    finally in.close()
  }

  private def fetchFile(url: String, to: File): Unit = {
    val in = open(url).getInputStream
    try Files.copy(in, to.toPath, StandardCopyOption.REPLACE_EXISTING): Unit
    finally in.close()
  }

  /** `InputStream.readAllBytes` is Java 9, and the sbt 1 axis compiles for 8. */
  private def readAll(in: InputStream): String = {
    val out    = new ByteArrayOutputStream
    val buffer = new Array[Byte](8 * 1024)
    var read   = in.read(buffer)
    while (read >= 0) {
      out.write(buffer, 0, read)
      read = in.read(buffer)
    }
    new String(out.toByteArray, StandardCharsets.UTF_8)
  }
}
