package io.eezo.sbt.dev

class KeyDecoderSuite extends munit.FunSuite {

  private def decode(bytes: Int*): Vector[Key] = {
    val d = new KeyDecoder
    bytes.toVector.flatMap(b => d.feed(b)) ++ d.timeout()
  }

  test("printable bytes are characters, control bytes are their keys") {
    assertEquals(decode('a', 'Z', '?'), Vector(Key.Char('a'), Key.Char('Z'), Key.Char('?')))
    assertEquals(decode(13), Vector(Key.Enter))
    assertEquals(decode(10), Vector(Key.Enter))
    assertEquals(decode(9), Vector(Key.Tab))
    assertEquals(decode(127), Vector(Key.Backspace))
    assertEquals(decode(3), Vector(Key.CtrlC))
  }

  test("arrows, paging and home/end arrive as escape sequences in both spellings") {
    assertEquals(decode(27, '[', 'A'), Vector(Key.Up))
    assertEquals(decode(27, 'O', 'B'), Vector(Key.Down))
    assertEquals(decode(27, '[', '5', '~'), Vector(Key.PageUp))
    assertEquals(decode(27, '[', '6', '~'), Vector(Key.PageDown))
    assertEquals(decode(27, '[', 'H'), Vector(Key.Home))
    assertEquals(decode(27, '[', '4', '~'), Vector(Key.End))
    assertEquals(decode(27, '[', 'Z'), Vector(Key.BackTab))
    assertEquals(decode(27, '[', '3', '~'), Vector(Key.Delete))
  }

  test("a lone escape is the Escape key once the stream goes quiet") {
    val d = new KeyDecoder
    assertEquals(d.feed(27), Vector.empty)
    assertEquals(d.timeout(), Vector(Key.Escape))
    assertEquals(d.timeout(), Vector.empty)
  }

  test("an unknown CSI sequence is swallowed whole, never typed") {
    assertEquals(decode(27, '[', '1', ';', '5', 'C', 'x'), Vector(Key.Char('x')))
  }

  test("escape followed by a plain key is Escape then the key") {
    assertEquals(decode(27, 'q'), Vector(Key.Escape, Key.Char('q')))
  }

  test("a lone escape expires after the quiet time; a sequence that keeps coming does not") {
    val d = new KeyDecoder
    assertEquals(d.feed(27, now = 1000L), Vector.empty)
    assertEquals(d.expire(1000L + KeyDecoder.Quiet - 1), Vector.empty)
    assertEquals(d.expire(1000L + KeyDecoder.Quiet), Vector(Key.Escape))
    assertEquals(d.expire(5000L), Vector.empty)
    assertEquals(d.feed(27, now = 2000L) ++ d.feed('['), Vector.empty)
    assertEquals(d.expire(2000L + KeyDecoder.Quiet - 1), Vector.empty)
    assertEquals(d.feed('A'), Vector(Key.Up))
  }

  test("a sequence that stalls is dropped at the quiet time, not typed") {
    val d = new KeyDecoder
    assertEquals(d.feed(27, now = 3000L) ++ d.feed('['), Vector.empty)
    assertEquals(d.expire(3000L + KeyDecoder.Quiet), Vector.empty)
    assertEquals(d.feed('A'), Vector(Key.Char('A')))
  }

  test("a wheel notch arrives as one write of several arrows, each delivered") {
    assertEquals(decode(27, '[', 'A', 27, '[', 'A', 27, '[', 'A'), Vector(Key.Up, Key.Up, Key.Up))
  }

  test("keys typed back to back decode independently") {
    assertEquals(decode('j', 27, '[', 'B', 'k'), Vector(Key.Char('j'), Key.Down, Key.Char('k')))
  }
}
