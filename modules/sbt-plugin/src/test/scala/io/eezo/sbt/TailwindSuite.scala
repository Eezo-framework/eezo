package io.eezo.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The pure half of the Tailwind generator: the platform table, the two release addresses, where
  * the binary is cached, the digest list's parsing and the command line. The download and the run
  * need a network and a binary, and the blog example exercises both under the gate.
  */
class TailwindSuite extends munit.FunSuite {

  test("the five platforms Tailwind publishes a binary for, by the JVM's own names") {
    assertEquals(Tailwind.asset("Linux", "amd64"), Right("tailwindcss-linux-x64"))
    assertEquals(Tailwind.asset("Linux", "aarch64"), Right("tailwindcss-linux-arm64"))
    assertEquals(Tailwind.asset("Mac OS X", "x86_64"), Right("tailwindcss-macos-x64"))
    assertEquals(Tailwind.asset("Mac OS X", "aarch64"), Right("tailwindcss-macos-arm64"))
    assertEquals(Tailwind.asset("Windows 11", "amd64"), Right("tailwindcss-windows-x64.exe"))
  }

  test("an arm64 Windows gets the x64 binary, the only one Tailwind publishes there") {
    assertEquals(Tailwind.asset("Windows 11", "aarch64"), Right("tailwindcss-windows-x64.exe"))
  }

  test("an unknown platform is a reason, naming what was asked") {
    assert(Tailwind.asset("FreeBSD", "amd64").left.exists(_.contains("FreeBSD")))
    assert(Tailwind.asset("Linux", "riscv64").left.exists(_.contains("riscv64")))
  }

  test("the addresses are the release's download URLs") {
    assertEquals(
      Tailwind.downloadUrl("4.3.3", "tailwindcss-linux-x64"),
      "https://github.com/tailwindlabs/tailwindcss/releases/download/v4.3.3/tailwindcss-linux-x64"
    )
    assertEquals(
      Tailwind.sumsUrl("4.3.3"),
      "https://github.com/tailwindlabs/tailwindcss/releases/download/v4.3.3/sha256sums.txt"
    )
  }

  test("the tools directory is under EEZO_HOME, and under ~/.eezo without one") {
    assertEquals(Tailwind.toolsDir(Some("/opt/eezo"), "/home/me"), new File("/opt/eezo/tools"))
    assertEquals(Tailwind.toolsDir(None, "/home/me"), new File("/home/me/.eezo/tools"))
  }

  test("one directory per version, and the Windows name carries its extension") {
    val tools = new File("/home/me/.eezo/tools")
    assertEquals(
      Tailwind.cachedBinary(tools, "4.3.3", windows = false),
      new File("/home/me/.eezo/tools/tailwindcss-4.3.3/tailwindcss")
    )
    assertEquals(
      Tailwind.cachedBinary(tools, "4.3.3", windows = true),
      new File("/home/me/.eezo/tools/tailwindcss-4.3.3/tailwindcss.exe")
    )
  }

  private val sums =
    """1111111111111111111111111111111111111111111111111111111111111111  ./tailwindcss-linux-arm64
      |2222222222222222222222222222222222222222222222222222222222222222  ./tailwindcss-linux-x64-musl
      |ABCDEF3333333333333333333333333333333333333333333333333333333333  ./tailwindcss-linux-x64
      |4444444444444444444444444444444444444444444444444444444444444444  ./tailwindcss-windows-x64.exe
      |""".stripMargin

  test("the digest list is read by whole asset name, so x64 is not confused with x64-musl") {
    assertEquals(
      Tailwind.expectedSha256(sums, "tailwindcss-linux-x64"),
      Some("abcdef3333333333333333333333333333333333333333333333333333333333")
    )
    assertEquals(
      Tailwind.expectedSha256(sums, "tailwindcss-linux-x64-musl"),
      Some("2222222222222222222222222222222222222222222222222222222222222222")
    )
    assertEquals(Tailwind.expectedSha256(sums, "tailwindcss-macos-arm64"), None)
  }

  test("the digest is sha256sum's hex, lower case") {
    val file = Files.createTempFile("tailwind-suite", ".txt")
    try {
      Files.write(file, "hello".getBytes(StandardCharsets.UTF_8))
      assertEquals(
        Tailwind.sha256(file.toFile),
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
      )
    } finally Files.deleteIfExists(file): Unit
  }

  test("the command line is one input, one output, minified") {
    val cli = new File("/tools/tailwindcss")
    assertEquals(
      Tailwind.command(cli, new File("/app/tailwind.css"), new File("/app/target/tailwind.css")),
      Seq(
        cli.getAbsolutePath,
        "--input",
        new File("/app/tailwind.css").getAbsolutePath,
        "--output",
        new File("/app/target/tailwind.css").getAbsolutePath,
        "--minify"
      )
    )
  }
}
