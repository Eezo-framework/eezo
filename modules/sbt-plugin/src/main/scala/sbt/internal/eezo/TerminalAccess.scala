package sbt.internal.eezo

import sbt.internal.util.Terminal

/** The handful of `sbt.internal.util.Terminal` members the dev console needs that sbt marks
  * `private[sbt]`, re-exported from a package under `sbt` so the qualifier admits them.
  *
  * This is the one file of the plugin that lives outside `io.eezo.sbt`, and the only place that
  * touches sbt's terminal layer by name: every other file reaches the terminal through it, so an
  * sbt release that renames one of these members breaks exactly one file. The bytecode of each
  * member is public on both axes (sbt 1.5.8 through 1.12, and 2.0), and a plugin classloader seals
  * no packages, so the access is honoured at runtime as well as by the compiler.
  *
  * Why these and not jline directly: sbt owns the system terminal and the thread that pumps its
  * input, and a second `TerminalBuilder.terminal()` in the same JVM would race it for every byte.
  * Reading through sbt's own `Terminal` is how sbt's `~` watch reads its keys, and it is the only
  * reader that cooperates with sbt's shell when the console is started from inside it.
  *
  * Public, not `private[sbt]`: that qualifier would admit root `sbt` and refuse `io.eezo.sbt`, the
  * package every caller lives in. Nothing outside the plugin's jar has a reason to find it.
  */
object TerminalAccess {

  /** The terminal of the channel running the current command: the console, or a network client such
    * as a thin client, whose terminal is what the user is looking at.
    */
  def current: Terminal = Terminal.get

  /** Whether the current command's terminal is sbt's own console, as opposed to a client's relayed
    * over a socket. `Terminal.get` is a proxy for whichever channel runs the command, and the
    * console's streams are the one object each, so identity is the test.
    */
  def isConsole: Boolean = Terminal.get.outputStream eq Terminal.console.outputStream

  /** `f` with the terminal's input in raw mode: no line buffering, no echo, no `^C` to signal
    * translation once [[withoutSignals]] has run as well.
    */
  def withRawInput[T](terminal: Terminal)(f: => T): T = terminal.withRawInput(f)

  /** The terminal's attribute flags as sbt reads them: `iflag`, `oflag`, `cflag`, `lflag` and
    * `cchars`, each a space separated list of lowercase names.
    */
  def attributes(terminal: Terminal): Map[String, String] = terminal.getAttributes

  def setAttributes(terminal: Terminal, attributes: Map[String, String]): Unit =
    terminal.setAttributes(attributes)

  /** `f` with `isig` cleared from the local flags, restored afterwards whatever `f` does. sbt's raw
    * mode leaves it set, so that `^C` still cancels a running task; the console wants `^C` as a key
    * it answers itself, which is byte 3 on a terminal with `isig` off.
    */
  def withoutSignals[T](terminal: Terminal)(f: => T): T = {
    val before  = attributes(terminal)
    val lflag   = before.getOrElse("lflag", "")
    val cleared = lflag.split(" ").filterNot(_ == "isig").mkString(" ")
    try {
      setAttributes(terminal, before.updated("lflag", cleared))
      f
    } finally setAttributes(terminal, before)
  }
}
