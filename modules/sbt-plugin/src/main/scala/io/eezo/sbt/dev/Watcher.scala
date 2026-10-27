package io.eezo.sbt.dev

import com.swoval.files.{FileTreeViews, PathWatcher, PathWatchers}

import java.io.File
import java.nio.file.Path

/** The source tree watched for the console, through swoval, the native watcher sbt's own `~` rides
  * on: `research/build-reload.md` measured it at a millisecond from save to event, where the JDK's
  * own service polls every two seconds on macOS.
  *
  * Every file event under the watched directories reaches `onChange` as a path; the debounce and
  * the decision to build are the loop's. Directories themselves are not reported, and neither is
  * anything under a `target` directory, which a build writes while it runs.
  */
private[sbt] final class Watcher(directories: Seq[File], onChange: Path => Unit) {

  private val watcher: PathWatcher[PathWatchers.Event] = PathWatchers.get(true)

  def start(): Unit = {
    watcher.addObserver(new FileTreeViews.Observer[PathWatchers.Event] {
      def onError(t: Throwable): Unit         = ()
      def onNext(e: PathWatchers.Event): Unit = {
        val typed = e.getTypedPath
        val path  = typed.getPath
        if (!typed.isDirectory && Watcher.relevant(path)) onChange(path)
      }
    }): Unit
    directories.filter(_.isDirectory).foreach { dir =>
      watcher.register(dir.toPath, Integer.MAX_VALUE): Unit
    }
  }

  def close(): Unit = watcher.close()
}

private[sbt] object Watcher {

  /** Not a build's own output, not an editor's scratch file. */
  def relevant(path: Path): Boolean = {
    val name        = path.getFileName.toString
    var p           = path.getParent
    var underTarget = false
    while (p != null && !underTarget) {
      if (p.getFileName != null && p.getFileName.toString == "target") underTarget = true
      p = p.getParent
    }
    !underTarget && !name.endsWith("~") && !name.endsWith(".swp") && !name.startsWith(".#")
  }
}
