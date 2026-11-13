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

  private def layout(): (java.io.File, java.io.File, java.io.File) = {
    val root   = java.nio.file.Files.createTempDirectory("eezo-mod").toFile
    val hello  = new java.io.File(root, "hello")
    val nested = new java.io.File(hello, "src")
    val todo   = new java.io.File(root, "todo")
    nested.mkdirs(): Unit
    todo.mkdirs(): Unit
    (root, hello, todo)
  }

  private def projects(hello: java.io.File, todo: java.io.File): Seq[ModuleChoice.Project] =
    Seq(ModuleChoice.Project("hello", hello), ModuleChoice.Project("todo", todo))

  test("a module directory selects that project") {
    val (root, hello, todo) = layout()
    assertEquals(
      ModuleChoice.choose(hello, root, projects(hello, todo), None),
      ModuleChoice.Selected("hello")
    )
  }

  test("a nested path under a module selects that module") {
    val (root, hello, todo) = layout()
    val nested              = new java.io.File(hello, "src")
    assertEquals(
      ModuleChoice.choose(nested, root, projects(hello, todo), None),
      ModuleChoice.Selected("hello")
    )
  }

  test("the build root with one project selects it") {
    val (root, hello, _) = layout()
    assertEquals(
      ModuleChoice.choose(root, root, Seq(ModuleChoice.Project("hello", hello)), None),
      ModuleChoice.Selected("hello")
    )
  }

  test("the build root with several projects names them") {
    val (root, hello, todo) = layout()
    assertEquals(
      ModuleChoice.choose(root, root, projects(hello, todo), None),
      ModuleChoice.Ambiguous(Seq("hello", "todo"))
    )
  }

  test("an explicit id that is not a project is unknown") {
    val (root, hello, todo) = layout()
    assertEquals(
      ModuleChoice.choose(hello, root, projects(hello, todo), Some("nope")),
      ModuleChoice.Unknown("nope")
    )
  }

  test("an explicit id overrides the directory") {
    val (root, hello, todo) = layout()
    assertEquals(
      ModuleChoice.choose(hello, root, projects(hello, todo), Some("todo")),
      ModuleChoice.Selected("todo")
    )
  }

  test("one eezo project at the aggregate is that project") {
    assertEquals(
      ModuleChoice.forConsole(ModuleChoice.Selected("root"), Seq("hello"), "root"),
      Right("hello")
    )
    assertEquals(
      ModuleChoice.forConsole(ModuleChoice.Ambiguous(Seq("hello", "todo")), Seq("hello"), "root"),
      Right("hello")
    )
  }

  test("several eezo projects at the aggregate stay a refusal") {
    assertEquals(
      ModuleChoice.forConsole(
        ModuleChoice.Ambiguous(Seq("hello", "todo")),
        Seq("hello", "todo"),
        "root"
      ),
      Left(Seq("hello", "todo"))
    )
  }

  test("a project without the plugin is not replaced by a sibling") {
    assertEquals(
      ModuleChoice.forConsole(ModuleChoice.Selected("reminders"), Seq("hello"), "root"),
      Left(Seq("hello"))
    )
  }
}
