// `EezoVersion` lives in `project/project/` because `plugins.sbt` needs it: a plugin has to be
// resolved before the meta build's own sources compile, so a helper used from `plugins.sbt` has to
// come from one level further up. This line compiles that same file into the meta build as well,
// so `build.sbt` reads the version from the one definition rather than from a second copy of it.
Compile / unmanagedSources += baseDirectory.value / "project" / "EezoVersion.scala"
