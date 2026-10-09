import java.io.File

import sbt.io.IO

/** The parts of the assertions in `build.sbt` that are plain functions rather than tasks.
  *
  * They live here rather than as `def`s in `build.sbt` so that the failure messages, which are the
  * only thing a reader of a red scripted run sees, can be written out at length without burying the
  * sequence of tasks they belong to.
  */
object Probe {

  /** What `recordRoutes` wrote: when the table was last written, and what it said. */
  final case class State(written: Long, contents: String)

  /** The timestamp is on a line of its own ahead of the contents, so a probe file written by an
    * older shape of this test fails to parse rather than being misread.
    */
  def write(probe: File, routes: File): Unit =
    IO.write(probe, routes.lastModified().toString + "\n" + IO.read(routes))

  def read(probe: File): State = {
    val raw   = IO.read(probe)
    val break = raw.indexOf('\n')
    if (break < 0) sys.error(s"$probe carries no timestamp on its first line.")
    State(raw.substring(0, break).toLong, raw.substring(break + 1))
  }

  /** Fails with the whole generated file, because a table that is missing a row is only readable
    * beside the rows it does have.
    */
  def mustContain(routes: File, fragment: String): Unit = {
    val contents = IO.read(routes)
    if (!contents.contains(fragment))
      sys.error(s"$routes does not contain\n  $fragment\nand instead reads\n\n$contents")
  }
}
