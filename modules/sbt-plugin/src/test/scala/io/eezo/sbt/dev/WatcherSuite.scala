package io.eezo.sbt.dev

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** The watch over a real tree: what a save under each kind of root does, which no suite of the pure
  * parts can see, since it is swoval's registration that decides.
  */
class WatcherSuite extends munit.FunSuite {

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8)): Unit
  }

  /** A watcher over `base`, started, with the paths it reported in a queue. */
  private def watching(
      dirs: Seq[Path],
      files: Seq[Path] = Nil,
      ignored: Seq[Path] = Nil
  ): (Watcher, LinkedBlockingQueue[Path]) = {
    val seen    = new LinkedBlockingQueue[Path]
    val watcher =
      new Watcher(
        dirs.map(_.toFile),
        files.map(_.toFile),
        ignored.map(_.toFile),
        p => { seen.add(p): Unit }
      )
    watcher.start()
    Thread.sleep(300)
    (watcher, seen)
  }

  /** Waits for a report of `path`, letting other reports go by. */
  private def expectReport(seen: LinkedBlockingQueue[Path], path: Path): Unit = {
    val deadline = System.currentTimeMillis + 5000
    var found    = false
    while (!found && System.currentTimeMillis < deadline) {
      val next = seen.poll(200, TimeUnit.MILLISECONDS)
      if (next != null && next == path.toAbsolutePath) found = true
    }
    assert(found, s"no report of $path")
  }

  /** Waits long enough for a report of `path` to have come, and asserts none did; a second report
    * of an earlier file, a modify after its create, is let go by.
    */
  private def expectNoReport(seen: LinkedBlockingQueue[Path], path: Path): Unit = {
    val deadline = System.currentTimeMillis + 700
    while (System.currentTimeMillis < deadline) {
      val next = seen.poll(100, TimeUnit.MILLISECONDS)
      assert(next == null || next != path.toAbsolutePath, s"reported $path")
    }
  }

  test("a save under a source root is reported after the stylesheet's parent went in shallow") {
    val base = Files.createTempDirectory("eezo-watch")
    val src  = base.resolve("src/main/scala")
    Files.createDirectories(src)
    val stylesheet      = base.resolve("tailwind.css")
    val (watcher, seen) = watching(Seq(src), files = Seq(stylesheet))
    try {
      write(src.resolve("Foo.scala"), "object Foo")
      expectReport(seen, src.resolve("Foo.scala"))
      write(stylesheet, "@import \"tailwindcss\";")
      expectReport(seen, stylesheet)
      write(base.resolve("README.md"), "a sibling of the stylesheet, not watched")
      expectNoReport(seen, base.resolve("README.md"))
    } finally watcher.close()
  }

  test("a root that does not exist at start is watched once it is created") {
    val base      = Files.createTempDirectory("eezo-watch")
    val scala     = base.resolve("src/main/scala")
    val resources = base.resolve("src/main/resources")
    Files.createDirectories(scala)
    val (watcher, seen) = watching(Seq(scala, resources))
    try {
      write(resources.resolve("application.conf"), "port = 8080")
      expectReport(seen, resources.resolve("application.conf"))
    } finally watcher.close()
  }

  test(
    "the build's own output is ignored; a project under `target`, or a package so named, is not"
  ) {
    val base = Files.createTempDirectory("eezo-watch").resolve("target/project")
    Files.createDirectories(base)
    val (watcher, seen) = watching(Seq(base), ignored = Seq(base.resolve("target")))
    try {
      write(base.resolve("target/classes/Foo.class"), "")
      expectNoReport(seen, base.resolve("target/classes/Foo.class"))
      write(base.resolve("src/main/scala/target/Foo.scala"), "package target")
      expectReport(seen, base.resolve("src/main/scala/target/Foo.scala"))
    } finally watcher.close()
  }

  test(
    "registrations are the roots that exist, the ancestors of those that do not, nothing nested"
  ) {
    val base = Files.createTempDirectory("eezo-watch")
    Files.createDirectories(base.resolve("src/main/scala"))
    val roots = Seq(
      base.resolve("src/main/scala"),
      base.resolve("src/main/java"),
      base.resolve("src/main/resources")
    )
    assertEquals(Watcher.registrations(roots), Seq(base.resolve("src/main")))
    assertEquals(
      Watcher.registrations(Seq(base.resolve("src/main/scala"))),
      Seq(base.resolve("src/main/scala"))
    )
  }

  test("an editor's scratch files are not relevant") {
    val base = Files.createTempDirectory("eezo-watch")
    assert(!Watcher.relevant(base.resolve("Foo.scala~"), Nil))
    assert(!Watcher.relevant(base.resolve(".#Foo.scala"), Nil))
    assert(!Watcher.relevant(base.resolve(".Foo.scala.swp"), Nil))
    assert(Watcher.relevant(base.resolve("Foo.scala"), Nil))
  }
}
