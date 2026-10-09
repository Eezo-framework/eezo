// A throwaway application that never compiles a line of Scala.
//
// Every step in `test` runs `Compile / eezoGenerateRoutes` rather than `compile`, so this test
// resolves no eezo artifact and starts no Scala 3 compiler: what it isolates is the generated
// file's stability, and a compile downstream of it would only add ways to be slow and ways to be
// red for another reason. `eezo/generated-compiles` is the test that compiles what this one reads.
//
// What is asserted is one property: an evaluation of the generator leaves `Routes.scala`'s bytes
// and modification time alone unless something that reaches the table changed. That is what keeps
// the dev loop from invalidating the compile that follows it, and `EezoPlugin` defends it twice
// over, with `FileFunction.cached` skipping the body and `writeIfChanged` declining to write when
// a body that did run produced the same text again.
//
// What is deliberately *not* asserted is which of the two did the defending on any given step,
// because inside one sbt session nothing can tell them apart:
//
//   - the generator's own `log.warn` goes to `target/streams/.../out`, which sbt holds in memory
//     and flushes at session close, so it reads as empty throughout a scripted run;
//   - `FileFunction.cached`'s `in-cache` and `out-cache` stores are rewritten on a hit exactly as
//     they are on a miss;
//   - any tampering that would betray a body run, truncating the table or editing it, changes the
//     output's hash, which is itself a cache input, and so forces the very run it meant to detect.
//
// The consequence is worth stating plainly. Removing `FileFunction.cached` while leaving
// `writeIfChanged` in place keeps this test green, and costs a full rescan of the source tree per
// keystroke rather than a wrong file. Removing `writeIfChanged` is caught here, by the step that
// changes an input which reaches nothing.
//
// The assertions are tasks rather than scripted's own file commands because the table's path
// contains the Scala binary version, and a `test` file that spelled `target/scala-2.12/...` out
// would break on a toolchain move that has nothing to do with what is being tested.

lazy val root = (project in file(".")).enablePlugins(EezoPlugin)

val routesFile = settingKey[File]("Where the generator writes the route table.")

val probeFile = settingKey[File]("Where the table's last recorded state is kept.")

val recordRoutes = taskKey[Unit]("Records the table's modification time and contents.")

val assertRoutesExist = taskKey[Unit]("Fails unless the table has been written.")

val assertRoutesUntouched =
  taskKey[Unit]("Fails if the table has moved or changed since the last recordRoutes.")

val assertRoutesRewritten =
  taskKey[Unit]("Fails unless the table has been written again since the last recordRoutes.")

val assertTableHasIndex = taskKey[Unit]("Fails unless the table carries the handwritten GET /.")

val assertTableHasShow =
  taskKey[Unit]("Fails unless the table carries the handwritten GET /widgets/:id.")

val assertTableHasWidget = taskKey[Unit]("Fails unless the table carries the Widget model's line.")

val deleteRoutes = taskKey[Unit]("Deletes the generated table.")

val truncateRoutes = taskKey[Unit]("Empties the generated table without deleting it.")

routesFile := (Compile / sourceManaged).value / "io" / "eezo" / "generated" / "Routes.scala"

probeFile := target.value / "routes-probe"

recordRoutes := Probe.write(probeFile.value, routesFile.value)

assertRoutesExist := {
  val routes = routesFile.value
  if (!routes.isFile) sys.error(s"the generator wrote no $routes")
}

assertRoutesUntouched := {
  val routes = routesFile.value
  val state  = Probe.read(probeFile.value)
  if (routes.lastModified() != state.written)
    sys.error(
      s"$routes was rewritten when nothing that reaches the table changed. That moves the " +
        "timestamp of the one file the compile after it is guaranteed to read, so a watch loop " +
        "recompiles on every keystroke."
    )
  if (IO.read(routes) != state.contents)
    sys.error(s"$routes changed contents when nothing that reaches the table changed.")
}

assertRoutesRewritten := {
  val routes = routesFile.value
  if (routes.lastModified() == Probe.read(probeFile.value).written)
    sys.error(s"$routes was not regenerated, so the change to its inputs never reached the table.")
}

assertTableHasIndex := Probe.mustContain(routesFile.value, "req => app.Index.index(req)")

assertTableHasShow :=
  Probe.mustContain(routesFile.value, "io.eezo.http.PathPattern.parse(\"/widgets/:id\")")

assertTableHasWidget :=
  Probe.mustContain(routesFile.value, "io.eezo.http.Resource.routesOf[models.Widget](store)")

deleteRoutes := IO.delete(routesFile.value)

truncateRoutes := IO.write(routesFile.value, "")
