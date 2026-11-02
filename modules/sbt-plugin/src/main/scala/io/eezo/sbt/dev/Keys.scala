package io.eezo.sbt.dev

/** The keys the console answers, and the decoder that reads them out of the raw byte stream.
  *
  * Raw mode hands the console bytes, not keys: a printable character is one byte, an arrow is an
  * escape sequence of three, and Escape on its own is the same first byte with nothing after it.
  * The decoder is a small state machine that holds an `ESC` until it knows which of the three it
  * is. Whether the stream has gone quiet after one cannot be asked of sbt's input stream, which
  * pumps a byte from the terminal only while a reader is blocked in `read`, so its `available` is
  * zero between keys and sequences alike. The decoder keeps the clock instead: the reader feeds
  * each byte with the time it arrived, and the tick thread calls [[KeyDecoder.expire]], which
  * delivers a lone Escape once nothing has followed it for [[KeyDecoder.Quiet]] milliseconds, late
  * by that much and never lost.
  *
  * Only the sequences the console binds are recognised. Any other `ESC [ ...` sequence is consumed
  * whole and dropped, so a key the console does not know never lands as stray characters in a text
  * prompt.
  */
private[sbt] sealed trait Key

private[sbt] object Key {
  final case class Char(c: scala.Char) extends Key
  case object Enter                    extends Key
  case object Escape                   extends Key
  case object Backspace                extends Key
  case object Tab                      extends Key
  case object BackTab                  extends Key
  case object Up                       extends Key
  case object Down                     extends Key
  case object Left                     extends Key
  case object Right                    extends Key
  case object PageUp                   extends Key
  case object PageDown                 extends Key
  case object Home                     extends Key
  case object End                      extends Key
  case object Delete                   extends Key
  case object CtrlC                    extends Key
  case object CtrlD                    extends Key
  case object CtrlL                    extends Key
}

private[sbt] final class KeyDecoder {

  private var pending: StringBuilder = null
  private var since: Long            = 0L

  /** The keys one more byte completes, often none; `now` is when the byte arrived. */
  def feed(b: Int, now: Long = 0L): Vector[Key] =
    if (pending != null) continueSequence(b)
    else
      b match {
        case 27           => pending = new StringBuilder; since = now; Vector.empty
        case 13 | 10      => Vector(Key.Enter)
        case 9            => Vector(Key.Tab)
        case 127 | 8      => Vector(Key.Backspace)
        case 3            => Vector(Key.CtrlC)
        case 4            => Vector(Key.CtrlD)
        case 12           => Vector(Key.CtrlL)
        case c if c >= 32 => Vector(Key.Char(c.toChar))
        case _            => Vector.empty
      }

  /** [[timeout]] once an `ESC` has waited [[KeyDecoder.Quiet]] milliseconds for its sequence. */
  def expire(now: Long): Vector[Key] =
    if (pending != null && now - since >= KeyDecoder.Quiet) timeout() else Vector.empty

  /** What a quiet stream after an `ESC` means: the Escape key itself. Also releases a sequence that
    * never completed, dropped.
    */
  def timeout(): Vector[Key] =
    if (pending == null) Vector.empty
    else {
      val held = pending.result()
      pending = null
      if (held.isEmpty) Vector(Key.Escape) else Vector.empty
    }

  private def continueSequence(b: Int): Vector[Key] = {
    pending.append(b.toChar)
    val seq = pending.result()
    if (seq == "[" || seq == "O") Vector.empty
    else if (seq.startsWith("[") && !isFinal(b)) Vector.empty
    else {
      pending = null
      seq match {
        case "[A" | "OA"                 => Vector(Key.Up)
        case "[B" | "OB"                 => Vector(Key.Down)
        case "[C" | "OC"                 => Vector(Key.Right)
        case "[D" | "OD"                 => Vector(Key.Left)
        case "[H" | "OH" | "[1~" | "[7~" => Vector(Key.Home)
        case "[F" | "OF" | "[4~" | "[8~" => Vector(Key.End)
        case "[5~"                       => Vector(Key.PageUp)
        case "[6~"                       => Vector(Key.PageDown)
        case "[3~"                       => Vector(Key.Delete)
        case "[Z"                        => Vector(Key.BackTab)
        case _ if seq.startsWith("[")    => Vector.empty
        // `ESC x`: an alt chord, or Escape typed just before another key; the key wins.
        case _ => Vector(Key.Escape) ++ feed(b)
      }
    }
  }

  private def isFinal(b: Int): Boolean = b >= 0x40 && b <= 0x7e
}

private[sbt] object KeyDecoder {

  /** How long an `ESC` waits for the rest of its sequence before it is the Escape key. A terminal
    * writes a sequence in one go, so the rest arrives within a millisecond; the wait only has to
    * outlast the hop through sbt's reader thread.
    */
  val Quiet: Long = 50L
}
