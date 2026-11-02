package io.eezo.sbt.dev

import com.swoval.files.{FileTreeViews, PathWatcher, PathWatchers}

import java.io.File
import java.nio.file.Path

/** The source tree watched for the console, through swoval, the native watcher sbt's own `~` rides
  * on: `research/build-reload.md` measured it at a millisecond from save to event, where the JDK's
  * own service polls every two seconds on macOS.
  *
  * Every file event under the watched directories reaches `onChange` as a path, and so does one on
  * a watched file, the stylesheet at the project root for one; the debounce and the decision to
  * build are the loop's. Directories themselves are not reported, and neither is anything under the
  * build's own output directories, which it writes while it runs. A watched file's siblings are not
  * reported either: its directory is registered one level deep so that the file's creation is seen,
  * and everything else there is dropped.
  *
  * A directory that does not exist yet, `src/main/resources` on a fresh scaffold, is watched
  * through its nearest existing ancestor, so that creating it and the first file in it counts. The
  * parents of the named files are registered before the source roots, shallow before deep, since a
  * watcher that registers a parent after a child may let the parent's depth win.
  */
private[sbt] final class Watcher(
    directories: Seq[File],
    files: Seq[File],
    ignored: Seq[File],
    onChange: Path => Unit
) {

  private val watcher: PathWatcher[PathWatchers.Event] = PathWatchers.get(true)

  private val roots  = directories.map(_.getAbsoluteFile.toPath)
  private val named  = files.map(_.getAbsoluteFile.toPath).toSet
  private val output = ignored.map(_.getAbsoluteFile.toPath)

  def start(): Unit = {
    watcher.addObserver(new FileTreeViews.Observer[PathWatchers.Event] {
      def onError(t: Throwable): Unit         = ()
      def onNext(e: PathWatchers.Event): Unit = {
        val typed = e.getTypedPath
        val path  = typed.getPath
        if (!typed.isDirectory && watched(path) && Watcher.relevant(path, output)) onChange(path)
      }
    }): Unit
    named.toSeq.map(_.getParent).distinct.filter(p => p != null && p.toFile.isDirectory).foreach {
      dir => watcher.register(dir, 0): Unit
    }
    Watcher.registrations(roots).foreach(dir => watcher.register(dir, Integer.MAX_VALUE): Unit)
  }

  private def watched(path: Path): Boolean =
    named.contains(path) || roots.exists(root => path.startsWith(root))

  def close(): Unit = watcher.close()
}

private[sbt] object Watcher {

  /** The directories to register for `roots`: each root, or the nearest existing ancestor of one
    * that does not exist yet, with those under another dropped, so that no tree is registered
    * twice.
    */
  def registrations(roots: Seq[Path]): Seq[Path] = {
    val existing = roots.flatMap(nearestExisting).distinct
    existing.filterNot(p => existing.exists(other => other != p && p.startsWith(other)))
  }

  private def nearestExisting(path: Path): Option[Path] = {
    var p = path
    while (p != null && !p.toFile.isDirectory) p = p.getParent
    Option(p)
  }

  /** Not under the build's own output, not an editor's scratch file. */
  def relevant(path: Path, ignored: Seq[Path]): Boolean = {
    val name = path.getFileName.toString
    !ignored.exists(dir => path.startsWith(dir)) && !name.endsWith("~") &&
    !name.endsWith(".swp") && !name.startsWith(".#")
  }
}
