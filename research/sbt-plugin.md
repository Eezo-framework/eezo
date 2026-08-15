# Research: how eezo publishes an sbt plugin, and what `sourceGenerators` requires

Resolves [Eezo-framework/eezo#108](https://github.com/Eezo-framework/eezo/issues/108).

Date of investigation: 2026-08-15. Every version, coordinate and artifact claim
was verified on that date against `maven-metadata.xml` and the published POMs on
Maven Central, and every behavioural claim about sbt was verified either against
sbt's own source on GitHub, at a named branch and file, or by running it.

**Empirical basis.** The behavioural half of this document was measured, not
read. Toolchain: **sbt 1.12.14** (launcher 1.12.1 from SDKMAN), **Scala 3.8.4**,
Temurin **JDK 26+35**, on an **Apple M4 Pro, macOS 15.7.4**, which is the same
machine as `research/http-server.md` and `research/build-reload.md`. Two throwaway
sbt projects were built for this ticket: one that registers three source
generators with different caching and output strategies, and one that registers
two generators with different watch declarations and is driven under a live `~`
session. Reproduction is in Appendix A. Numbers marked **measured** came off that
machine; everything else carries its source inline.

The prior art in `../skiff` at `modules/sbt-plugin/` was read as evidence, not as
an oracle. Where this document disagrees with it, it says so and says why.

---

## 1. The findings that reframe the question

There are five, and only the first is about publishing.

### 1.1 Cross-publishing to sbt 1 and sbt 2 is two settings, is already done by the plugin eezo's own release depends on, and is purely additive

The ticket treats "does eezo cross-publish?" as an open strategic question. It is
not. The mechanism is a `crossScalaVersions` pair and a `pluginCrossBuild /
sbtVersion` match, both applied to the plugin subproject only, and the ordinary
`+` cross-build command does the rest. sbt's own documentation gives the exact
snippet, and `sbt-ci-release`, which eezo's `project/plugins.sbt` already depends
on, is built exactly that way.

Verified on Maven Central: `sbt-ci-release` **1.12.0** exists as both
[`com.github.sbt:sbt-ci-release_2.12_1.0`](https://repo1.maven.org/maven2/com/github/sbt/sbt-ci-release_2.12_1.0/1.12.0/)
and
[`com.github.sbt:sbt-ci-release_sbt2_3`](https://repo1.maven.org/maven2/com/github/sbt/sbt-ci-release_sbt2_3/1.12.0/),
with the same `lastUpdated` stamp `20260706215715` in both `maven-metadata.xml`
files, which is one release producing two artifacts.

The decisive property is that **the two artifacts have different artifactIds**.
Adding the sbt 2 axis later creates a new coordinate and breaks nothing that
already resolves; dropping it later removes a coordinate nobody was forced to
use. There is no compatibility cliff either way, so this decision is reversible
and should not be agonised over. See §2.

### 1.2 A source generator is *not* cached by sbt, in either sbt 1 or sbt 2, and in sbt 2 the plausible fix is a trap

Measured, sbt 1.12.14, one project with two generators, one naive and one wrapped
in `FileFunction.cached`, over three evaluations of `Compile / managedSources`
(two `compile` invocations plus one `show`):

| generator | times the body ran |
|---|---|
| naive `Def.task { IO.write(...); Seq(f) }` | **3** |
| `FileFunction.cached(cacheDir, FileInfo.hash) { ... }` | **1** |

This is not a subtlety, it is the whole contract. `Compile / sourceGenerators` is
a `SettingKey[Seq[Task[Seq[File]]]]`, and `managedSources` is
`generate(sourceGenerators)`, which sbt defines as
`generators { _.join.map(_.flatten) }`
([`Defaults.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala)).
A join runs every generator, every time the task is evaluated. sbt's own
"Generating files" page says so in one line, and it is the only performance
advice on the page: generators "should avoid regenerating files upon each call"
and outputs "should be cached based on the input values"
([Generating files](https://www.scala-sbt.org/1.x/docs/Howto-Generating-Files.html)).

The trap is in sbt 2. sbt 2's migration guide states that "all tasks are cached
by default"
([Migrating from sbt 1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html)),
and a reader could conclude the caching problem has been solved for them. Read
against sbt's source, that statement is about the `:=` assignment form. In
`Def.scala` on the `2.0.x` branch, `Def.task` expands with `cached = false` and
only `Def.cachedTask` and `Def.taskIf` expand with `cached = true`
([`Def.scala`, 2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/Def.scala)),
while `TaskMacro.taskMacroImpl(t, key)`, the path taken by `key := body`,
computes `cached = ContextUtil.isTaskCacheByDefault && !isUncacheApplied &&
cl.nonEmpty`
([`TaskMacro.scala`, 2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/std/TaskMacro.scala)).

So the canonical idiom, `Compile / sourceGenerators += Def.task { ... }.taskValue`,
is **uncached in sbt 2 as well**. And the obvious "fix", promoting the generator
to a named `TaskKey` assigned with `:=` so that sbt 2 caches it, makes things
worse rather than better, because sbt's own migration guide states the hazard
plainly: "When sbt 2 restores a task result from its disk cache, it returns the
cached value without re-executing the task body. Any side effect (e.g. writing
files, syncing mappings) is silently skipped." A generator whose entire purpose is
the side effect of writing a file will therefore, after a `clean`, return a list
of files that do not exist.

The instruction that follows is the same for both sbt versions: **do the caching
inside the generator with `FileFunction.cached`, and do not rely on the build
tool's own caching.** `sbt.util.FileFunction` exists with an identical `cached`
signature on both the `1.12.x` and `develop` branches
([1.12.x](https://github.com/sbt/sbt/blob/1.12.x/util-tracking/src/main/scala/sbt/util/FileFunction.scala),
[develop](https://github.com/sbt/sbt/blob/develop/util-tracking/src/main/scala/sbt/util/FileFunction.scala)),
so one implementation serves both.

### 1.3 sbt's `~` will not trigger on the generator's inputs, and `fileInputs` does not fix it

This is the finding that most changes what eezo builds. Measured, live `~compile`
session, sbt 1.12.14:

| edit | build triggered? | generators re-ran? |
|---|---|---|
| `conf/routes`, read by a generator that declares nothing | **no** | no |
| `conf2/routes.txt`, read by a generator with `gen2 / fileInputs += <glob>` | **no** | no |
| `src/main/scala/Main.scala` | **yes** | yes, both |
| `conf/routes`, after adding `Compile / compile / watchTriggers += <glob>` | **yes** | yes |

The negative result in row two is the interesting one. Declaring the generator's
input with `fileInputs` on the generator's own task key is not enough, because
`sourceGenerators` is a *setting* holding anonymous `Task` values and sbt's watch
computes its monitored set from the task graph it can see. The remedy that works
is `watchTriggers` scoped to the task being watched, which sbt documents as being
for exactly this case: files "that should task trigger evaluation but that the
task does not directly depend on"
([Triggered Execution](https://www.scala-sbt.org/1.x/docs/Triggered-Execution.html)).

Two consequences. First, if eezo's route table is derived from `.scala` files
under `src/main/scala`, it is watched for free, because those files are already
`unmanagedSources` of `compile`. Second, if eezo ever derives anything from a
non-Scala file, the plugin must add a `watchTriggers` entry or the file will
silently stop being a build input under `~`. Silently is the operative word: the
failure mode is a stale generated file and no error.

### 1.4 The Skiff premise about `src_managed` is true, and eezo should still not copy Skiff's answer

Skiff writes generated sources to `project/.skiff/` and registers that directory
in `Compile / managedSourceDirectories`, on the stated ground that
`target/scala-*/src_managed/` only exists after a first compile. The premise is
correct and was measured: after `sbt clean`, `target/scala-3.8.4/` does not exist
at all, while a file written to `project/.eezo/` survives untouched.

What the premise omits is that **every import path that an IDE actually uses runs
the generators**. sbt's own BSP handler builds `buildTarget/sources` from
`managedSources.value`, which is the join in §1.2, and reports
`managedSourceDirectories` as `SourceItem`s with `generated = true`
([`BuildServerProtocol.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala)).
Bloop's sbt plugin forces the same task with a comment that says why: "Force
source generators on this task manually", `val _ = Keys.managedSources.value`
([`SbtBloop.scala`](https://github.com/scalacenter/bloop/blob/main/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala)).
IntelliJ's extractor has a dedicated `generateManagedSourcesTaskDef` that logs
"Generating managed sources in ... / Compile, ... / Test" and evaluates
`Compile / managedSources` and `Test / managedSources`
([`ProjectExtractor.scala`](https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/dump/extract/ProjectExtractor.scala)).

So the cold-open gap Skiff is defending against closes on the first import, not
on the first compile, and paying for it with a generated artefact that `clean`
cannot remove is a bad trade. Details and the residual uncertainty are in §4.

### 1.5 The plugin and eezo's dev loop must not be the same thing, and sbt's own anti-entropy explains the double rebuild `build-reload.md` measured

`research/build-reload.md` §1.5 recommends that eezo "drive the compile itself
with a debounce, not delegate to `~`", on the strength of a measured double
rebuild: at 204 sources, an editor-style rename touching 51 files compiles in
215 ms driven directly but takes 774 to 1101 ms under `~`. That measurement now
has a mechanism. sbt's `watchAntiEntropy` defaults to 500 ms and its own docstring
defines it as "The minimum delay between build triggers **for the same file**"
([`Watch.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Watch.scala)).
A 51-file rename writes 51 *different* files, so the anti-entropy window
suppresses nothing: the first write triggers a build and a later write triggers
another. sbt's anti-entropy is a de-duplicator, not a debouncer, and no setting
turns it into one.

That vindicates the recommendation, and it also fixes the plugin's job
description. **The plugin registers the generator. The dev server owns the loop.**
The plugin must keep working under plain `sbt compile` and under `~compile`,
because those are what a user without `eezo dev` will run, and it must not
contain the debounce, the file watcher or the classloader swap. The seam between
them is discussed with the measured client latencies in §7.

---

## 2. Question 1: how an sbt plugin gets cross-published for sbt 1 and sbt 2

### 2.1 The two coordinate schemes, from the published POMs

The artifact naming is generated in `Defaults.sbtPluginExtra`, which branches on
the sbt version
([`Defaults.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala)):

```scala
def sbtPluginExtra(m: ModuleID, sbtV: String, scalaV: String): ModuleID =
  partialVersion(sbtV) match {
    case Some((0, _)) | Some((1, _)) =>
      m.extra(
          PomExtraDependencyAttributes.SbtVersionKey -> sbtV,
          PomExtraDependencyAttributes.ScalaVersionKey -> scalaV
        )
        .withCrossVersion(Disabled())
    case Some(_) =>
      // this produces a normal suffix like _sjs1_2.13
      val prefix = s"sbt${binarySbtVersion(sbtV)}_"
      m.cross(CrossVersion.binaryWith(prefix, ""))
    case None =>
      sys.error(s"unknown sbt version $sbtV")
  }
```

The sbt 1 branch is the Ivy inheritance. sbt's own PR that introduced the sbt 2
scheme is blunt about it: "The extra attribute is a vestige from the days when sbt
plugins were published on Ivy repos"
([sbt/sbt#7671](https://github.com/sbt/sbt/pull/7671)). The sbt 2 branch is an
ordinary Maven suffix.

What that produces, read off the two published POMs of `sbt-ci-release` 1.12.0:

| | sbt 1 artifact | sbt 2 artifact |
|---|---|---|
| artifactId | `sbt-ci-release_2.12_1.0` | `sbt-ci-release_sbt2_3` |
| Scala dependency | `scala-library` **2.12.21**, `provided` | `scala3-library_3` **3.8.4**, `provided` |
| sbt dependency | `org.scala-sbt:sbt` **1.5.8**, `provided` | `org.scala-sbt:sbt` **2.0.0**, `provided` |
| POM extras | `<extraDependencyAttributes>` block plus `<scalaVersion>2.12</scalaVersion>` and `<sbtVersion>1.0</sbtVersion>` properties | none |
| plugin dependencies | `sbt-dynver_2.12_1.0`, `sbt-pgp_2.12_1.0`, encoded in the extras block | `sbt-dynver_sbt2_3`, `sbt-pgp_sbt2_3`, as ordinary Maven dependencies |

Note that the sbt version in the sbt 1 coordinate is the *binary* sbt version,
`1.0`, and that the `sbt` dependency it declares is `1.5.8`, not the sbt that
built it. That number is whatever `pluginCrossBuild / sbtVersion` was set to, and
it is the **floor**: it is the oldest sbt the plugin promises to work on. Choosing
a low floor is how a plugin stays usable by people on older sbt 1 lines.

`_sbt2_3` decomposes as `sbt` + `binarySbtVersion("2.0.0")` = `2`, then `_`, then
the Scala binary version `3`. Milestone builds carried the full version instead:
Maven Central still holds `sbt-git_sbt2.0.0-M2_3`, `_sbt2.0.0-M3_3` and
`_sbt2.0.0-M4_3` alongside the final `sbt-git_sbt2_3`.

### 2.2 The mechanism, and which of the three candidates is the live one

The ticket asks whether it is `pluginCrossBuild / sbtVersion`, `crossSbtVersions`,
`^publish`, or something else. All three exist; only the first is current.

- **`crossSbtVersions` plus `^` and `^^`** is the sbt 0.13-to-1.x mechanism,
  documented at [Cross building plugins](https://www.scala-sbt.org/1.x/docs/Cross-Build-Plugins.html)
  with `crossSbtVersions := Vector("1.2.8", "0.13.18")`. It works because 0.13 and
  1.x use different Scala binary versions, and the same page already recommends
  driving it off `crossScalaVersions` and `pluginCrossBuild / sbtVersion` instead.
  It is not what anyone uses for the 1-to-2 transition. sbt keeps
  `crossSbtVersions := Vector((pluginCrossBuild / sbtVersion).value)` as a derived
  default, so leaving it alone is correct.
- **`pluginCrossBuild / sbtVersion` plus ordinary `crossScalaVersions` and `+`**
  is the current mechanism, for both sbt 1 and sbt 2 as the *building* sbt.
- **`projectMatrix` with `jvmPlatform(scalaVersions = ...)`** is the sbt 2 spelling
  of the same thing.

sbt's migration guide gives both, verbatim
([Migrating from sbt 1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html)):

```scala
// using sbt 2.x
lazy val plugin = (projectMatrix in file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-vimquit",
  )
  .jvmPlatform(scalaVersions = Seq("3.8.4", "2.12.20"))
```

```scala
// using sbt 1.x
lazy val scala212 = "2.12.20"
lazy val scala3 = "3.8.4"
ThisBuild / crossScalaVersions := Seq(scala212, scala3)

lazy val plugin = (project in file("plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-vimquit",
    (pluginCrossBuild / sbtVersion) := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.5.8"
        case _      => "2.0.6"
      }
    },
  )
```

with the sentence that matters for eezo: "Use sbt 1.10.2 or later, if you want to
cross build using sbt 1.x." eezo is on **1.12.14**, so eezo's existing build can
already produce both artifacts with no build-tool migration.

`sbt-ci-release`'s own `build.sbt` is the same shape, at the current versions
([source](https://github.com/sbt/sbt-ci-release/blob/main/build.sbt)):

```scala
lazy val scala212 = "2.12.21"
lazy val scala3 = "3.8.4"
...
crossScalaVersions := Seq(scala212, scala3),
scalacOptions ++= {
  scalaBinaryVersion.value match {
    case "2.12" => "-Xsource:3" :: "-release:8" :: Nil
    case _      => Nil
  }
},
(pluginCrossBuild / sbtVersion) := {
  scalaBinaryVersion.value match {
    case "2.12" => "1.5.8"
    case _      => "2.0.0"
  }
},
```

### 2.3 The publish command, and why it does not disturb eezo's other eight modules

There is no `^publish` in this picture. The command is the ordinary Scala
cross-build `+`, and eezo already issues it: `sbt-ci-release`'s `ci-release`
command runs `+publishSigned` when the tag is a plain version
([`CiReleasePlugin.scala`](https://github.com/sbt/sbt-ci-release/blob/main/plugin/src/main/scala/com/geirsson/CiReleasePlugin.scala)).

The question this raises is what `+publishSigned` does to a build where eight
modules are Scala 3.8.4 only and one is cross-built. Read from `Cross.scala`
([1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Cross.scala)):
when the argument parses as a *task* (which `publishSigned` does), sbt takes the
`Right` branch, which builds `keysByVersion` by flat-mapping each key over
`crossVersions(extracted, project)` and grouping. Each project therefore runs the
task only at the versions in **its own** `crossScalaVersions`. The blanket-switch
branch with the "not all sub projects have the same cross build configuration"
warning is the `Left` branch, taken only when the argument is a bare command.

So `+publishSigned` on eezo's build publishes the eight Scala 3.8.4 modules once
at 3.8.4, and the plugin twice, once at 2.12.21 and once at 3.8.4. That is exactly
the desired behaviour and it needs no special handling.

### 2.4 What eezo would have to write, and what it costs

The cost is not the two settings. It is that the plugin's shared source must
compile under both Scala 2.12.21 and Scala 3.8.4. sbt documents the escape hatch:
version-specific source directories `src/main/scala-2.12/` and `src/main/scala-3/`
holding an object with the same name, conventionally `PluginCompat`, with the
[`sbt2-compat`](https://github.com/sbt/sbt2-compat) plugin supplying pre-built
shims for the APIs that broke. The one that breaks most often is `Classpath`,
which sbt 2 redefined as `Seq[Attributed[xsbti.HashedVirtualFileRef]]` instead of
`Seq[Attributed[File]]`.

For eezo specifically the exposure is small, because the generator's work is
reading `.scala` files, deriving a route table and writing a `.scala` file. It
never touches `Classpath`. It needs `sbt.io.IO`, `java.io.File`, `sbt.Keys` and
string manipulation, all of which are common to both. What it must avoid is Scala
3 syntax, which means the plugin subproject cannot inherit eezo's
`commonSettings`: `-no-indent`, `-Wunused:all` with `-Werror` and `-release 25`
are not a valid 2.12 flag set. `../skiff` hit exactly this and reset
`scalacOptions` wholesale on its plugin module, and `sbt-ci-release` does the same
thing conditionally.

### Recommendation for question 1

**Target sbt 1.x as the shipped default, and cross-publish the sbt 2 artifact from
the same source from day one**, using `crossScalaVersions := Seq("2.12.21",
"3.8.4")` and a `pluginCrossBuild / sbtVersion` match on the plugin subproject
only. Set the sbt 1 floor to a genuinely old sbt 1 (`1.5.8`, matching
`sbt-ci-release`) rather than to 1.12.14, so that the floor is a compatibility
promise rather than an accident of eezo's own build. Set the sbt 2 axis to
**2.0.6**, the current release. Do not use `crossSbtVersions`, `^` or `^^`. Do not
use `projectMatrix`, which would require eezo to move the plugin into its own
subdirectory to keep the synthetic root project from picking up `src/`, for no
gain while eezo's build is sbt 1.

Write the plugin in the Scala 2.12-and-3 common subset and add `-Xsource:3` on the
2.12 axis so the compiler enforces it. Do not add `sbt2-compat` until something
actually needs a shim; the generator, as scoped, needs none.

**If the shared-source constraint turns out to bite**, drop the sbt 2 axis and
ship sbt 1 only. §1.1 establishes that this is reversible in both directions,
because the two artifacts have different artifactIds. What eezo must not do is
ship for sbt 2 only: sbt 1.12.15 is current and actively maintained, eezo's own
build is on it, and `research/build-reload.md` §10 already recommends sbt 1.12.x
for the dev loop with sbt 2 as a follow-up.

sbt-revolver is a non-issue here and should stop being cited as one.
`io.spray:sbt-revolver` exists on Maven Central only as `sbt-revolver_2.12_1.0`,
with no sbt 2 artifact, but `research/build-reload.md` §10 already ruled it out
and told eezo to implement fork-and-restart itself. Its absence from sbt 2
constrains nothing eezo depends on.

---

## 3. Question 2: what `Compile / sourceGenerators` requires of a generator

### 3.1 The type, which is the same in sbt 1 and sbt 2

```scala
val sourceGenerators =
  settingKey[Seq[Task[Seq[File]]]]("List of tasks that generate sources.")
```

identical on
[`Keys.scala` 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Keys.scala)
and
[`Keys.scala` develop](https://github.com/sbt/sbt/blob/develop/main/src/main/scala/sbt/Keys.scala).
The element is a `Task`, not a value, which is why the registration idiom ends in
`.taskValue` rather than `.value`. `taskValue` survives into sbt 2
([`Def.scala`, 2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/Def.scala)),
so the registration line itself is source-compatible across both:

```scala
Compile / sourceGenerators += Def.task { ... : Seq[File] }.taskValue
```

The output type is `Seq[File]` and there is no alternative. sbt 2 introduced
`VirtualFileRef`, `VirtualFile` and `HashedVirtualFileRef`, and changed
`Classpath` to use them, but it did **not** change `sourceGenerators`. sbt 2's
`managedSources` converts afterwards into a separate `managedSourcesVF` key. So a
generator returning `Seq[File]` is correct on both.

### 3.2 What sbt does with the result

sbt 1.12.x and sbt 2 both define:

```scala
def generate(generators: SettingKey[Seq[Task[Seq[File]]]]): Initialize[Task[Seq[File]]] =
  generators { _.join.map(_.flatten) }
```

and consume it as `managedSources := generate(sourceGenerators).value`, then
`sources := Classpaths.concatDistinct(unmanagedSources, managedSources).value`.

Three facts fall out of those three lines and they are the whole contract.

1. **Every registered generator runs on every evaluation.** There is no
   up-to-date check between sbt and the generator, in either version. Measured in
   §1.2.
2. **Generators run in parallel with each other** (`join`), so two generators must
   not write to the same file or read each other's output. If eezo ever needs one
   generator's output as another's input, they must be one generator or be
   sequenced explicitly through a task dependency.
3. **The returned files are compiled because they were returned, not because of
   where they are.** `sources` is the concatenation, and it does not consult
   `managedSourceDirectories` at all. A generator may return a file from anywhere
   on disk and Zinc will compile it. Location matters for the IDE, not for the
   compiler. This is §4.

Also worth knowing, because it will otherwise surprise someone: sbt registers a
`resourceGenerators` entry of its own by default
(`resourceGenerators += Def.task { ... }` in `Defaults.scala`), so
`resourceGenerators` is never empty and `sourceGenerators :== Nil` is.

### 3.3 Incrementality, and the two caching helpers

Because sbt does not cache the body, the generator must. Two tools, both present
in sbt 1.12.x and sbt 2:

**`sbt.util.FileFunction.cached`** is the file-in, file-out helper, and it is what
eezo should use:

```scala
def cached(cacheBaseDirectory: File, inStyle: FileInfo.Style)(
    action: Set[File] => Set[File]
): Set[File] => Set[File]
```

Its own scaladoc states the semantics: "The input file and resulting output file
state is cached in stores issued by `storeFactory`. On each invocation, the state
of the input and output files from the previous run is compared against the cache,
as is the set of input files. If a change in file state / input files set is
detected, the action function is re-executed." It tracks **both** sides, so
deleting the generated output re-runs the action even when the inputs have not
moved, which is what makes it survive `clean`. The default `inStyle` is
`FileInfo.lastModified` and the default `outStyle` is `FileInfo.exists`.
Measured, it reduced three body executions to one (§1.2).

**`sbt.Tracked.inputChanged` / `Tracked.outputChanged`**, backed by
`sbt.util.CacheImplicits` (which is `BasicCacheImplicits with BasicJsonProtocol`
in
[1.12.x](https://github.com/sbt/sbt/blob/1.12.x/util-cache/src/main/scala/sbt/util/CacheImplicits.scala)
and, as a Scala 3 `given` carrier, in
[develop](https://github.com/sbt/sbt/blob/develop/util-cache/src/main/scala/sbt/util/CacheImplicits.scala))
is the lower-level route, for when the input is a *value* rather than a file set.
sbt's own docs name it as the alternative: cache "using the File tracking system
or by manually tracking the input values using `sbt.Tracked`". eezo does not need
it; the route table's inputs are files.

The cache directory should be `streams.value.cacheDirectory`, which sbt already
scopes per task and per configuration and, critically, places under `target`, so
`clean` removes the cache and the generated file together and they cannot get out
of step.

**The sbt 2 caveats, restated as instructions.** Keep the generator as
`Def.task { ... }.taskValue`, which is uncached in both versions (§1.2), and let
`FileFunction.cached` be the only cache. Do not promote it to a named `TaskKey`
assigned with `:=` in the hope of getting sbt 2's cache for free: sbt's own
migration guide warns that a restored cache hit skips the side effect, and a
generator is nothing but a side effect. If eezo ever does want sbt 2's cache, the
correct form is `Def.declareOutput(vf)` inside the task
([Caching](https://www.scala-sbt.org/2.x/docs/en/concepts/caching.html)), and that
form has no sbt 1 equivalent, so it would have to live behind a `PluginCompat`
shim. Not worth it.

### 3.4 Behaviour inside a resident `~` session

Measured, §1.3. Restated as three rules:

1. **The generator body runs on every triggered build**, exactly as it does on a
   cold `compile`. Both generators in the watch lab went from one run to two runs
   on the single triggering edit. Whatever the generator costs, the dev loop pays
   it on every keystroke burst. With `FileFunction.cached` in place, that cost is
   a directory scan and a hash comparison.
2. **Nothing a `~` session watches is derived from the generator.** Editing a file
   the generator reads does not trigger a build unless that file is already an
   input to `compile` for some other reason. `fileInputs` on the generator's own
   key does not change this; measured.
3. **`watchTriggers`, scoped to the watched task, is the fix that works.**
   Measured:
   ```scala
   Compile / compile / watchTriggers += baseDirectory.value.toGlob / "conf" / "routes"
   ```
   produced `Build triggered by .../conf/routes. Running 'compile'.`

There is a fourth behaviour that matters and that this document did **not**
measure: what happens when the generator's *output* changes and Zinc has to
invalidate downstream. `research/build-reload.md` §1.1 and §3.3 already own that
question and its answer is a design constraint on the generator rather than on the
plugin: the generated route table must be a plain `val routes: List[Route]` and
must not contain `given` values, or every route change invalidates every route
consumer.

### Recommendation for question 2

Ship the generator as a single `Compile / sourceGenerators += Def.task {
... }.taskValue` entry returning `Seq[File]`, with the whole body wrapped in
`FileFunction.cached(streams.value.cacheDirectory / "eezo-routes", FileInfo.hash)`.
Use `FileInfo.hash` rather than the default `FileInfo.lastModified`, because a
`git checkout` rewrites mtimes on files whose content did not change and eezo
should not regenerate on a branch switch. Register exactly one generator, not
three: multiple generators run in parallel and the second one that needs the
first's output is a bug waiting to happen.

If a future eezo generator reads anything that is not a `.scala` file under
`src/main/scala`, the plugin must add a `Compile / compile / watchTriggers` entry
for it in the same settings block, and there should be a scripted test that fails
if it does not.

---

## 4. Question 3: where generated sources should land

### 4.1 What `sourceManaged` actually guarantees

`sourceManaged` guarantees a path, and nothing else. It does not guarantee the
directory exists, and it is not stable across sbt versions.

In sbt 1.12.x:
```scala
sourceManaged := crossTarget.value / "src_managed"   // then
sourceManaged := configSrcSub(sourceManaged).value   // appends the config
```
which resolved, measured, to
`<base>/target/scala-3.8.4/src_managed/main` for `Compile`.

In sbt 2 the base moved:
```scala
target := rootOutputDirectory.value.resolve(outputPath.value).toFile()
sourceManaged := target.value / "src_managed"
```
([`Defaults.scala`, develop](https://github.com/sbt/sbt/blob/develop/main/src/main/scala/sbt/Defaults.scala)),
and sbt's migration guide states the resulting layout: "In sbt 2.x `target`
defaults to `target/out/jvm/scala-3.8.4/<subproject>/`, as opposed to
`<subproject>/target/`", with the explicit warning that "Plugins should be aware
of this change during migration."

**Instruction: never hard-code the path.** Always read `(Compile /
sourceManaged).value`. A plugin that constructs `target / "scala-3.8.4" /
"src_managed"` by hand is correct on sbt 1 and wrong on sbt 2.

What `sourceManaged` does guarantee is registration:
`managedSourceDirectories := Seq(sourceManaged.value)` in both versions, and
`sourceDirectories := concatSettings(unmanagedSourceDirectories,
managedSourceDirectories)`.

### 4.2 What BSP reports as a source root, exactly

From `bspBuildTargetSourcesItem`
([`BuildServerProtocol.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala),
same shape on
[2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala)):

```scala
val dirs = unmanagedSourceDirectories.value
val sourceFiles = getStandaloneSourceFiles(unmanagedSources.value, dirs)
val managedDirs = managedSourceDirectories.value
val managedSourceFiles = getStandaloneSourceFiles(managedSources.value, managedDirs)
val items = dirs.map(toSourceItem(SourceItemKind.Directory, generated = false)) ++
  sourceFiles.map(toSourceItem(SourceItemKind.File, generated = false)) ++
  managedDirs.map(toSourceItem(SourceItemKind.Directory, generated = true)) ++
  managedSourceFiles.map(toSourceItem(SourceItemKind.File, generated = true))
```

Four things follow.

1. **`managedSourceDirectories` is what becomes a generated source root.** Not
   `sourceManaged` directly, and not the returned file list.
2. **`getStandaloneSourceFiles` is the safety net.** It is
   `sourceFiles.filterNot(f => sourceDirs.exists(dir => f.toPath.startsWith(dir.toPath)))`,
   so a generated file that lies outside every `managedSourceDirectory` is still
   reported, but as an individual `SourceItem` of kind `File`. The IDE will know
   about the file. It will not treat its directory as a root, which is what
   determines whether a *new* file dropped in beside it is indexed.
3. **Answering `buildTarget/sources` runs the generators**, because the task reads
   `managedSources.value`. A BSP client asking what the sources are is, as a side
   effect, generating them.
4. sbt reports the meta-build's directories too, from `pluginData`, which is why
   `project/*.sbt` files show up in Metals.

For IntelliJ specifically, which imports through `sbt-structure` rather than BSP
by default, the extractor maps `managedSourceDirectories(configuration)` to
`DirectoryData(_, managed = true)` with no existence filter, and separately
defines `generateManagedSourcesTaskDef`, gated on `options.generateManagedSources`
([`ProjectExtractor.scala`](https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/dump/extract/ProjectExtractor.scala)).
The `Options` case class defaults that flag to `true`, but `readFromSeq` derives
it from `options.contains("generateManagedSources")`, so the effective default
depends on the option string IntelliJ sends
([`Options.scala`](https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/config/Options.scala)).
**Which option string IntelliJ actually sends could not be established from
primary sources and is not claimed here.** It is a checkbox in IntelliJ's sbt
import settings, and eezo should verify it empirically before relying on it.

For Metals, the default build server for an sbt project is Bloop, with sbt's own
BSP server available since sbt 1.4.1 and switchable through `metals.bsp-switch`
([Metals sbt docs](https://scalameta.org/metals/docs/build-tools/sbt)). Both
routes run the generators: the sbt BSP route through `managedSources.value` above,
the Bloop route through `bloopGenerate`'s explicit `val _ =
Keys.managedSources.value`.

### 4.3 What `clean` does, measured

Measured: after `sbt clean`, `target/scala-3.8.4/` did not exist, so
`src_managed`, the generated sources and `streams`' cache directory all went
together. A file written to `project/.eezo/` was untouched.

That is `Clean.task` doing what it says: it deletes the contents of `target` when
`full`, then the entries of `cleanFiles`, then the declared `fileOutputs`
([`Clean.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Clean.scala)).
Anything outside `target` and outside `cleanFiles` survives. sbt provides
`cleanKeepFiles` and `cleanKeepGlobs` for deliberate exceptions.

**A `clean` does not break the build.** The next `compile` re-evaluates
`managedSources`, the generator's `FileFunction` cache is gone along with its
output so the action re-runs, and the file comes back. The only thing `clean`
costs is the regeneration, which is the same work the first compile of a fresh
clone does.

### 4.4 The `project/` subdirectory question, since Skiff raises it

If eezo did write outside `target`, `project/.eezo/` would be safe from the
meta-build. Measured: a deliberately non-compiling `project/visible/Probe.scala`
loaded fine, and the same file at `project/Probe.scala` failed the build with
`type mismatch; found: String("not an int"); required: Int`. The meta-build's
source set is not recursive, which is visible in `Defaults.scala`:

```scala
val baseSources =
  if (sourcesInBase.value) Globs(baseDirectory.value.toPath, recursive = false, filter) :: Nil
  else Nil
```

So the safety does not depend on the leading dot. It depends on being in a
subdirectory. Worth knowing, because someone will eventually try `project/eezo/`
and it will also work, and someone else will try `project/Routes.scala` and be
very confused.

### Recommendation for question 3

**Generate into `(Compile / sourceManaged).value`, read from the setting, and add
nothing to `managedSourceDirectories`.** The default already registers
`sourceManaged` as a generated source root, sbt's BSP already reports it with
`generated = true`, and every import path that matters, sbt BSP, Bloop and
IntelliJ's extractor, forces `managedSources` and therefore runs the generator
during import rather than waiting for a compile. Skiff's premise, that
`src_managed` only exists after a first compile, is literally true and
operationally irrelevant, because "after a first import" is the condition that
actually governs whether the IDE is red.

**Reject `project/.eezo/`** as the default, for three reasons. It survives
`clean`, which turns a stale generated file into a state a user cannot clear with
the one command they know. It puts a build output in the source tree, where it
will be committed by someone and then conflict on every merge. And it buys only
the window between "clone" and "first import", during which nothing works anyway
because dependencies have not been resolved.

Two caveats, held honestly. First, if eezo measures a real IntelliJ cold-open in
which `eezo.generated.Routes` is unresolved after a normal import, that is a
falsification of the reasoning above and the fallback is exactly Skiff's shape,
`project/.eezo/` plus `Compile / managedSourceDirectories +=`, which is measured
to work and to survive `clean`. The dot is not what makes it safe (§4.4), but
keep it: it keeps the directory out of casual `ls` and out of most editors' file
trees. Second, this recommendation assumes the generated route table is worthless
without a compile anyway. If eezo ever generates something a human is meant to
read, that thing does not belong in `src_managed`.

---

## 5. Question 4: what the plugin can depend on

### 5.1 The two Scala versions, established

| | sbt version | plugin's Scala | source of truth |
|---|---|---|---|
| sbt 1 | 1.12.x (1.12.15 current) | **2.12.21** | `project/Dependencies.scala` on [`1.12.x`](https://github.com/sbt/sbt/blob/1.12.x/project/Dependencies.scala): `val scala212 = "2.12.21"`, `val baseScalaVersion = scala212`; corroborated by `sbt-1.12.14.pom` depending on `scala-library` 2.12.21 |
| sbt 2 | 2.0.x (2.0.6 current) | **3.8.4** | `sbt-2.0.0.pom` and `sbt-2.0.6.pom` both depend on `scala3-library_3` 3.8.4 |

sbt 2.0.6's `maven-metadata.xml` carries `lastUpdated` `20260807023355`, one week
before this investigation.

The published plugin POMs corroborate both, with the Scala library and sbt itself
in `provided` scope (§2.1), which is the mechanical statement of "this comes from
the container". The container is the build's classloader, and sbt's own
documentation describes the arrangement from the other end: sbt server "acts as a
cashier to take commands from sbtn and editors", and one session is shared by the
build user and the IDEs
([sbt components](https://www.scala-sbt.org/2.x/docs/en/guide/sbt-components.html)).

### 5.2 The consequences, in order of how much they hurt

**A. eezo's plugin cannot depend on eezo's modules on the sbt 1 axis. Full stop.**
eezo publishes `eezo-core_3` and friends, compiled with Scala 3.8.4. A Scala 2.12
plugin cannot consume a Scala 3 artifact; there is no cross-version escape hatch
in that direction (`CrossVersion.for3Use2_13` goes the other way, and
`for2_13Use3` starts at 2.13, not 2.12). This is not a versioning inconvenience,
it is the ABI.

**B. On the sbt 2 axis it would technically work, and eezo should still not do
it.** The sbt 2 plugin is Scala 3.8.4, which is *exactly* eezo's `scalaVersion`
(`project/Toolchain.scala`: `val ScalaVersion = "3.8.4"`). So `libraryDependencies
+= "io.eezo" %% "eezo-core" % version.value` would resolve `eezo-core_3` and load.
Three reasons not to:

- It would make the two axes of the same plugin have different capabilities,
  which means two implementations of the generator, which defeats the point.
- It welds eezo's Scala version to sbt's. Scala 3 guarantees backward binary
  compatibility, not forward: a module compiled with 3.9 wants a 3.9 library, and
  sbt 2.0.6 supplies 3.8.4. The first eezo Scala bump would break the plugin.
- It puts eezo's transitive dependencies, which include Jetty, HikariCP and
  pgjdbc, on the build's classpath, shared with every other plugin the user has.
  sbt's own plugin guidance is that plugins should "play well with other plugins"
  and "avoid namespace clashes"
  ([Plugins Best Practices](https://www.scala-sbt.org/1.x/docs/Plugins-Best-Practices.html));
  dragging a servlet container into `project/` is the opposite.

**C. There is a circularity that would be worse than either.** `modules/sbt-plugin`
lives in eezo's own build. If it depended on `modules/core`, then building the
plugin would require building core, and any future need to run the plugin over
eezo's own templates would need a `publishLocal` bootstrap. `../skiff` documents
that it dodged this by sharing *source* rather than artefacts: its
`project/build.sbt` adds the plugin's source directory to the meta-build's
`unmanagedSourceDirectories`, so the generator compiles twice, once into the
plugin jar and once into the meta-build. That is a legitimate technique and eezo
may want it later for its in-repo templates. It is not a dependency.

**D. JDK level.** eezo's floor is JDK 25 and its `commonSettings` compile with
`-release 25`. The plugin runs on whatever JVM the user's sbt runs on. Since eezo
requires JDK 25 of its users anyway, targeting a lower `-release` on the plugin is
not required for correctness, but it is free insurance and it is what
`sbt-ci-release` does (`-release:8` on the 2.12 axis).

### Recommendation for question 4

**The plugin depends on sbt and on nothing else.** Its whole world is `sbt.io.IO`,
`java.io.File`, `sbt.Keys`, `sbt.util.FileFunction`, and the standard library
subset common to Scala 2.12 and 3. No eezo modules, on either axis. No third-party
libraries, on either axis; if the route parser needs anything beyond string
handling, that is a signal the route table's format is too clever.

Give `modules/sbt-plugin` its own `scalacOptions` rather than eezo's
`commonSettings`, because `-no-indent`, `-Wunused:all` and `-release 25` are not a
valid 2.12 flag set, and drop the `munit` dependency that `commonSettings` adds
unconditionally, because MUnit's Scala 2.12 artifact would have to be pinned
separately. Test the generator through sbt's `scripted` framework, which
`enablePlugins(SbtPlugin)` brings in for free:
[`SbtPlugin`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/plugins/SbtPlugin.scala)
is four lines, `override def requires = ScriptedPlugin` and `sbtPlugin := true`.

The one thing the plugin and the framework must share is the *shape* of the
generated code, and that shape should be pinned by a scripted test that compiles
generated output against a published eezo, not by a compile-time dependency.

---

## 6. Question 5: how the AutoPlugin triggers, and what the user writes

### 6.1 The two overrides

`sbt.AutoPlugin`'s defaults, identical in
[1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Plugins.scala)
and
[2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/Plugins.scala):

```scala
def trigger: PluginTrigger = noTrigger
def requires: Plugins = plugins.JvmPlugin   // Plugins.defaultRequires in sbt 2
```

and the scaladoc states the rule precisely: "When this method returns
`allRequirements`, and `requires` method returns `Web && Javascript`, this plugin
instance will be added automatically if the `Web` and `Javascript` plugins are
enabled. When this method returns `noTrigger`, ... this plugin instance will be
added only if the build user enables it, but it will automatically add both `Web`
and `Javascript`."

So the two are orthogonal. `requires` is a precondition and an activation
*forcing* relation: enabling a plugin enables everything it requires. `trigger`
decides whether satisfying the precondition is sufficient on its own.

`allRequirements` with `requires = JvmPlugin` means the plugin turns itself on in
every JVM subproject in the build, including ones the user did not think about.
`noTrigger` means nothing happens until `enablePlugins(EezoPlugin)`.

The three setting hooks, from
[Plugins](https://www.scala-sbt.org/1.x/docs/Plugins.html):
`projectSettings` are applied to each project that has the plugin enabled,
`buildSettings` are added once per build in `ThisBuild` scope, and
`globalSettings` are applied in `Global`, "typically for default values and
commands".

### 6.2 What the user writes

Two files, and they are the same in sbt 1 and sbt 2.

`project/plugins.sbt`:
```scala
addSbtPlugin("io.eezo" % "sbt-eezo" % "0.1.0")
```

Note the single `%`. `addSbtPlugin` applies the plugin cross-version itself, which
is the `_2.12_1.0` or `_sbt2_3` machinery of §2.1, so the user never writes the
suffix and the same line resolves the right artifact on either sbt.

`build.sbt`, if the plugin is `noTrigger`:
```scala
lazy val root = (project in file("."))
  .enablePlugins(EezoPlugin)
  .settings(
    name := "myapp",
    libraryDependencies ++= Seq(
      "io.eezo" %% "eezo-core" % "0.1.0",
      "io.eezo" %% "eezo-http" % "0.1.0"
    )
  )
```

and nothing at all in `build.sbt` if it is `allRequirements`. `disablePlugins` is
the escape hatch in that case.

A user can check what is on with the `plugins` command, which sbt's own
documentation demonstrates and which lists both enabled plugins and "Plugins that
are loaded to the build but not enabled in any subprojects"
([Plugin basics](https://www.scala-sbt.org/2.x/docs/en/)).

### Recommendation for question 5

```scala
object EezoPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins      = sbt.plugins.JvmPlugin
  object autoImport { /* eezo-prefixed keys */ }
  override lazy val projectSettings: Seq[Setting[_]] = ...
}
```

**`noTrigger`, not `allRequirements.`** The generator scans a source tree and
writes a file into it. Doing that automatically to every JVM subproject in a
build eezo does not own, including the `tools/` or `benchmarks/` subproject the
user has for unrelated reasons, is not a defensible default. `../skiff` reached
the same conclusion and wrote the reason in a comment: "A user might have a
`tools/` sub-project that shouldn't get Routes.scala." The cost of `noTrigger` is
one line in the user's `build.sbt`, which `eezo new` writes for them.

`requires = JvmPlugin` explicitly, even though it is already the default, because
the default is easy to override by accident and the plugin genuinely depends on
`sourceGenerators`, `scalaSource` and `compile` existing.

Every key in `autoImport` gets an `eezo` prefix, per sbt's naming guidance
("Instead of generic names like `jarName`, prefix them"). Export the settings as a
named `val eezoSettings: Seq[Setting[_]]` inside `autoImport` as well as applying
them from `projectSettings`, so a project with an unusual layout can apply the
pieces by hand; `../skiff` does this and it costs nothing.

The published artifact is `sbt-eezo`, which matches sbt's prescribed
`sbt-$projectname` scheme, with `sbt-eezo-plugin` and `eezo-sbt` explicitly listed
as wrong.

---

## 7. Question 6: coexisting with a debounced compile driver eezo owns

### 7.1 The context this inherits

`research/build-reload.md` §1.5 concludes that eezo should "drive the compile
itself with a debounce, not delegate to `~`", and calls it "the largest saving
anywhere in this document", on a measured 3.6x to 5x penalty for multi-file edits
under `~`. §9 entry 3 of that document prices the work at one day and names the
mechanism as "resident sbt driven through the sbt server / BSP rather than `~`".

§1.5 of *this* document supplies the missing mechanism: sbt's `watchAntiEntropy`
is a per-file de-duplicator with a 500 ms window, not a global debounce, so a
51-file rename is 51 independent trigger sources and the window suppresses none of
them. There is no sbt setting that converts it into a debounce. The conclusion
holds and is now explained rather than merely measured.

### 7.2 The three ways to drive a resident sbt, and what each costs

**(a) A client process per compile.** `sbt --client compile` invokes sbtn, which
sbt documents as "compiled to native code using GraalVM native-image" with a
protocol "stable enough that it should work between most recent versions of sbt"
([sbt components](https://www.scala-sbt.org/2.x/docs/en/guide/sbt-components.html)).
Measured in the eezo repository itself, an eight-module build with no sources so
that the compile is a no-op and only the round trip is being measured:

| invocation | first call | steady state (5 runs) |
|---|---|---|
| `sbt --client compile`, server cold | **5.85 s** (boots the server) | |
| `sbt --client compile`, server warm | | **0.30, 0.26, 0.23, 0.23, 0.22 s** |
| `sbt --client "print name"`, server warm | 0.28 s | **0.15, 0.14, 0.14, 0.14 s** |
| `sbtn-universal-apple-darwin compile`, invoked directly | 0.24 s | **0.18, 0.19, 0.19, 0.19 s** |
| `sbtn-universal-apple-darwin "print name"` | 0.74 s | **0.17, 0.15, 0.12, 0.12 s** |

So roughly **140 ms is client process overhead** and **another 60 to 90 ms is the
server's own no-op turnaround**. Against `research/build-reload.md`'s measured
194 to 224 ms for a real one-field compile at 204 sources, shelling out to sbtn
per edit roughly doubles the cheap case. It is not fatal, and it is by far the
simplest thing to build.

**(b) A held JSON-RPC connection to sbt server.** sbt server speaks Language Server
Protocol 3.0 over JSON-RPC and publishes its address in
`./project/target/active.json`, as `{"uri":"local:///path/to/sock"}` on Unix. A
client sends `initialize`, then `sbt/exec` to run a command, and receives
`textDocument/publishDiagnostics` for compiler errors; `sbt/cancelRequest`
terminates a running task
([sbt server](https://www.scala-sbt.org/1.x/docs/sbt-server.html)). Holding that
connection open removes the 140 ms of process overhead entirely, and the
`sbt/cancelRequest` verb is what makes a *real* debounce possible: eezo can cancel
an in-flight compile when a newer edit lands, which `~` cannot do.

**(c) BSP `buildTarget/compile` on the same connection.** sbt implements it as
`bspBuildTargetCompile`, with `Method.Compile = "buildTarget/compile"` dispatching
to that key
([`BuildServerProtocol.scala`, 1.12.x](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala)).
The advantage over `sbt/exec` is a typed result with per-target status codes and
structured diagnostics; the disadvantage is that eezo must speak enough BSP to
have discovered the target ids first.

### 7.3 The hazard: one server, sequentially processed

sbt's own concept documentation states the constraint: "Because there is only one
state, a characteristic of commands is that they are executed one at a time" and
"sbt server is a service that accepts commands from either the command line or a
network API called Build Server Protocol. This mechanism allows both the build
user and IDEs to share the same sbt session"
([Command basics](https://www.scala-sbt.org/2.x/docs/en/)).

The upside is exactly what `research/build-reload.md` §9 entry 3 hoped for: eezo's
dev server and the user's Metals session can share one warm compiler instead of
running two. The downside arrives with it. If Metals issues a `buildTarget/compile`
because the user saved a file, and eezo's dev server issues one 40 ms later, they
queue. eezo's debounce controls only its own emissions, not the queue depth, and
the "compile finished" event eezo observes may belong to Metals' request rather
than its own. A dev server that assumes one-request-one-response will
occasionally reload against a build it did not ask for, which is harmless, and
occasionally believe a compile is still running when it is not, which is not.

The mitigation is to make eezo's dev server *reactive* rather than
request-response: treat any compile completion on the shared session as a signal
to check whether the class output changed, and reload if it did. That converges
regardless of who issued the compile, and it is close to how Play detects a
rebuild (max `lastModified` over the classpath, cited in
`research/build-reload.md` §11.1).

### 7.4 What this means for the plugin's surface

The plugin's contribution to the dev loop is not the loop. It is three things:

1. **The generator itself**, which must be correct under plain `sbt compile`,
   under `~compile`, and under a compile issued over the server, all of which are
   the same code path (`managedSources`), so this is free.
2. **The `watchTriggers` entries** for any non-Scala generator input (§3.4), so
   that a user who does run `~compile` gets correct behaviour rather than a stale
   route table. eezo's own dev server does not need them, because it owns its
   watcher, but the plugin must not assume the dev server is present.
3. **Settings the dev server reads rather than duplicates**: the generated output
   path, the class directory, the run classpath. The dev server should ask the
   build for these over `sbt/setting` rather than reconstructing them, precisely
   because §4.1 shows the paths move between sbt 1 and sbt 2.

What the plugin must **not** contain: the file watcher, the debounce, the fork,
the classloader swap, or a task that blocks. A plugin task that never returns
occupies the single sbt state (§7.3) and makes the session unusable for everything
else, which is the failure mode `sbt-revolver`'s `reStart` carefully avoids by
forking.

### Recommendation for question 6

**Build (a) first and (b) second.** Ship the first version of `eezo dev` driving
compiles through `sbt --client`, because it is a subprocess call, it is measured
at 220 to 300 ms of overhead on this machine, and it is enough to prove the debounce
is worth having. Then move to a held JSON-RPC connection over the socket in
`project/target/active.json` once the loop is real, for the 140 ms and, more
importantly, for `sbt/cancelRequest`, which is the only way to make a debounce
that can abandon work rather than merely delay it. Do not start with BSP: it is
strictly more protocol for the same effect until eezo wants structured
diagnostics, which it will, but not first.

**Keep the debounce in the dev server and out of the plugin**, and keep the plugin
correct without the dev server. Concretely: the plugin's settings block must
contain no watcher and no long-running task, and there should be a scripted test
that runs `compile` twice with no edit in between and asserts the generator's
cache prevented a rewrite.

**Design the reload trigger to be reactive.** eezo's dev server shares the sbt
session with the user's Metals, and compiles it did not request will complete on
that session. Detect "the class output changed" rather than "my compile
finished".

---

## 8. Recommendation

Add `modules/sbt-plugin` to eezo's build, publishing as `sbt-eezo`, with this
shape:

```scala
// The sbt plugin. Cross-built for sbt 1 (Scala 2.12) and sbt 2 (Scala 3), which
// are two different artifactIds, so adding or dropping an axis later breaks
// nothing that already resolves. It does not inherit commonSettings: `-no-indent`
// and `-release 25` are not a valid Scala 2.12 flag set.
lazy val sbtEezo = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name               := "sbt-eezo",
    crossScalaVersions := Seq("2.12.21", Toolchain.ScalaVersion),
    scalacOptions      := {
      val common = Seq("-deprecation", "-feature", "-unchecked")
      scalaBinaryVersion.value match {
        case "2.12" => common ++ Seq("-Xsource:3", "-release:8")
        case _      => common
      }
    },
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.5.8"   // the floor, not eezo's own sbt
        case _      => "2.0.6"
      }
    }
  )
```

not aggregated into the root's `test` path in a way that forces a 2.12 build on
every `sbt test`, and not depending on any eezo module.

The plugin itself:

```scala
object EezoPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins      = sbt.plugins.JvmPlugin

  object autoImport {
    val eezoSettings: Seq[Setting[_]] = Seq(
      Compile / sourceGenerators += Def.task {
        val src   = (Compile / scalaSource).value
        val out   = (Compile / sourceManaged).value / "eezo"
        val cache = streams.value.cacheDirectory / "eezo-routes"
        val gen   = FileFunction.cached(cache, FileInfo.hash) { (in: Set[File]) =>
          RouteTableGenerator.generate(in, out)
        }
        gen((src ** "*.scala").get.toSet).toSeq
      }.taskValue
    )
  }

  override lazy val projectSettings: Seq[Setting[_]] = autoImport.eezoSettings
}
```

Six answers in one line each:

1. **Cross-publish, using `crossScalaVersions` plus `pluginCrossBuild /
   sbtVersion` and the ordinary `+publishSigned` that `ci-release` already runs;
   the coordinates are `io.eezo:sbt-eezo_2.12_1.0` and `io.eezo:sbt-eezo_sbt2_3`,
   and the decision is reversible in both directions because they are different
   artifactIds.**
2. **A generator is a `Task[Seq[File]]` registered with `.taskValue`, is run on
   every evaluation by sbt in both versions, and must cache itself with
   `FileFunction.cached`; sbt 2's automatic caching does not apply to `Def.task`
   and would be actively wrong if it did.**
3. **`(Compile / sourceManaged).value`, read from the setting and never
   hard-coded; it is already a BSP generated source root, every IDE import path
   runs the generator, and `clean` removing it costs one regeneration.**
4. **sbt and the Scala standard library, nothing else: the sbt 1 axis is Scala
   2.12.21 and cannot see eezo's Scala 3 artifacts at all, and the sbt 2 axis
   could but must not, because it would weld eezo's Scala version to sbt's and put
   Jetty on the build classpath.**
5. **`noTrigger` with `requires = JvmPlugin`, so a user writes `addSbtPlugin("io.eezo"
   % "sbt-eezo" % v)` in `project/plugins.sbt` and `.enablePlugins(EezoPlugin)` in
   `build.sbt`.**
6. **The plugin registers the generator and adds `watchTriggers`; the dev server
   owns the debounce and drives compiles through sbt server, first with
   `sbt --client` at a measured 220 to 300 ms of overhead, later over a held socket
   for `sbt/cancelRequest`; sbt's own `watchAntiEntropy` cannot be made to
   debounce because it is per-file.**

### Confidence

**Very high (98%) on the artifact coordinates and the cross-publish mechanism.**
Both artifacts of a real plugin were fetched from Maven Central and their POMs
read, the naming code was read in `Defaults.sbtPluginExtra`, and sbt's own
migration guide gives the build snippet verbatim. The only way this is wrong is if
sbt changes the scheme again.

**Very high (97%) that a source generator is not cached by sbt in either
version.** Measured three body executions against three task evaluations, and read
`generate = generators { _.join.map(_.flatten) }` in both branches. The sbt 2 half
rests on reading `Def.task` expanding with `cached = false` in `Def.scala` on
`2.0.x`, which contradicts a plain reading of the prose documentation; I trust the
source, and this is the claim most worth re-checking against a real sbt 2 build.

**High (94%) that `fileInputs` on a generator's task key does not make `~` watch
it, and that `watchTriggers` does.** Both measured, cleanly, in a live session.
What I did *not* establish is *why* `fileInputs` fails, beyond the inference that
`WatchTransitiveDependencies` cannot see through a `SettingKey[Seq[Task[...]]]`.
The remedy works regardless of the mechanism.

**High (90%) that `sourceManaged` is the right output location.** The BSP source
item construction, Bloop's forced `managedSources` and IntelliJ's
`generateManagedSourcesTaskDef` were all read in their own source. The 10% is
concentrated in one thing: whether IntelliJ actually passes the
`generateManagedSources` option by default. `Options.readFromSeq` derives it from
an option string rather than the case class default, and I could not establish
what IntelliJ sends. **If it does not, an IntelliJ user's first open shows
unresolved references until they compile, and the recommendation flips to Skiff's
`project/.eezo/` shape.** That is one 15-minute experiment and #108's implementer
should run it before committing.

**High (92%) that the plugin must not depend on eezo's modules.** The sbt 1 half
is an ABI fact and is not arguable. The sbt 2 half is a judgement about coupling,
and someone could reasonably decide that an sbt-2-only plugin sharing eezo's
route-parsing code is worth the version lockstep. I think it is not, and the
symmetry argument (two axes, one implementation) is what makes me confident.

**Medium-high (80%) on `noTrigger` over `allRequirements`.** The argument is
about blast radius in builds eezo does not own, and it is the same conclusion
`../skiff` reached, but frameworks do go the other way: an `allRequirements`
plugin that no-ops when it finds no eezo routes would also be defensible and would
save the user a line. The deciding factor is that "no eezo routes" is not cheaply
detectable without scanning, and scanning every subproject in every build is worse
than one line of configuration.

**Medium (70%) on the specific dev-loop sequencing in §7.** The client latencies
were measured on an eight-module build with **no sources**, which is the right
isolation for the round trip and the wrong scale for anything else. The claim that
a held socket saves the 140 ms is arithmetic, not measurement: I did not build a
JSON-RPC client and time it. The claim that `sbt/cancelRequest` makes a
cancellable debounce possible is read from documentation, not exercised.

**Medium (65%) that sharing one sbt session with Metals is a net win rather than a
net hazard.** §7.3's sequencing constraint is documented and real, and the
reactive-detection mitigation is sound in principle, but nobody has run eezo's dev
server and Metals against one sbt server and watched what happens. If it turns out
badly, the fallback is a dedicated sbt server on a separate `project/target`,
which costs a second JVM and a second warm compiler, which is the duplication
`research/build-reload.md` §3.2 wanted to avoid.

---

## 9. Open items handed onward

| Item | Why it is open | Ticket that should own it |
|---|---|---|
| Whether IntelliJ passes `generateManagedSources` on import by default | `Options.readFromSeq` derives it from an option string, not the case class default; §4.2. It is the single fact that could flip §4's recommendation | [#108](https://github.com/Eezo-framework/eezo/issues/108), before the module lands |
| Whether the sbt 2 axis of the plugin actually builds and publishes from eezo's sbt 1.12.14 build | The mechanism is documented and `sbt-ci-release` does it, but eezo has not run it | [#108](https://github.com/Eezo-framework/eezo/issues/108) |
| Why `fileInputs` on the generator's task key does not reach sbt's watch | Measured negative result with an inferred, unverified mechanism; §1.3 | whichever ticket writes the generator |
| A scripted test that fails if the generator's cache regresses | §3.3's `FileFunction.cached` is the only thing standing between the dev loop and a full rescan per keystroke, and nothing currently protects it | whichever ticket writes the generator |
| Held-socket compile driving, measured against the `sbt --client` baseline in §7.2 | The 140 ms saving is arithmetic, and `sbt/cancelRequest` is unexercised | [#16](https://github.com/Eezo-framework/eezo/issues/16) |
| Behaviour when eezo's dev server and Metals share one sbt session | §7.3's sequencing constraint is documented; the interaction is not measured | [#16](https://github.com/Eezo-framework/eezo/issues/16) |
| Generated route table must be a plain `val`, not a family of `given`s | `research/build-reload.md` §1.1 and §3.3; this document does not re-litigate it but the generator must obey it | whichever ticket writes the generator |
| Whether eezo shares the generator's source with its meta-build, Skiff-style, for in-repo templates | Would let eezo's own templates build without a `publishLocal` bootstrap; §5.2 note C | whichever ticket adds in-repo templates |
| sbt 2 head-to-head for the whole dev loop, including the plugin | `research/build-reload.md` §9 entry 6 already owns this; the plugin half is now answered, the loop half is not | [#16](https://github.com/Eezo-framework/eezo/issues/16) |

---

## Appendix A: reproduction

Everything below was run on sbt 1.12.14, Scala 3.8.4, Temurin JDK 26+35, macOS
15.7.4, Apple M4 Pro. The two lab projects are throwaway and were not committed;
they are reproduced in full here because they are short.

### A.1 Generator caching, `clean` behaviour, and paths (§1.2, §4.1, §4.3)

A project with `scalaVersion := "3.8.4"` and three generators: one naive, one
wrapped in `FileFunction.cached(cache, FileInfo.hash)`, and one writing to
`project/.eezo/` with `Compile / managedSourceDirectories +=` that directory. Each
of the first two appends a line to its own log file every time its body runs.

```
sbt -batch "show Compile/sourceManaged" "show Compile/managedSourceDirectories" \
           compile compile "show Compile/managedSources"
wc -l naive.log cached.log
sbt -batch clean
ls target/scala-3.8.4/ ; ls project/.eezo
```

Results: `sourceManaged` is `target/scala-3.8.4/src_managed/main`;
`managedSourceDirectories` is that plus `project/.eezo`; `managedSources` lists all
three generated files including the one outside `src_managed`; `naive.log` has 3
lines and `cached.log` has 1 across three evaluations of `managedSources`; after
`clean`, `target/scala-3.8.4/` does not exist and `project/.eezo/Outside.scala`
does.

### A.2 Meta-build source scanning (§4.4)

```
echo 'object MetaProbe { val x: Int = "not an int" }' > project/visible/Probe.scala
sbt -batch "show name"       # loads fine
mv project/visible/Probe.scala project/Probe.scala
sbt -batch "show name"       # type mismatch; found String, required Int
```

### A.3 Watch triggers under a live `~` session (§1.3, §3.4)

A project with two generators, `gen1` reading `conf/routes` and declaring nothing,
`gen2` reading `conf2/routes.txt` and declaring
`gen2 / fileInputs += baseDirectory.value.toGlob / "conf2" / "*.txt"`. A driver
script starts `sbt "~compile"` in the background, waits for
`Monitoring source files`, then makes one edit at a time with a 6-second settle
between them, recording each generator's run count and `grep -c 'Build triggered'`
after each.

Edits, in order: `conf/routes`, `conf2/routes.txt`, `src/main/scala/Main.scala`.
Only the third triggered. A second run of the same script, after adding
`Compile / compile / watchTriggers += baseDirectory.value.toGlob / "conf" / "routes"`,
triggered on the first edit with
`Build triggered by .../conf/routes. Running 'compile'.`

### A.4 Thin-client latency (§7.2)

Run in `/Users/rcardin/Documents/Repositories/eezo` at commit `5c47435`, an
eight-module build with no source files, so `compile` is a no-op and the figure is
the round trip.

```
time sbt --client compile                      # cold: boots the server
for i in 1 2 3 4 5; do /usr/bin/time -p sbt --client compile; done
for i in 1 2 3 4 5; do /usr/bin/time -p sbt --client "print name"; done
SBTN=~/.sdkman/candidates/sbt/current/bin/sbtn-universal-apple-darwin
for i in 1 2 3 4 5; do /usr/bin/time -p $SBTN compile; done
sbt --client shutdown
```

### A.5 Registry queries

```
curl -s https://repo1.maven.org/maven2/com/github/sbt/ | grep -o 'href="[^"]*"'
curl -s https://repo1.maven.org/maven2/com/github/sbt/sbt-ci-release_sbt2_3/maven-metadata.xml
curl -s https://repo1.maven.org/maven2/com/github/sbt/sbt-ci-release_sbt2_3/1.12.0/sbt-ci-release_sbt2_3-1.12.0.pom
curl -s https://repo1.maven.org/maven2/com/github/sbt/sbt-ci-release_2.12_1.0/1.12.0/sbt-ci-release_2.12_1.0-1.12.0.pom
curl -s https://repo1.maven.org/maven2/io/spray/ | grep -o 'href="sbt-revolver[^"]*"'
curl -s https://repo1.maven.org/maven2/org/scala-sbt/sbt/2.0.6/sbt-2.0.6.pom
curl -s https://repo1.maven.org/maven2/org/scala-sbt/sbt/1.12.14/sbt-1.12.14.pom
```

### A.6 Negative results, preserved deliberately

- `gen2 / fileInputs` did not cause a `~` trigger. Reported as a finding rather
  than discarded, because it is the obvious thing to try and it does not work.
- The first watch-lab `build.sbt` failed to load with `')' expected but string
  literal found` because of an escaping mistake in the heredoc that wrote it. The
  run was discarded and the file rewritten with string concatenation; no number in
  this document comes from that run.
- `https://www.scala-sbt.org/2.x/docs/en/reference/cross-building.html` and
  `.../reference/caching.html` both 404. The sbt 2 documentation's real page names
  are `reference/cross-building-setup.html` and `concepts/caching.html`, and the
  complete text is reachable at `.../2.x/docs/en/print.html`, which is what the
  verbatim quotations in §2 and §3 were taken from.

---

## Appendix B: sources

**Primary, sbt's own source, at named branches**
- [`main/src/main/scala/sbt/Keys.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Keys.scala) and [develop](https://github.com/sbt/sbt/blob/develop/main/src/main/scala/sbt/Keys.scala): `sourceGenerators`, `sourceManaged`, `managedSourceDirectories`
- [`main/src/main/scala/sbt/Defaults.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala) and [develop](https://github.com/sbt/sbt/blob/develop/main/src/main/scala/sbt/Defaults.scala): `generate`, `managedSources`, `sourceManaged`, `sbtPluginExtra`, `pluginCrossBuild`, `sbtCrossVersion`, `sourcesInBase`
- [`main-settings/src/main/scala/sbt/Def.scala`](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/Def.scala) and [`std/TaskMacro.scala`](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/std/TaskMacro.scala): which task forms are cached in sbt 2
- [`main-settings/src/main/scala/sbt/Plugins.scala`](https://github.com/sbt/sbt/blob/2.0.x/main-settings/src/main/scala/sbt/Plugins.scala) and [`main/src/main/scala/sbt/Plugins.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Plugins.scala): `AutoPlugin.trigger`, `requires`
- [`main/src/main/scala/sbt/plugins/SbtPlugin.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/plugins/SbtPlugin.scala)
- [`main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala) and [2.0.x](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/server/BuildServerProtocol.scala): `bspBuildTargetSourcesItem`, `getStandaloneSourceFiles`, `Method.Compile`
- [`main/src/main/scala/sbt/internal/Clean.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Clean.scala)
- [`main/src/main/scala/sbt/nio/Watch.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Watch.scala) and [`nio/Keys.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Keys.scala): anti-entropy defaults, `watchTriggers`, `fileInputs`
- [`main/src/main/scala/sbt/Cross.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Cross.scala): what `+` does with a task
- [`util-tracking/src/main/scala/sbt/util/FileFunction.scala`](https://github.com/sbt/sbt/blob/1.12.x/util-tracking/src/main/scala/sbt/util/FileFunction.scala) and [develop](https://github.com/sbt/sbt/blob/develop/util-tracking/src/main/scala/sbt/util/FileFunction.scala)
- [`util-cache/src/main/scala/sbt/util/CacheImplicits.scala`](https://github.com/sbt/sbt/blob/1.12.x/util-cache/src/main/scala/sbt/util/CacheImplicits.scala)
- [`project/Dependencies.scala`](https://github.com/sbt/sbt/blob/1.12.x/project/Dependencies.scala): sbt 1.12.x is Scala 2.12.21
- [sbt/sbt#7671, "Use `_sbt2_3` suffix"](https://github.com/sbt/sbt/pull/7671)

**Primary, sbt documentation**
- [Generating files](https://www.scala-sbt.org/1.x/docs/Howto-Generating-Files.html)
- [Plugins](https://www.scala-sbt.org/1.x/docs/Plugins.html), [Plugins Best Practices](https://www.scala-sbt.org/1.x/docs/Plugins-Best-Practices.html), [Using plugins](https://www.scala-sbt.org/1.x/docs/Using-Plugins.html)
- [Cross building plugins](https://www.scala-sbt.org/1.x/docs/Cross-Build-Plugins.html)
- [Triggered execution](https://www.scala-sbt.org/1.x/docs/Triggered-Execution.html)
- [sbt server](https://www.scala-sbt.org/1.x/docs/sbt-server.html)
- [sbt 2.0 change summary](https://www.scala-sbt.org/2.x/docs/en/changes/sbt-2.0-change-summary.html), [Migrating from sbt 1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html), [Caching](https://www.scala-sbt.org/2.x/docs/en/concepts/caching.html), [Cached task](https://www.scala-sbt.org/2.x/docs/en/reference/cached-task.html), and the complete Book of sbt at [print.html](https://www.scala-sbt.org/2.x/docs/en/print.html)

**Primary, other projects' source**
- [`sbt/sbt-ci-release` `build.sbt`](https://github.com/sbt/sbt-ci-release/blob/main/build.sbt) and [`CiReleasePlugin.scala`](https://github.com/sbt/sbt-ci-release/blob/main/plugin/src/main/scala/com/geirsson/CiReleasePlugin.scala)
- [`sbt/sbt2-compat`](https://github.com/sbt/sbt2-compat)
- [`scalacenter/bloop` `SbtBloop.scala`](https://github.com/scalacenter/bloop/blob/main/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala)
- [`JetBrains/sbt-structure` `ProjectExtractor.scala`](https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/dump/extract/ProjectExtractor.scala) and [`Options.scala`](https://github.com/JetBrains/sbt-structure/blob/master/extractor/src/main/scala/org/jetbrains/sbt/config/Options.scala)
- [Metals sbt documentation](https://scalameta.org/metals/docs/build-tools/sbt)

**Primary, registries (all queried 2026-08-15)**
Maven Central directory listings and `maven-metadata.xml` under
`com/github/sbt/`, `io/spray/` and `org/scala-sbt/sbt/`, plus the full POMs of
`sbt-ci-release` 1.12.0 in both flavours and of `sbt` 1.12.14, 2.0.0 and 2.0.6.

**Evidence, not authority**
`../skiff`, read-only: `modules/sbt-plugin/src/main/scala/skiff/sbt/SkiffPlugin.scala`
(`noTrigger`, `requires JvmPlugin`, the `project/.skiff/` output location and its
stated rationale) and `build.sbt` lines 668 to 692 (sbt 1 only, `scalaVersion :=
"2.12.20"`, `scalacOptions` reset, MUnit filtered out).

**Measured locally, cited to nothing**
Every figure in §1.2, §1.3, §4.1, §4.3, §4.4 and §7.2.
