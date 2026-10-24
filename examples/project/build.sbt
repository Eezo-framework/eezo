// `EezoVersion` lives in `project/project/` because `plugins.sbt` needs it: a plugin has to be
// resolved before the meta build's own sources compile, so a helper used from `plugins.sbt` has to
// come from one level further up. This line compiles that same file into the meta build as well,
// so `build.sbt` reads the version from the one definition rather than from a second copy of it.
Compile / unmanagedSources += baseDirectory.value / "project" / "EezoVersion.scala"

// The framework's `Dependencies`, compiled into this meta build as well, so an example whose suite
// needs what a module's suite needs, a real Postgres through testcontainers, takes the same version
// instead of a second literal that falls behind: a Docker Engine upgrade once broke the examples
// alone for that reason.
Compile / unmanagedSources += baseDirectory.value.getParentFile.getParentFile / "project" / "Dependencies.scala"
