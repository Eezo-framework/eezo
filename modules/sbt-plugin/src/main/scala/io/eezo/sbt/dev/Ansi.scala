package io.eezo.sbt.dev

/** The few escape sequences the console writes, and the width arithmetic a row needs once it
  * contains them.
  *
  * Sixteen colours and three attributes, nothing else: every terminal the dev loop will ever run in
  * has them, and the console's look is carried by layout and restraint, not by a palette. The
  * sequences are spelled here once; `View` composes styles and never writes an escape itself.
  *
  * Width is counted in code points with every escape sequence skipped, and every code point is
  * taken as one column. The glyphs the console uses (`✓`, `✗`, `⚠`, the braille spinner, the box
  * drawing set) are single width; a wide character in an application's own log line will push that
  * row one column off, which `Screen` clears with an erase to end of line, so it mis-aligns nothing
  * but itself.
  */
private[sbt] object Ansi {

  final val Esc: Char = 27.toChar

  /** A text attribute set: colour and the three attributes, each absent by default. */
  final case class Style(
      fg: Option[Int] = None,
      bold: Boolean = false,
      dim: Boolean = false,
      reverse: Boolean = false
  ) {

    def isPlain: Boolean = fg.isEmpty && !bold && !dim && !reverse

    /** `text` wrapped in this style, or `text` itself when the style is plain or colour is off. */
    def apply(text: String)(implicit theme: Theme): String = render(text, theme)

    /** [[apply]] with the theme named, for a caller that holds another theme implicitly. */
    def render(text: String, theme: Theme): String =
      if (!theme.color || isPlain || text.isEmpty) text
      else {
        val codes = List(
          if (bold) List("1") else Nil,
          if (dim) List("2") else Nil,
          if (reverse) List("7") else Nil,
          fg.map(_.toString).toList
        ).flatten
        s"$Esc[${codes.mkString(";")}m$text$Esc[0m"
      }
  }

  val Plain: Style   = Style()
  val Bold: Style    = Style(bold = true)
  val Dim: Style     = Style(dim = true)
  val Reverse: Style = Style(reverse = true)
  val Red: Style     = Style(fg = Some(31))
  val Green: Style   = Style(fg = Some(32))
  val Yellow: Style  = Style(fg = Some(33))
  val Blue: Style    = Style(fg = Some(34))
  val Magenta: Style = Style(fg = Some(35))
  val Cyan: Style    = Style(fg = Some(36))

  /** What the console can assume of the terminal: colour, and whether the box drawing and status
    * glyphs are safe to write. Both are decided once at start from the terminal and the
    * environment; the view only reads them.
    */
  final case class Theme(color: Boolean, unicode: Boolean) {
    def ok: String       = if (unicode) "✓" else "ok"
    def fail: String     = if (unicode) "✗" else "x"
    def warn: String     = if (unicode) "⚠" else "!"
    def bullet: String   = if (unicode) "·" else "-"
    def rule: String     = if (unicode) "─" else "-"
    def ellipsis: String = if (unicode) "…" else "..."
    def up: String       = if (unicode) "↑" else "^"
    def down: String     = if (unicode) "↓" else "v"

    /** The braille wheel the bash console span, with an ASCII wheel beside it. */
    def spinner(frame: Int): String =
      if (unicode) Spinner(frame % Spinner.length) else AsciiSpinner(frame % AsciiSpinner.length)
  }

  object Theme {

    /** From the environment: `NO_COLOR` (https://no-color.org) turns colour off whatever the
      * terminal said; the encoding of stdout decides the glyphs.
      */
    def detect(colorEnabled: Boolean, env: String => Option[String], encoding: String): Theme =
      Theme(
        color = colorEnabled && env("NO_COLOR").forall(_.isEmpty),
        unicode = encoding.toUpperCase.replace("-", "").startsWith("UTF")
      )
  }

  private val Spinner: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  private val AsciiSpinner: Vector[String] = Vector("|", "/", "-", "\\")

  /** The number of columns `s` takes: every character, minus the escape sequences. */
  def width(s: String): Int = {
    var columns = 0
    var i       = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == Esc && i + 1 < s.length && s.charAt(i + 1) == '[') {
        i += 2
        while (i < s.length && !isFinalByte(s.charAt(i))) i += 1
        i += 1
      } else {
        if (!Character.isLowSurrogate(c)) columns += 1
        i += 1
      }
    }
    columns
  }

  /** `s` without its escape sequences: the compiler colours its diagnostics, and a value handed to
    * a program, the JSON event stream, wants the text alone.
    */
  def strip(s: String): String = {
    val sb = new StringBuilder
    var i  = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == Esc && i + 1 < s.length && s.charAt(i + 1) == '[') {
        i += 2
        while (i < s.length && !isFinalByte(s.charAt(i))) i += 1
        i += 1
      } else {
        sb.append(c)
        i += 1
      }
    }
    sb.toString
  }

  private def isFinalByte(c: Char): Boolean = c >= '@' && c <= '~'

  /** `s` cut to at most `columns` columns, with the escape sequences it contains left intact and
    * every open attribute closed at the cut, so a truncated styled row never bleeds its colour into
    * the next.
    */
  def truncate(s: String, columns: Int): String =
    if (columns <= 0) ""
    else if (width(s) <= columns) s
    else {
      val sb     = new StringBuilder
      var taken  = 0
      var i      = 0
      var styled = false
      while (i < s.length && taken < columns) {
        val c = s.charAt(i)
        if (c == Esc && i + 1 < s.length && s.charAt(i + 1) == '[') {
          val start = i
          i += 2
          while (i < s.length && !isFinalByte(s.charAt(i))) i += 1
          i += 1
          val seq = s.substring(start, math.min(i, s.length))
          sb.append(seq)
          styled = !seq.endsWith("[0m")
        } else {
          sb.append(c)
          if (!Character.isLowSurrogate(c)) taken += 1
          i += 1
        }
      }
      if (styled) sb.append(s"$Esc[0m")
      sb.result()
    }

  /** `s` padded with spaces on the right to exactly `columns`, truncated when longer. */
  def padRight(s: String, columns: Int): String = {
    val cut = truncate(s, columns)
    cut + spaces(columns - width(cut))
  }

  /** `s` padded on the left: right aligned in `columns`. */
  def padLeft(s: String, columns: Int): String = {
    val cut = truncate(s, columns)
    spaces(columns - width(cut)) + cut
  }

  /** `left` and `right` on one row of `columns`, the gap between them filled; the right part wins
    * the space when both do not fit, since it is where the console puts what changed last.
    */
  def spread(left: String, right: String, columns: Int): String = {
    val r    = truncate(right, columns)
    val room = columns - width(r)
    val l    = truncate(left, math.max(0, if (room > 0) room - 1 else 0))
    l + spaces(columns - width(l) - width(r)) + r
  }

  /** The middle of a long plain string replaced by `ellipsis`, keeping the two ends, which is what
    * identifies a path: the file at the end and the root at the start.
    */
  def shorten(s: String, columns: Int, ellipsis: String): String =
    if (s.length <= columns) s
    else if (columns <= ellipsis.length) s.take(math.max(0, columns))
    else {
      val keep = columns - ellipsis.length
      val head = (keep + 1) / 2
      val tail = keep - head
      s.take(head) + ellipsis + s.takeRight(tail)
    }

  def spaces(n: Int): String = if (n <= 0) "" else " " * n

  /** `s` as one or more rows of at most `columns`, breaking at the last space that fits and hard
    * breaking a word longer than a row. Escape sequences are not expected inside a wrapped string:
    * application output is wrapped before it is styled.
    */
  def wrap(s: String, columns: Int): Vector[String] =
    if (columns <= 0) Vector(s)
    else if (s.length <= columns) Vector(s)
    else {
      val rows = Vector.newBuilder[String]
      var rest = s
      while (rest.length > columns) {
        val cut = rest.lastIndexOf(' ', columns)
        val at  = if (cut <= 0) columns else cut
        rows += rest.substring(0, at)
        rest = rest.substring(at).dropWhile(_ == ' ')
      }
      rows += rest
      rows.result()
    }
}
