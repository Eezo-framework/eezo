package io.eezo.core.support

import java.io.ByteArrayOutputStream
import java.io.PrintStream

object Captured {

  def captured(body: => Int): (Int, String, String) = {
    val out  = new ByteArrayOutputStream()
    val err  = new ByteArrayOutputStream()
    val code = Console.withOut(new PrintStream(out)) {
      Console.withErr(new PrintStream(err)) { body }
    }
    (code, out.toString, err.toString)
  }
}
