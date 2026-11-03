package io.eezo.sbt.dev

import java.io.File

/** The child a schema command runs as, with a shell standing in for the JVM: what is read, and that
  * the timeout is on the process rather than on its stdout.
  */
class SchemaCommandsSuite extends munit.FunSuite {

  private val sh = new File("/bin/sh")

  private def run(script: String, timeoutSeconds: Long): Outcome =
    SchemaCommands.execute(
      java.util.Arrays.asList(sh.getPath, "-c", script),
      new File("."),
      SchemaCommand.Status,
      timeoutSeconds
    )

  test("the answer is read whole: the JSON on stdout parsed, stderr kept, the exit code as is") {
    assume(sh.isFile, "needs /bin/sh")
    val o =
      run("""echo '{"command":"status","inSync":true,"changes":[]}'; echo noise >&2; exit 1""", 10L)
    assertEquals(o.code, 1)
    assertEquals(o.json.flatMap(_("inSync").bool), Some(true))
    assertEquals(o.err, Vector("noise"))
    assert(!o.ok)
  }

  test("a child that hangs with its stdout open is killed at the timeout and answers -1") {
    assume(sh.isFile, "needs /bin/sh")
    val started = System.currentTimeMillis
    val o       = run("echo started; exec sleep 30", 1L)
    assertEquals(o.code, -1)
    assertEquals(o.out, Vector("started"))
    assertEquals(o.err.lastOption, Some("gave up after 1s"))
    assert(System.currentTimeMillis - started < 15000L, "the timeout did not fire")
  }
}
