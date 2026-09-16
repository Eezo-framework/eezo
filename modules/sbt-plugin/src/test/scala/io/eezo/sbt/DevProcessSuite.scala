package io.eezo.sbt

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The dev loop's secret: one per sbt session, handed to every forked child unless sbt's own
  * environment already names one. The fork itself needs a real JVM and a port, and is exercised by
  * hand instead.
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

  test("the variable is the one the http edge reads") {
    assertEquals(DevProcess.SecretVar, "EEZO_SECRET")
  }
}
