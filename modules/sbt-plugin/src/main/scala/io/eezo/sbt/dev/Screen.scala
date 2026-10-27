package io.eezo.sbt.dev

/** The terminal's alternate screen, and the writer that puts a frame on it.
  *
  * A frame is a vector of rows. The screen remembers the last frame it wrote and writes only the
  * rows that changed, each by absolute cursor position with an erase to the end of the line, in one
  * write per frame. No newline is ever written, so the terminal never scrolls and nothing sbt
  * appends to a newline (its progress clear) can reach the screen. A size change redraws
  * everything, since the terminal has reflowed what was there. Every write ends by parking the
  * cursor on the last cell: see `draw`.
  */
private[sbt] final class Screen(write: String => Unit) {

  private var last: Vector[String] = Vector.empty
  private var lastWidth: Int       = -1
  private var lastHeight: Int      = -1

  def enter(): Unit = {
    write(Screen.EnterAlternate + Screen.HideCursor + Screen.Home + Screen.ClearAll)
    last = Vector.empty
  }

  def leave(): Unit = write(Screen.ShowCursor + Screen.LeaveAlternate)

  def draw(rows: Vector[String], width: Int, height: Int): Unit = {
    val resized = width != lastWidth || height != lastHeight
    val sb      = new StringBuilder
    if (resized) {
      sb.append(Screen.Home).append(Screen.ClearAll)
      last = Vector.empty
    }
    var i = 0
    while (i < rows.size && i < height) {
      val row = rows(i)
      if (resized || i >= last.size || last(i) != row)
        sb.append(s"\u001b[${i + 1};1H").append(row).append(Screen.ClearLine): Unit
      i += 1
    }
    // The cursor is parked on the last cell after every write. sbt's terminal appends an erase to
    // the end of the screen after each write it forwards; from the last cell that erases one blank
    // cell, from the end of a rewritten header row it would erase every row under it.
    if (sb.nonEmpty) write(sb.append(s"\u001b[$height;${width}H").result())
    last = rows
    lastWidth = width
    lastHeight = height
  }

  /** Forget the last frame, so the next draw writes every row: after anything else wrote. */
  def invalidate(): Unit = { last = Vector.empty; lastWidth = -1 }
}

private[sbt] object Screen {
  val EnterAlternate: String = "\u001b[?1049h"
  val LeaveAlternate: String = "\u001b[?1049l"
  val HideCursor: String     = "\u001b[?25l"
  val ShowCursor: String     = "\u001b[?25h"
  val Home: String           = "\u001b[H"
  val ClearAll: String       = "\u001b[2J"
  val ClearLine: String      = "\u001b[K"
}
