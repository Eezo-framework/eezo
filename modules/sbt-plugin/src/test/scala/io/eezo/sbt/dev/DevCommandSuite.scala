package io.eezo.sbt.dev

/** The refusals, whole: each names the way out. */
class DevCommandSuite extends munit.FunSuite {

  test("behind a thin client the console names the channel and the two ways to run") {
    assertEquals(
      DevCommand.clientRefusal(Some("network-1")),
      "[eezo dev] eezoDev runs on sbt's own terminal, and this sbt is a client of a server " +
        "(channel network-1). Run `eezo dev`, or `sbt --server eezoDev` on an sbt 2 project; " +
        "`eezoDev --once` builds, starts and stops from anywhere."
    )
    assert(DevCommand.clientRefusal(None).contains("(channel ?)"))
  }

  test("without a terminal the console names the three forms that need none") {
    assertEquals(
      DevCommand.terminalRefusal,
      "[eezo dev] eezoDev needs a terminal to draw on. `eezoDev --plain` runs the same loop " +
        "printing lines and `eezoDev --json` one JSON object per line, for a pipe or an agent; " +
        "`eezoDev --once` builds, starts and stops."
    )
  }

  test("at a project without the plugin the console names the projects that have it") {
    val head =
      "[eezo dev] eezoDev runs in a project that enables EezoPlugin, and the current one does not."
    assertEquals(DevCommand.projectRefusal(Nil), head)
    assertEquals(DevCommand.projectRefusal(Seq("hello")), s"$head `project hello` first.")
    assertEquals(
      DevCommand.projectRefusal(Seq("hello", "todo")),
      s"$head One of `project hello`, `project todo` first."
    )
  }
}
