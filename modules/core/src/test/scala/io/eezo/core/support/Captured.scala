package io.eezo.core.support

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** What a `Dispatch.run` printed, for the entry trait suites of every module: `core`'s own, and
  * each edge's through `test->test` on `core`.
  */
object Captured {

  /** Runs `body` with stdout and stderr captured, and returns the code and what each received. */
  def captured(body: => Int): (Int, String, String) = {
    val out  = new ByteArrayOutputStream()
    val err  = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      Console.withErr(new PrintStream(err)) { body }
    }
    (code, out.toString, err.toString)
  }
}
