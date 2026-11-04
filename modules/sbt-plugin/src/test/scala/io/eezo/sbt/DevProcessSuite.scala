package io.eezo.sbt

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The dev loop's secret: one per sbt session, handed to every forked child unless sbt's own
  * environment already names one; and the values the console took, handed to every fork after. The
  * fork itself needs a real JVM and a port, and is exercised by hand instead.
  */
class DevProcessSuite extends munit.FunSuite {

  test("an EEZO_SECRET already in sbt's environment wins, and the child inherits it") {
    assertEquals(DevProcess.secretFor(inherited = Some("set by the developer")), None)
  }

  test("an unset EEZO_SECRET gets the session's generated value") {
    assert(DevProcess.secretFor(inherited = None).isDefined)
  }

  test("the generated value is the same on every restart of one session") {
    assertEquals(DevProcess.secretFor(inherited = None), DevProcess.secretFor(inherited = None))
  }

  test("the generated value is 32 random bytes and parses as at least 32 UTF-8 bytes") {
    val generated = DevProcess.secretFor(inherited = None).getOrElse(fail("no generated secret"))
    assertEquals(Base64.getDecoder.decode(generated).length, 32)
    assert(generated.getBytes(StandardCharsets.UTF_8).length >= 32, generated.length)
  }

  /** What a fork's environment holds, from sbt's own `inherited` and the console's `supplied`. */
  private def environment(
      inherited: Map[String, String],
      supplied: Map[String, String]
  ): Map[String, String] = {
    val env = new java.util.HashMap[String, String]
    inherited.foreach { case (k, v) => env.put(k, v) }
    DevProcess.environment(env, piped = true, supplied)
    val out = Map.newBuilder[String, String]
    env.forEach((k, v) => out += k -> v)
    out.result()
  }

  test("the values the console took reach the child, over an inherited variable of the same name") {
    val env = environment(
      Map("SHOP_PORT" -> "eighty", "HOME"     -> "/home/dev"),
      Map("SHOP_PORT" -> "8080", "STRIPE_KEY" -> "sk_test")
    )
    assertEquals(env.get("SHOP_PORT"), Some("8080"))
    assertEquals(env.get("STRIPE_KEY"), Some("sk_test"))
    assertEquals(env.get("HOME"), Some("/home/dev"))
    assertEquals(env.get(DevProcess.MarkersVar), Some("1"))
    assertEquals(env.get(DevProcess.SecretVar), DevProcess.secretFor(inherited = None))
  }

  test("a supplied value never sets a variable of the framework's") {
    val env = environment(
      Map(DevProcess.SecretVar -> "inherited secret"),
      Map(DevProcess.SecretVar -> "typed", DevProcess.MarkersVar -> "", "EEZO_PORT" -> "1")
    )
    assertEquals(env.get(DevProcess.SecretVar), Some("inherited secret"))
    assertEquals(env.get(DevProcess.MarkersVar), Some("1"))
    assertEquals(env.get("EEZO_PORT"), None)
  }

  test("the variable is the one the http layer reads") {
    assertEquals(DevProcess.SecretVar, "EEZO_SECRET")
  }
}
