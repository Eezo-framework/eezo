# Research: fast edit-to-running-code loops on the JVM and Scala 3

Resolves [rcardin/eezo#6](https://github.com/rcardin/eezo/issues/6). Part of #1.
Feeds the build-tool and dev-loop architecture decision in
[#16](https://github.com/rcardin/eezo/issues/16) and the measurement spike in
[#10](https://github.com/rcardin/eezo/issues/10).

Date of investigation: 2026-07-27. Every version, release-date and maintenance
claim was verified on that date against the GitHub REST API, Maven Central
`maven-metadata.xml`, and the projects' own source.

**Empirical basis.** The load-bearing claims about Zinc invalidation and about
dev-loop latency were measured, not read. Toolchain: **Apple M4 Pro, 14 cores,
24 GiB, macOS 15.7.4**, Temurin **JDK 26+35**, **sbt 1.12.1**, **Zinc** as
shipped by that sbt, **Scala 3.8.4**, **scala-cli 1.15.0** (which drives Bloop).
Same machine as `research/http-server.md`, so the numbers in the two documents
compose. Every number marked **measured** was produced by the rig preserved at
[`research/harnesses/zinc-lab/`](harnesses/zinc-lab/) with its raw logs. Every
number marked **cited** carries its source inline.

The salvaged 2026-07-26 rig was read, its two broken experiments were diagnosed
(§A.0), the rig was rewritten, and all five original experiments plus nine new
ones were re-run at three project sizes. **Nothing from that salvage is quoted.**

---

## 1. The findings that reframe the question

There are five. Only the last one is about picking a build tool.

### 1.1 The dangerous edit is not "add a field", it is "change the `derives` clause", and the reason is a single branch in Zinc

eezo's thesis is that many things are derived from one case class. The obvious
fear is that editing that case class recompiles the world. Measured, on a
504-source project where 400 files depend on the model:

| Edit to the model case class | Sources recompiled | Warm compile |
|---|---|---|
| add a field | **1** | 129 ms |
| change a field's type (`Int` to `Long`) | **1** | 132 ms |
| rename a field nothing references | **1** | 123 ms |
| change a method body on the case class | **1** | 126 ms |
| rename a field 200 files reference | 201 | 429 ms |
| **add one typeclass to the `derives` clause** | **401** | **766 ms** |
| **remove one typeclass from the `derives` clause** | **601** | **977 ms** |

Adding a field to a case class that derives four typeclasses recompiles **one
file**. Adding a fifth typeclass to the same class recompiles **every file that
mentions that class at all**, including the 200 that never touch a typeclass and
only read `user.name`.

This is not an accident of my rig. It is
[`MemberRefInvalidator.get`](https://github.com/sbt/zinc/blob/develop/internal/zinc-core/src/main/scala/sbt/internal/inc/MemberRefInvalidator.scala),
verbatim:

```scala
case NamesChange(_, modifiedNames) if modifiedNames.in(UseScope.Implicit).nonEmpty =>
  new InvalidateUnconditionally(memberRef)
case NamesChange(_, modifiedNames) =>
  new NameHashFilteredInvalidator(usedNames, memberRef, modifiedNames, isScalaClass)
```

If any modified name is in `UseScope.Implicit`, **name hashing is switched off
and every member-reference dependent is invalidated unconditionally**. And
[`NameHashing.scala`](https://github.com/sbt/zinc/blob/develop/internal/zinc-apiinfo/src/main/scala/xsbt/api/NameHashing.scala)
decides membership of that scope by one line:

```scala
val (regularDefs, implicitDefs) = apiPublicDefs.partition(deff => !deff.modifiers.isImplicit)
```

and Scala 3's
[`ExtractAPI`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/sbt/ExtractAPI.scala)
sets that flag with `sym.isOneOf(GivenOrImplicit)`. A `derives Foo` clause
generates `given Foo[User]` in `User`'s companion. So changing the *set* of
derived typeclasses changes the set of given members, which puts a modified name
in the implicit scope, which disables name hashing for that class.

Zinc says so itself. Verbatim from the debug run, on the 24-source project where
13 classes depend on the model:

```
Change NamesChange(model.User,ModifiedNames(changes = UsedName(derived$Show,[Implicit])))
  invalidates 13 classes due to The model.User has the following implicit definitions changed
```

**The corollary is the good news, and it is bigger than the bad news.** Adding
or renaming a *field* does not change the derived given's **signature**, only its
inlined body, and Zinc's API model is signature-based. So the everyday eezo edit,
"add a column to the model", costs exactly one file no matter how many
typeclasses are derived and no matter how many files consume the model. The
expensive edit is the rare one.

### 1.2 The build tool must be resident, and that single fact is worth more than the choice of build tool

Measured, same 24-source project, same edit (add one field to the model):

| | median of 3 |
|---|---|
| `sbt compile` as a **fresh process**, nothing to do | **1692 ms** |
| `sbt compile` as a **fresh process**, one field added | **3059 ms** |
| **`sbt ~` watch, already resident**, one field added | **217 ms** |

A cold `sbt` invocation spends 1.7 seconds getting to the point where it can
decide there is nothing to do. That is 56% of the three-second budget consumed
before a single character of Scala has been typechecked. With an edit to
compile, it is 3.06 seconds, and **the budget is gone on a 24-file project**.

The resident-versus-cold gap is 14x. No difference between sbt, Mill and
scala-cli that I measured is anywhere near that large. Any dev loop that shells
out to a build tool per edit has already lost.

### 1.3 Mill and sbt 2.x use the *same* Zinc, so §1.1 is not a build-tool choice

Mill's own dependency file pins
[`mvn"org.scala-sbt::zinc:2.0.1"`](https://github.com/com-lihaoyi/mill/blob/main/mill-build/src/millbuild/Deps.scala).
sbt 2.0.x ships Zinc 2.0.x from the same repository (Zinc `v2.0.4` was released
2026-07-26, sbt `v2.0.4` the same day). Bloop, which scala-cli and Metals drive,
is also a Zinc consumer. **There is exactly one incremental-compilation
implementation in the Scala 3 world**, so the invalidation behaviour in §1.1 is a
property of Scala 3 plus Zinc, not of sbt. Build-tool selection is therefore a
question about ergonomics, plugin story and residency, not about compile
correctness or invalidation width.

### 1.4 Every fast-reload framework that exists on the JVM is fast for a reason eezo cannot borrow

Quarkus, Play, Spring Boot DevTools and Phoenix all converge on the same
architecture: keep a stable parent classloader with the framework and the
libraries, throw away a child classloader holding application classes, rebuild
the child. Quarkus adds one further trick: when *only method bodies* changed it
skips the restart entirely and calls JVMTI `redefineClasses`
([source](https://github.com/quarkusio/quarkus/blob/main/core/deployment/src/main/java/io/quarkus/deployment/dev/RuntimeUpdatesProcessor.java)).

All of that optimises **the reload term**. Measured for eezo's stack, the reload
term is already small: `research/http-server.md` §6 measures Jetty at **229 ms
wall clock from JVM start** and **0.7 ms to rebind the same port** when the
server is reconstructed inside a fresh classloader, and §1.4 of that document
proves Jetty's classloader is actually released so the swap does not leak.

The expensive term for eezo is the **compiler**, and none of these frameworks has
anything to say about it, because javac is not scalac. Quarkus's own source
carries the tell: it logs a hint when a live reload takes more than four seconds.
Copying Quarkus's classloader architecture would buy eezo, at most, the 229 ms of
JVM start. Copying it will not make Scala compile faster.

### 1.5 The build-tool recommendation is sbt, held for one strong reason, and sbt's own file watcher is the part to replace

sbt 1.12.x with a resident `~` session hits **148 to 300 ms** end to end from
file save to compile complete on the single-file model edits that matter (§4.4).
scala-cli over Bloop hits **353 to 528 ms** for the same edits, and a large
constant of that is the scala-cli launcher process, which a framework-owned dev
server would not pay. On single-file latency the two are close enough that the
decision should not be made on latency.

It should be made on the **source generator** story, because eezo needs one for
its route table. sbt has `Compile / sourceGenerators`, a first-class, documented,
incrementally-aware hook that every sbt user already understands. Mill has the
equivalent through task graph composition. scala-cli has no plugin model at all:
its extension point is `//> using` directives, and there is no place to hang a
code generator. That rules scala-cli out as eezo's *shipped* build integration,
though it remains the right tool for eezo's own micro-benchmarks.

One caveat lands on `~` rather than on sbt. **A multi-file edit makes sbt's
watcher fire twice and run both builds.** Measured at 204 sources, an
editor-style rename touching 51 files compiles in 215 ms when driven directly but
takes **774 to 1101 ms** under `~`, while scala-cli, invoked once after the
writes finish, takes 638 ms. eezo should drive the compile itself with a
debounce, not delegate to `~`. That single change is the largest saving anywhere
in this document.

---

## 2. Method, and what was thrown away

### 2.1 The salvaged rig, and why two of its five numbers were meaningless

The 2026-07-26 rig ran a **separate cold `sbt -batch compile` process per
experiment** and grepped the log. Two of its five runs ended in
`Compilation failed`, and its own README flagged that they were never diagnosed.
Both diagnoses are trivial and both invalidate the run:

- `C_rename_field` renamed `User.name` and did not touch the six `Named*.scala`
  files that read `u.name`. Log: six copies of
  `value name is not a member of model.User`. The "invalidation count" was
  counting a failed compile.
- `D_separate_given` summoned `Codec[Order]` from a file that had not imported
  the given. Log:
  `No given instance of type model.Codec[model.Order] was found ... given instance orderCodec in package model was not considered because it was not imported with import given`.

There is a third, quieter defect that would have mattered even if those two had
passed: **timing a cold `sbt -batch` process measures sbt's boot time**, which
§1.2 shows is 1.7 s, an order of magnitude above the thing being measured. The
salvaged logs are preserved at
[`harnesses/zinc-lab/logs/2026-07-26-salvage/`](harnesses/zinc-lab/logs/2026-07-26-salvage/)
and nothing in this document is quoted from them.

### 2.2 The rewritten rig

[`harnesses/zinc-lab/`](harnesses/zinc-lab/) is now a generator plus four
drivers.

`gen.sh <outdir> <n_typeclass_consumers> <n_member_consumers> <n_unrelated> <n_derives>`
emits a self-contained sbt project in eezo's shape:

- `model/Typeclasses.scala`: five `Mirror`-based typeclasses whose `derived` is an
  `inline def`, which is how Magnum's `DbCodec` and every Scala 3 derivation of
  this shape works.
- `model/User.scala`: `case class User(...) derives Codec, Table, Form, Resource`
  with a method body, plus a second case class `Order` with no `derives`.
- `model/OrderInstances.scala`: `given orderCodec: Codec[Order] = Codec.derived`,
  the "instance lives in a different file from the case class" shape.
- `app/Consumer*.scala`: N files that `summon` all four derived instances.
- `app/Named*.scala`: N files that reference exactly one field by name.
- `app/Unrelated*.scala`: N files that touch nothing, as a control.

Three settings matter. `incOptions.withRecompileAllFraction(1.0)` defeats Zinc's
"if enough of the build is invalidated, just recompile everything" heuristic, so
reported counts are true transitive invalidation sets rather than a rounding up.
`turbo := false` keeps the classloader layering standard. `logLevel` is switched
to `Debug` for the invalidation run only, because debug logging is itself a cost.

The critical change from the salvaged rig: **the whole experiment matrix runs
inside one sbt batch invocation**, using two custom sbt commands that the
generator writes into `build.sbt`. `edit <name>` shells out to `edits.sh` from
inside the running session; `timed <label>` wall-clocks one `Compile / compile`,
including Zinc's up-to-date check, and prints microseconds. The compiler, the
JIT and Zinc's analysis therefore stay warm across all fourteen edits, which is
the state a real dev loop is in.

Protocol per experiment, repeated three times: `edit reset` (an `rsync -c`
restore, so unchanged files keep their mtime), `timed settle_E` (compile back to
pristine, not reported), `edit E`, `timed E` (the measurement). Every edit is
required to leave the project **compiling**; `rename_used` renames the field
*and* fixes all six or 200 consumers in the same edit, which is what an editor's
rename refactor does.

`loop.sh` measures the terms `run.sh` deliberately excludes: cold `sbt`
processes, `sbt ~` watch latency from file save to rebuild complete, and the
same edits under scala-cli.

`derives-scale.sh` measures what a `derives` clause costs the compiler as a
function of clause length and model count, with
`scala-cli compile --server=false -O -Yprofile-enabled`, reporting the sum of
per-phase `run ns` from the compiler's own profiler. That is the same instrument
and the same reporting convention as `research/capture-checking.md` §5 and
`research/db-query-layer.md` §3, so all three documents' numbers are comparable.

### 2.3 One measurement was thrown away mid-run and rebuilt

The first watch-latency driver detected "rebuild finished" by counting sbt's
`Monitoring source files` lines. It produced 72 ms for a seven-file recompile,
which is faster than the compile itself, so it was wrong. Two causes: a
multi-file edit makes sbt's watcher fire on the first file written, and a
transiently broken intermediate state produces a monitor line for a **failed**
build that is indistinguishable from a very fast success. The fix was to have
the build print its own completion marker from a task body, which by sbt's
evaluation order runs strictly after `Compile / compile`, and to wait for the
build to go quiet rather than for one tick. The discarded numbers are kept at
`logs/loop-small-v1-suspect.tsv` and are not quoted.

---

## 3. Build tools

### 3.1 Status of the field, verified today

| | current version | released | maintenance signal |
|---|---|---|---|
| **sbt 1.x** | 1.12.14 | 2026-07-16 | actively maintained in parallel with 2.x |
| **sbt 2.x** | **2.0.4** | **2026-07-26** | 2.0.0 GA on 2026-06-14, four patches since |
| **Zinc** | 2.0.4 | 2026-07-26 | same repo, same cadence as sbt 2 |
| **Bloop** | 2.1.1 | 2026-07-07 | **actively maintained**, commits within 3 days |
| **Mill** | 1.1.7 | 2026-06-21 | active, 1.2.0-RC1 out |
| **sbt-revolver** | 0.10.0 | **2023-04-08** | last commit 2024-10-18, **no sbt 2.x artifact** |

Two of these deserve comment because the ticket asked.

**Bloop is not abandoned.** Its README carries no maintenance or deprecation
notice, its latest release is three weeks old, and its most recent commits are
three days old and include bug fixes rather than only dependency bumps
([releases](https://github.com/scalacenter/bloop/releases),
[commits](https://github.com/scalacenter/bloop/commits)). Whatever reputational
uncertainty Bloop accumulated, the primary evidence today says maintained.

**sbt 2.0 is GA and eezo would be an early adopter of it.** The
[change summary](https://www.scala-sbt.org/2.x/docs/en/changes/sbt-2.0-change-summary.html)
records three things that matter here. The build DSL is now Scala 3 ("sbt 2.x
build.sbt DSL, used for build definitions and plugins, is based on Scala 3.x
(currently 3.8.4)"). Plugins are republished under a new suffix ("sbt 2.x plugins
are published with `_sbt2_3` suffix"), which is why sbt-revolver has no sbt 2
artifact and why every plugin eezo might want must be checked individually.
And tasks are cached by default, to local disk and to a "Bazel-compatible remote
cache".

That last one is a genuine future asset for eezo's CI, and irrelevant to the
three-second loop, which never has a cache miss to avoid.

### 3.2 The five candidate configurations, and what each actually gives you

**sbt, plain (`sbt compile` per edit).** Disqualified by §1.2: 1.7 s of process
boot before any work, 3.06 s to compile a one-field change on a 24-file project.
Listed only because it is what a naive `Makefile`-style integration produces.

**sbt with `~compile` (or `~` any task).** The compiler stays hot in the sbt JVM,
Zinc's `Analysis` stays in memory, and the file watcher triggers on save. This is
the configuration that produced the 148 to 300 ms numbers in §4.4. sbt's watch is
native on macOS through `sbt.io`, not a poll loop; the trigger latency is inside
those numbers and is not separable from them with the instrument I used.

**sbt with sbt-revolver (`~reStart`).** Adds "fork the app in a background JVM,
kill and re-fork it when compilation succeeds". That is the crudest possible
reload strategy: it pays the full JVM start every time. `research/http-server.md`
§6 measures that at 229 ms wall clock for Jetty, which is affordable, so the
crudeness is not fatal. What *is* a problem is that sbt-revolver's last release
was 2023-04-08 and there is no `_sbt2_3` artifact on Maven Central, so choosing
sbt-revolver pins eezo to sbt 1.x or commits eezo to maintaining a fork. eezo can
implement `reStart` itself in about a hundred lines and should.

**sbt with Bloop.** Bloop is a build server: it holds compiler instances and Zinc
analysis in a long-lived daemon, so *any* client gets warm compiles. In practice
the client is Metals. Running Bloop underneath sbt duplicates residency that a
resident sbt already has. Bloop's real value to eezo is that a user's editor is
already talking to it, so an editor-triggered compile and a dev-server-triggered
compile can share one warm compiler instead of fighting over two.

**Mill.** Uses Zinc 2.0.1, so §1.1 applies unchanged. `--watch`/`-w` re-evaluates
when inputs change, and `runBackground` is the built-in equivalent of
sbt-revolver: Mill's docs say it exists "for long-running processes like web
servers ... to make sure they recompile and restart when code changes, forcefully
terminating the previous process even though it may be still alive"
([flags](https://mill-build.org/mill/cli/flags.html)). Mill's design docs are
candid that residency is a performance measure rather than the model: "The Mill
build process is meant to be run over and over, not only as a long-lived
daemon/console", while "By default Mill uses a long-lived compile server to speed
things up even more"
([design principles](https://mill-build.org/mill/depth/design-principles.html)).
Mill was **not measured** here: no Mill launcher and no coursier were installed
on this machine, and bootstrapping one would have measured a first-run download
rather than a dev loop. That is a real gap and it is handed to #16.

**scala-cli.** Drives Bloop by default; its own docs say "Scala CLI uses `bloop`
by default" and offer `--server=false` to turn it off
([compile docs](https://scala-cli.virtuslab.org/docs/commands/compile)).
Measured at 353 to 478 ms per edit (§4.4), of which a large constant is the
launcher process. It has `--watch`. It has no plugin system, so no route-table
generator. It stays in eezo's toolbox for benchmarking, and out of eezo's
shipped dev loop.

### 3.3 How eezo ships a route-table generator through each

This is the axis that decides #16, and it is not close.

- **sbt**: `Compile / sourceGenerators += Def.task { ... }`. The generated file
  lands in `target/.../src_managed`, Zinc treats it as an ordinary source, and
  everything in §4 applies to it. An eezo sbt plugin can register this in one
  line of `autoImport`. This is the only mechanism in the field that is both
  standard and incremental-aware.
- **Mill**: a `Task` producing sources, composed into `generatedSources`. Equally
  capable, differently spelled, with a much smaller pool of users who will
  recognise it.
- **scala-cli**: nothing. There is no plugin API.

There is a design consequence in §4 that whoever writes the generator must know:
**if the generated route table contains `given` values, every edit that changes
the set of those givens will invalidate every file that references the route
table.** Generate a plain `val routes: List[Route]`, not a family of givens.

---

## 4. Incremental compilation in Scala 3, measured

This is the crux section.

### 4.1 What Zinc does, from its own source and docs

Zinc records, per class, an extracted **API** (signatures, not bodies), a set of
**used names**, and dependency edges in three kinds. The
[sbt documentation](https://www.scala-sbt.org/1.x/docs/Understanding-Recompilation.html)
states the distinction that matters:

> all other direct dependencies are considered by name hashing optimization;
> other dependencies are also called "member reference" dependencies because they
> are introduced by referring to a member (class, method, type, etc.) defined in
> some other source file

and

> all dependencies introduced by inheritance are _not_ subject to name-hashing
> analysis so they are never marked as irrelevant

and that inheritance dependencies "are included _transitively_".

Scala 3's compiler bridge produces these edges in
[`ExtractDependencies.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/sbt/ExtractDependencies.scala):
`DependencyByMemberRef` for ordinary references, `DependencyByInheritance` and
`LocalDependencyByInheritance` for `extends`, and `DependencyByMacroExpansion`
for the type arguments of inline calls.

When a class's API changes, `MemberRefInvalidator` picks a strategy. Four of the
five branches bypass name hashing entirely and invalidate **all** member-reference
dependents:

| API change | strategy |
|---|---|
| `TraitPrivateMembersModified` | no invalidation |
| `APIChangeDueToMacroDefinition` | **invalidate unconditionally** |
| `APIChangeDueToAnnotationDefinition` | **invalidate unconditionally** |
| `NamesChange` with any name in `UseScope.Implicit` | **invalidate unconditionally** |
| `NamesChange` otherwise | name-hash filtered |

The fourth row is eezo's row, for the reason given in §1.1.

### 4.2 How Scala 3 tracks `inline`, and it tracks it well

Zinc's API model is signatures only, which would make `inline` unsound: the body
of an inline method is part of its observable behaviour at every call site.
Scala 3 solves this by hashing the body into the signature. From
[`ExtractAPI.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/sbt/ExtractAPI.scala),
verbatim:

> If the body of an inline def changes, all the reverse dependencies of this
> method need to be recompiled. sbt has no way of tracking method bodies, so we
> include the hash of the body of the method as part of the signature we send to
> sbt.

Measured, this works exactly as advertised and it is **narrow**. Changing the body
of `Codec.derived`, an `inline def` that every `derives Codec` expands:

| project size | sources recompiled | warm compile |
|---|---|---|
| 24 sources | 1 + 3 | 181 ms |
| 204 sources | 1 + 3 | 198 ms |
| 504 sources | 1 + 3 | 219 ms |

The invalidation set does not grow with project size. Zinc's own debug output
names the affected classes as `model.User` and `model.OrderInstances$package`,
which are precisely the two **inline expansion sites**, and it reports the change
as `UsedName(derived,[Default])`, that is, in the *default* scope, so name
hashing applies and the 200 files that merely `summon[Codec[User]]` are left
alone. **Editing eezo's framework internals does not recompile the user's app**,
as long as the edit is to an inline body and not to the set of givens.

There is one open, named limitation to keep in view: `DependencyByMacroExpansion`
is added defensively in the Scala 3 bridge, guarded by a reflective check,
because "it was added later to the zinc.apiinfo DependencyContext enum, e.g. pre
1.10.x sbt would throw java.lang.NoSuchFieldError errors here". Macro-expansion
dependency tracking is therefore a comparatively recent addition, and eezo should
treat sbt versions below 1.10 as unsupported.

### 4.3 The full invalidation table, measured

Scala 3.8.4, sbt 1.12.1, warm session, median of three repetitions, JDK 26.
`sources` is the total across all of Zinc's internal rounds; where Zinc needed
two rounds the split is given.

**Small project, 24 sources** (6 typeclass consumers, 6 field-name consumers,
8 unrelated), `derives Codec, Table, Form, Resource`:

| Edit | sources | rounds | ms |
|---|---|---|---|
| `touch` the model, no content change | **0** | 0 | **10** |
| append a comment to the model | 1 | 1 | 99 |
| change a method body on the model | 1 | 1 | 97 |
| add a field | 1 | 1 | 97 |
| rename an unreferenced field | 1 | 1 | 97 |
| change a field type `Int` to `Long` | 1 | 1 | 96 |
| rename a field 6 files reference (+ fix them) | 7 | 1 | 110 |
| **add a 5th typeclass to `derives`** | **13** | 2 (1+12) | **182** |
| **remove a typeclass from `derives`** (+ fix consumers) | **19** | 2 | **185** |
| add a field to the class whose given is in another file | 2 | 2 | 149 |
| change an `inline def` body in the typeclass | 4 | 2 (1+3) | 181 |
| change a non-inline given's body in the typeclass | 1 | 1 | 85 |
| change an unrelated file | 1 | 1 | 47 |
| change a consumer file | 1 | 1 | 71 |
| clean build | 24 | 1 | 1999 |

**Scale, 204 sources** (50 / 50 / 100) and **504 sources** (200 / 200 / 100):

| Edit | 204: sources | 204: ms | 504: sources | 504: ms |
|---|---|---|---|---|
| add a field | 1 | 106 | 1 | 129 |
| change a field type | 1 | 110 | 1 | 132 |
| rename a referenced field (+ fix consumers) | 51 | 215 | 201 | 429 |
| **add a typeclass to `derives`** | **101** (1+100) | **336** | **401** (1+400) | **766** |
| **remove a typeclass from `derives`** | **151** | **426** | **601** (201+400) | **977** |
| change an `inline def` body | 4 | 198 | 4 | 219 |
| add a field to the separately-given class | 2 | 164 | 2 | 187 |
| clean build | 204 | 2833 | 504 | 3692 |

Four things to read out of this.

**A pure `touch` is free.** Zinc stamps sources by content hash, not mtime, so a
save that changes nothing costs 10 ms and recompiles zero files. Editors that
save aggressively do not cost anything.

**Every field-level edit is O(1) in project size.** 97 ms, 106 ms, 129 ms at 24,
204 and 504 sources. The growth is Zinc's up-to-date scan, not compilation.

**`derives`-clause edits are O(dependents), and they are the only edit that is.**
The slope from the three sizes is about 1.6 ms per additional invalidated source
over a floor of roughly 100 ms. Extrapolating to a 2000-file application with
1600 model-dependent files gives about 2.7 s, which is the whole budget. This is
the number that should make eezo nervous, and §9 says what to do about it.

**A separately-defined given costs one extra file and nothing more.** Putting
`given DbCodec[Order] = DbCodec.derived` in a different file from `Order` makes
an edit to `Order` recompile both files. It does not propagate further. This
shape is safe.

### 4.4 The loop, end to end, measured

`sbt ~`, resident, wall clock from the moment `edits.sh` writes the file to the
moment the build prints its own completion marker. First repetition of each edit
is shown separately because it is consistently an outlier (JIT, and sbt's watcher
warming up).

**24-source project:**

| Edit | run 1 | run 2 | run 3 | rebuilds triggered |
|---|---|---|---|---|
| append a comment | 1423 | 290 | 258 | 1 |
| add a field | 219 | 195 | 217 | 1 |
| rename an unreferenced field | 174 | 162 | 155 | 1 |
| change a field type | 173 | 151 | 148 | 1 |
| rename a referenced field (7 files written) | 344 | 282 | 293 | **2** |
| add a typeclass to `derives` | 263 | 245 | 245 | 1 |

**204-source project:**

| Edit | run 1 | run 2 | run 3 | rebuilds triggered |
|---|---|---|---|---|
| append a comment | 1483 | 300 | 249 | 1 |
| add a field | 224 | 211 | 194 | 1 |
| rename an unreferenced field | 179 | 177 | 184 | 1 |
| change a field type | 175 | 156 | 159 | 1 |
| rename a referenced field (51 files written) | **1101** | **844** | **774** | **2** |
| add a typeclass to `derives` | 456 | 425 | 419 | 1 |

The `rebuilds` column is a finding in itself: **a multi-file edit makes sbt's
watcher fire twice**, once on the first file written and again on the rest, and
both builds run to completion. The cost is not academic. At 204 sources, the
`rename_used` edit compiles 51 sources in 215 ms when driven directly (§4.3) but
takes **774 to 1101 ms** end to end under `sbt ~`, because the watcher triggers
on the model file first, compiles it against 50 unfixed consumers, and then
compiles again once the consumers land. That is a **3.6x to 5x penalty on
exactly the edit an IDE rename refactor produces**, and it is the strongest
single argument for eezo driving compilation itself, debounced, rather than
relying on `~` (§9, entry 3).

Same edits under **scala-cli over Bloop**, including the scala-cli launcher
process:

| Edit | 24 sources | 204 sources |
|---|---|---|
| append a comment | 390 / 406 / 440 | 464 / 513 / 528 |
| add a field | 353 / 364 / 415 | 453 / 476 / 479 |
| rename a referenced field | 380 / 394 / 404 | 588 / 638 / 639 |
| add a typeclass to `derives` | 475 / 475 / 478 | 779 / 841 / 848 |

Note that scala-cli does **not** show the double-rebuild penalty, because the
driver invokes it once after all files are written rather than reacting to file
events. Its `rename_used` at 204 sources is 638 ms against sbt-watch's 844 ms,
and that gap is entirely sbt's second build.

The cold-process cost is the same at both sizes, confirming it is boot overhead
rather than work: `sbt compile` as a fresh process with nothing to do is
**1692 ms** at 24 sources and **1715 ms** at 204; with one field added it is
**3059 ms** and **3129 ms**.

These agree with `research/db-query-layer.md` §3.3 and §3.4, which measured the
same instrument at 0.36 s to 0.75 s for warm incremental edits, and with
`research/capture-checking.md` §5.3, which measured 0.51 s to 0.54 s for a
one-file edit in an 80-file project. Three independent rigs on three different
tickets agree that **a warm Scala 3 incremental compile of a small edit costs
roughly 0.15 s to 0.5 s depending on how much process overhead the harness
includes**. That is the most reliable number in this document.

### 4.5 The cost of the `derives` clause itself

Clause length costs the compiler linearly and costs the *incremental* loop
nothing, because derivation happens at the definition site and nowhere else.

Measured with the compiler's own profiler
(`scala-cli compile --server=false -O -Yprofile-enabled`, sum of per-phase
`run ns`, mean of two runs, same convention as `capture-checking.md` §5 and
`db-query-layer.md` §3):

| models | 1 typeclass | 2 | 4 | 8 |
|---|---|---|---|---|
| 5 | 964.0 ms | 994.2 | 1118.1 | 1235.6 |
| 20 | 1343.3 ms | 1437.4 | 1655.1 | 1903.6 |
| 40 | 1643.6 ms | 1810.0 | 2069.4 | 2486.5 |

Run-to-run variance was under 2.5% in every cell. Marginal clean-build cost per
model, taken from the 5-to-40 slope:

| clause length | ms per model |
|---|---|
| 1 typeclass | 19.4 |
| 2 | 24.2 |
| 4 | 27.2 |
| 8 | 35.7 |

So each additional typeclass in a `derives` clause costs roughly **2 to 2.6 ms
per model** of clean-build compile time. Doubling from four typeclasses to eight
on a forty-model application costs 417 ms of clean build and nothing at all on a
warm incremental edit.

The corresponding sbt clean builds agree: at 204 sources, `derives Codec` alone
is 2683 ms and `derives Codec, Table, Form, Resource` is 2833 ms; at 24 sources
1959 ms versus 1999 ms. And warm incremental edits are indistinguishable between
the two variants (`add_field` 102 ms versus 106 ms at 204 sources), because only
the model file recompiles and it does the same derivation work either way.

**Two honesty notes about these numbers.**

First, **the toy typeclasses here are about twenty times cheaper than a real
one.** `research/db-query-layer.md` §3.2 measured Magnum's `derives DbCodec` at
**+41.5 ms per entity** over a bare case class, where mine cost about 2 ms.
That difference does **not** affect §4.3's invalidation results, and the reason
matters: when a `derives`-clause edit invalidates 400 consumer files, those
files contain `summon[Codec[User]]`, which resolves to an already-compiled given
in the companion. **They do not re-derive anything.** Their 1.6 ms each is
re-typechecking a summon, and that is independent of how expensive the derivation
is.

Second, there is one place where real derivation cost *does* multiply, and it is
eezo's own developer loop rather than the user's. §4.2 measured that changing an
`inline def derived` body invalidates exactly the expansion sites. On a forty-
model application every model file is an expansion site, so an edit to eezo's
`DbCodec.derived` recompiles forty model files, each re-deriving. At Magnum's
+41.5 ms per entity that is roughly **1.7 s**, and it is paid by whoever is
hacking on eezo's derivation code. That is worth knowing before eezo's own repo
is laid out.

---

## 5. Class reloading

### 5.1 Standard HotSwap: method bodies only, and the spec is unambiguous

The JVM TI specification for
[`RedefineClasses`](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#RedefineClasses)
states the limit in one sentence:

> The redefinition may change method bodies, the constant pool and attributes
> (unless explicitly prohibited). The redefinition must not add, remove or rename
> fields or methods, change the signatures of methods, change modifiers, or
> change inheritance. The redefinition must not change the NestHost, NestMembers,
> Record, or PermittedSubclasses attributes.

The failure modes are named errors:
`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED` for a changed field,
`..._METHOD_ADDED`, `..._METHOD_DELETED`, `..._HIERARCHY_CHANGED`,
`..._CLASS_MODIFIERS_CHANGED`.

Three further facts from the same spec bear directly on a stateful framework:

> redefining a class does not cause its initializers to be run. The values of
> static fields will remain as they were prior to the call.

> Instances of the redefined class are not affected -- fields retain their
> previous values.

> If a method has active stack frames, those active frames continue to run the
> bytecodes of the original method version.

**What survives standard HotSwap:** every object on the heap, every static field,
every open socket, every session, every LiveView-style server-side component
state. **What forces a restart:** adding or removing any member. For eezo that
means adding a field to a case class, adding a method to a controller, and adding
a typeclass to a `derives` clause are all restarts. Since a case class with a new
field is a schema change and eezo's whole premise is editing case classes,
**standard HotSwap covers approximately none of eezo's interesting edits.**

### 5.2 DCEVM and JetBrains Runtime: available, maintained, and narrower than advertised

DCEVM's enhanced redefinition is merged into **JetBrains Runtime**, whose README
states it "supports enhanced class redefinition (DCEVM) ... this feature needs to
be explicitly enabled with `-XX:+AllowEnhancedClassRedefinition`"
([JBR](https://github.com/JetBrains/JetBrainsRuntime)). JBR ships for macOS,
Linux and Windows.

**Availability for eezo's JDK floor is good.** The most recent JBR releases are
`jbr-release-25.0.3b508.16` (2026-06-23) and `jbr-release-21.0.11b1163.116`
(2026-05-15), so both 21 and 25 are live. HotswapAgent's own instructions cover
"Java 17/21/25" and tell you to drop `hotswap-agent.jar` into `lib/hotswap`
([HotswapAgent README](https://github.com/HotswapProjects/HotswapAgent)).
HotswapAgent itself released `RELEASE-2.0.3` on 2026-01-22 with commits in
February 2026, so it is maintained, if not briskly.

**What it buys, per its own documentation**
([hotswapagent.org](http://hotswapagent.org/)): "Add/remove/modify class fields",
"Add/remove/modify methods", "Add/remove/modify classes including anonymous
classes", "Add/remove static member of classes", "Add/remove enum values". The
stated limit: "The only unsupported operation is hierarchy change (change the
superclass or remove an interface)."

Mapped onto eezo's edits: adding a field to a case class becomes hot-swappable,
which is the single most common eezo edit and the one standard HotSwap cannot do.
Adding a typeclass to a `derives` clause adds an anonymous class and a static
member, which is also permitted. Changing what a case class `extends` is not.

**Why it is still not the answer.** Three reasons, in descending order of force.
First, it requires the developer to run JetBrains Runtime rather than their own
JDK, which is a hard sell for a framework demo and impossible to assume of users.
Second, it saves the reload term, which §1.4 shows is 229 ms, out of a budget
whose expensive term is the compiler. Third, HotswapAgent's value comes from its
framework plugins (Spring, Hibernate, and so on) reinitialising framework state
after a swap; **there is no Scala or eezo plugin**, so eezo would have to write
its own plugin to re-register routes and re-derive instances, which is the same
work as writing a classloader swap and strictly more fragile.

### 5.3 Custom classloader swapping: what actually survives

This is what Play, Quarkus and Spring Boot DevTools all do, and it is the only
mechanism in this section that eezo can adopt without asking the user to change
their JDK.

The structure is always the same: a **stable parent** loader holding the JDK, the
Scala library and the framework, and a **disposable child** holding application
classes. On reload the child is dropped and rebuilt from the new class files.

**What survives:** anything reachable from the parent loader. That is the HTTP
server (`research/http-server.md` §6 measures Jetty rebinding the same port in
**0.7 ms**), the connection pool, the JVM's JIT state for framework code, and any
state eezo deliberately parks in a parent-loaded holder.

**What does not survive:** every instance of every application class, because its
`Class` object is gone. For a server-stateful LiveView framework that is exactly
the state you wanted to keep. eezo therefore has to decide whether live component
state is serialised across a reload or discarded, and that is an eezo design
decision, not a JVM constraint. Phoenix, notably, does not have this problem at
all, because the BEAM's unit of hot code loading is the module and the VM keeps
two versions of it live, so processes holding state simply keep running.

**The failure mode to guard against:** if any application class leaks a reference
into a parent-loaded structure (a static cache, a thread-local, a shutdown hook,
a running thread), the child loader is retained and every reload leaks a full
copy of the application. `research/http-server.md` §1.4 already measured this for
the two candidate servers and it is why Jetty was chosen: Jetty released the
loader in 4 out of 4 iterations, Helidon retained it in 4 out of 4.

---

## 6. Fast JVM restart as the alternative

### 6.1 CDS and AppCDS

Class-data sharing has been in HotSpot since JDK 5 and, as JEP 483 notes,
"builds of JDK 12 and later include a built-in CDS archive containing the
metadata of over a thousand commonly-used JDK classes"
([JEP 483](https://openjdk.org/jeps/483)). Every JDK 21 and 25 run eezo makes
already benefits from it without configuration. AppCDS extends this to
application classes and is available on 21 and 25 alike. This is a *difference of
degree*, not a differentiator between the two JDK floors.

### 6.2 The AOT cache, JEP 483 / 514 / 515: a production feature that a dev loop cannot use

[JEP 483](https://openjdk.org/jeps/483) landed the AOT cache in **JDK 24**.
[JEP 514](https://openjdk.org/jeps/514) (one-step `-XX:AOTCacheOutput`) and
[JEP 515](https://openjdk.org/jeps/515) (cached method profiles) landed in
**JDK 25**. This is a real reload-relevant difference between eezo's 21 floor and
a 25 floor, and it is worth stating precisely for #35.

The headline numbers, from JEP 483 itself:

> This program runs in 0.031 seconds on JDK 23. After doing the small amount of
> additional work required to create an AOT cache it runs in in 0.018 seconds on
> JDK 24 -- an improvement of 42%.

> For a representative server application, consider Spring PetClinic, version
> 3.2.0. It loads and links about 21,000 classes at startup. It starts in 4.486
> seconds on JDK 23 and in 2.604 seconds on JDK 24 when using an AOT cache --
> also an improvement of 42%.

JEP 515 adds a further 19% on a warmup microbenchmark ("runs in 90 milliseconds
with an AOT cache that contains no profiles ... it runs in 73 milliseconds").

**And none of it is usable in eezo's dev loop**, for a reason JEP 483 states
outright:

> All runs must have consistent class paths. A subsequent run may specify extra
> class-path entries, appended to the training class path; otherwise, the class
> paths must be identical. **Class paths must contain only JAR files; directories
> in class paths are not supported** because the JVM cannot efficiently check
> them for consistency.

A dev loop's classpath is `target/scala-3.x/classes`, a directory, and its
contents change on every edit. The cache would be silently ignored (or, with
`-XX:AOTMode=on`, would refuse to start). AppCDS has the same directory
restriction in its strict modes.

**Verdict: the AOT cache is a JDK-25-floor argument for eezo's `deploy` story and
for `research/deploy-target.md`, and contributes nothing to the three-second
loop.** It is worth two sentences in #35 and no engineering.

### 6.3 CRaC and CRIU: not applicable, for two independent reasons

CRaC checkpoints a warmed-up JVM and restores it later
([CRaC docs](https://github.com/CRaC/docs)). Its published results were collected
on "laptop with Intel i7-5500U ... Linux kernel 5.7.4-arch1-1 ... data was
collected in container running `ubuntu:18.04` based image".

The first reason it does not apply is platform: CRaC's checkpointing is CRIU, and
CRIU is Linux-only. The Scala Days demo and the developer machine in this
investigation are macOS. There is no CRaC on macOS.

The second reason is more fundamental and would hold even on Linux: **a
checkpoint captures loaded classes, and a dev loop's whole point is that the
classes just changed.** Restoring a checkpoint taken before the edit gives you
the old code. CRaC is a cold-start optimisation for a fixed artifact.

Availability, for the record: the newest build published under
[CRaC/openjdk-builds](https://github.com/CRaC/openjdk-builds/releases) is
`24-crac+20` from 2024-12-12, so there is no JDK 25 CRaC build from that source;
CRaC-enabled JDK 21 builds are shipped by Azul and BellSoft. This is irrelevant
to eezo but is the kind of thing #35 will be asked about.

### 6.4 What actually helps a dev loop

Nothing in this section. The realistic cold-start figure for a JVM web app of
eezo's shape is already measured: `research/http-server.md` §6 gives Jetty at
**229 ms wall clock from JVM start**, of which 64 ms is Jetty construction and
165 ms is the JVM getting to `main`. That 229 ms is 7.6% of a three-second
budget. Optimising it with AOT caches, CRaC or CRIU is optimising the wrong term,
and doing so would introduce constraints (jar-only classpaths, Linux-only
checkpoints) that a dev loop cannot satisfy.

---

## 7. How comparable frameworks do it

### 7.1 Play: compile on request, swap a `URLClassLoader`

Play's dev mode is a two-classloader design driven through a deliberately
Java-only interface,
[`BuildLink`](https://github.com/playframework/playframework/blob/main/dev-mode/play-build-link/src/main/java/play/core/BuildLink.java),
"written in Java and uses only Java types so that communication can work even
when the plugin and embedded Play server are built with different versions of
Scala". Its single interesting method is `reload()`, documented to return "either
a Throwable ... a ClassLoader - If the classloader has changed ... or null - If
nothing changed".

The implementation in
[`DevServerReloader`](https://github.com/playframework/playframework/blob/main/dev-mode/play-run-support/src/main/java/play/runsupport/DevServerReloader.java)
does three things worth copying and one worth avoiding.

Worth copying: it decides whether to reload by **maximum `lastModified` across
the compiled classpath**, not by whether sources changed, so an edit that
compiles to identical bytecode does not reload. It names each loader
`ReloadableClassLoader(vN)` with an incrementing counter, which makes loader
leaks trivially visible in a heap dump. And it runs compilation with the
reloader's own context classloader, with a comment explaining why: "use Reloader
context ClassLoader to avoid ClassLoader leaks in sbt/scala-compiler threads".

Worth avoiding: compilation is triggered **by the HTTP request**, not by the file
save. `reload()` "is invoked on every request", and `BuildLink` warns that "This
method is called multiple times on every request, so it is advised that change
detection happens asynchronously to this call". The consequence is that the user
pays the entire compile latency *after* hitting refresh, staring at a blank tab.
eezo, which has a live WebSocket to the browser anyway, can compile on save and
push, which moves the compile off the user's perceived critical path entirely.
That is a genuine architectural advantage of the LiveView model and eezo should
take it.

### 7.2 Quarkus: how the sub-second claim is actually constructed

Quarkus's dev mode is four classloaders. Per its
[class-loading reference](https://quarkus.io/guides/class-loading-reference), the
**augmentation** and **base runtime** loaders are persistent: each "is
persistent, even if the application restarts it will remain". The **deployment**
and **runtime** loaders are not: the deployment loader "is non-persistent, it
will be re-created when the application is started" and the runtime loader is
"recreated when the application is restarted".

That is the same trick as Play and Spring. The part that produces sub-second
numbers is different and it is visible in
[`RuntimeUpdatesProcessor.doScan`](https://github.com/quarkusio/quarkus/blob/main/core/deployment/src/main/java/io/quarkus/deployment/dev/RuntimeUpdatesProcessor.java):

```java
//attempt to do an instrumentation based reload
//if only code has changed and not the class structure, then we can do a reload
//using the JDK instrumentation API (assuming we were started with the javaagent)
if (changedClassResults.deletedClasses.isEmpty()
        && changedClassResults.addedClasses.isEmpty()
        && !changedClassResults.changedClasses.isEmpty()) {
    ...
    for (ClassInfo clazz : current.getKnownClasses()) {
        ClassInfo old = lastStartIndex.getClassByName(clazz.name());
        if (!ClassComparisonUtil.isSameStructure(clazz, old) ...) { ok = false; break; }
    }
    if (ok) {
        log.info("Application restart not required, replacing classes via instrumentation");
        ClassChangeAgent.getInstrumentation().redefineClasses(defs);
```

So Quarkus's fast path is **plain JVMTI `redefineClasses` gated on
`isSameStructure`**, which is §5.1's method-bodies-only limit. When the structure
changes, Quarkus does the full augmentation-plus-restart and logs
`Live reload total time: Xs`. Its own source contains the honest counterweight:

```java
if (TimeUnit.SECONDS.convert(timeNanoSeconds, TimeUnit.NANOSECONDS) >= 4 && !instrumentationEnabled()) {
    log.info("Live reload took more than 4 seconds, you may want to enable instrumentation based reload
             (quarkus.live-reload.instrumentation=true). ...");
```

Two things follow. First, the instrumentation path is **off by default**, so the
out-of-the-box Quarkus experience is the restart path. Second, Quarkus's own
authors budgeted for reloads exceeding four seconds.

**Is the mechanism portable to Scala 3?** The classloader half, yes, and eezo
should take it. The instrumentation half, essentially no, and for a reason that
has nothing to do with the JVM. `isSameStructure` is only satisfied when nothing
about the class's shape changed. A Scala 3 `derives` clause generates anonymous
classes; adding a field to a case class changes its constructor, its
`productArity`, its `unapply`, its `copy` default methods and the body of every
derived instance. **The class structure changes on almost every edit that a
framework built around "the case class is the source of truth" invites the user
to make.** Quarkus gets away with it because a Java developer editing a JAX-RS
resource method body changes exactly one method body.

And the decisive point is the one from §1.4: Quarkus's expensive term is javac,
which is fast. eezo's is scalac, which is not. Making eezo's reload term zero
takes 229 ms off a loop whose compiler term is 150 to 800 ms and could be 2.7 s
in the pathological case.

### 7.3 Spring Boot DevTools: the same two loaders, stated plainly

[Spring's documentation](https://docs.spring.io/spring-boot/reference/using/devtools.html):
"Classes that do not change (for example, those from third-party jars) are loaded
into a _base_ classloader. Classes that you are actively developing are loaded
into a _restart_ classloader. When the application is restarted, the _restart_
classloader is thrown away and a new one is created."

Its own claim is carefully limited: "This approach means that application
restarts are typically much faster than 'cold starts', since the _base_
classloader is already available and populated." It does not claim parity with a
reload agent, and it points elsewhere if that is not enough: "If you find that
restarts are not quick enough for your applications or you encounter classloading
issues, you could consider reloading technologies such as JRebel."

One operational detail eezo should copy: "As DevTools monitors classpath
resources, the only way to trigger a restart is to update the classpath ... the
modified files have to be recompiled to trigger a restart." Watching **class
files** rather than source files means the reload cannot race the compiler, which
is a real failure mode in naive source-watching designs.

### 7.4 Phoenix: the one that is genuinely different, and cannot be copied

[`Phoenix.CodeReloader`](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/code_reloader.ex)
is a Plug. It recompiles by "invoking the `:reloadable_compilers` on the list of
`:reloadable_apps`", and "To avoid race conditions, all code reloads are funneled
through a sequential call operation".

So structurally Phoenix is Play: compile on request, funnelled. The difference is
everything underneath. The BEAM hot-loads a module and keeps the old version live
for processes already executing it, so no classloader is discarded, no object
graph is orphaned, and stateful LiveView processes survive a code change. The JVM
offers no equivalent: `RedefineClasses` explicitly says "If a method has active
stack frames, those active frames continue to run the bytecodes of the original
method version", which is the *only* piece of BEAM-like behaviour it has, and it
comes with the method-bodies-only restriction.

**eezo should not promise Phoenix's reload semantics.** The honest LiveView-style
story on the JVM is "your component state is discarded on reload, and the browser
reconnects and re-renders", and eezo should design its client reconnect to make
that invisible rather than pretend the state survived.

---

## 8. Published Scala 3 compile-speed numbers and pathological cases

### 8.1 What the compiler team benchmarks

The Scala 3 repository carries its own benchmark suite at
[`bench/`](https://github.com/scala/scala3/tree/main/bench), and the shape of its
profiles is itself informative about where the team expects pain. The
[`implicits.yml`](https://github.com/scala/scala3/blob/main/bench/profiles/implicits.yml)
profile tracks four charts: "implicit cache I", "implicit cache II", "implicit
scope loop" and "inductive implicits". The
[`quotes.yml`](https://github.com/scala/scala3/blob/main/bench/profiles/quotes.yml)
profile tracks "Inline a quote", "Inline 1k quotes" and "Quote String
interpolation matching". Implicit search and inline expansion are the two axes
the compiler team considers worth a permanent regression chart, and they are
exactly eezo's two axes.

The
[`inductive-implicits.scala`](https://github.com/scala/scala3/blob/main/tests/bench/inductive-implicits.scala)
benchmark carries published numbers in its header, for inductive implicit
resolution over shapeless-style `HList`s:

| HList size | baseline | with `matchesPtInst` |
|---|---|---|
| 50 | 4 s | 3 s |
| 100 | 7 s | 4 s |
| 200 | 25 s | 5 s |
| 300 | 74 s | 6 s |
| 500 | **421 s** | 15 s |

Be careful with this one: the header says "1: baseline - scalac 2.12.5", so these
are **Scala 2.12.5** numbers preserved in the Scala 3 repo as the motivation for
the optimisation, not measurements of Scala 3. What they establish is the
*shape*: **inductive given resolution is superlinear in derivation depth**, and
the fix that tamed it was a specific compiler optimisation rather than anything
the user could do.

The practical bearing on eezo is direct. A `derives` chain that recurses over
`MirroredElemTypes` and calls `summonInline` per field is inductive given
resolution. It is linear in field count for flat case classes, which is what eezo
has, and it becomes superlinear when models nest deeply. **eezo should measure
its own derivation on a nested model tree before promising anything about it.**

### 8.2 The open pathological cases, with their numbers

**[scala/scala3#25728](https://github.com/scala/scala3/issues/25728), open,
filed 2026-04-08: exponential `PostTyper` time with chained transparent inline
macro operations.** Reported against 3.7.1 and reproducible on 3.8.x nightlies:

| chained additions | PostTyper time |
|---|---|
| 5 | 85 ms |
| 8 | 397 ms |
| 10 | 1.0 s |
| 12 | 4.0 s |
| 15 | ~90 s |

The reporter's summary is "Each additional `+` roughly doubles the compile time.
20 additions would take hours." The diagnosed cause is a `transform(call)` in
`PostTyper.scala` whose result is discarded, and a fix PR is open. **This is the
single most relevant open compiler bug to eezo**, because `transparent inline` is
the mechanism a framework reaches for when it wants a derivation to refine its
result type, and eezo's `derives Form, Resource` are exactly the kind of thing
that tempts you into `transparent inline`. The mitigation is a rule, not a
workaround: **eezo should not chain `transparent inline` operations**, and should
prefer plain `inline` wherever the result type is known.

**[scala/scala3#25930](https://github.com/scala/scala3/issues/25930), open, filed
2026-04-25: "Compilation slows down heavily when one type annotation is
missing".** A ten-line cats-effect snippet takes 2 to 4 seconds to compile on
3.8.3, 3.9.0-RC1 nightly and 3.3.7, and compiles substantially faster when an
explicit type argument is supplied at the call site. It is still labelled as
needing minimisation. The relevance to eezo is that a *user's* missing type
annotation, in a file eezo never sees, can add seconds to eezo's reload loop, and
eezo has no way to prevent it. This is the strongest argument for eezo shipping a
"why was this reload slow" diagnostic rather than only a time.

**Union and intersection type inference** has produced compile-time cliffs
historically but the open ones have been fixed. The relevant issue,
[scala/scala3#20120](https://github.com/scala/scala3/issues/20120), "Slow
compilation times when inferring type HKT with intersection types" (filed
2024-04-08), is **closed**, as are the neighbouring
[#14903](https://github.com/scala/scala3/issues/14903) (chained dependent match
types), [#16785](https://github.com/scala/scala3/issues/16785) (GADT reasoning in
pattern matches) and [#14333](https://github.com/scala/scala3/issues/14333)
(repeatedly diverging implicit search). I found no open issue of this family.
The honest statement is that this axis was a real problem in the 3.0 to 3.3 era
and is not one today, and that it is not a shape eezo generates anyway: derived
instances have concrete, named result types.

**[scala/scala3#25975](https://github.com/scala/scala3/issues/25975)**, the
opaque-type exponential under capture checking's safe mode, is already documented
with its full table in `research/capture-checking.md` §5.4 and is not repeated
here. It is relevant to this ticket only as further evidence that Scala 3's
compile-time cliffs are real, are found in normal-looking code, and are found by
users rather than by the compiler team.

### 8.3 The numbers eezo already owns

Three prior investigations on this machine, with the same instrument, give a
consistent picture that should be treated as eezo's baseline:

| Source | Measurement | Result |
|---|---|---|
| `db-query-layer.md` §3.2 | clean build, 40 entities, `derives DbCodec` + queries | 3.69 s (Magnum), 1.41 s (bare baseline) |
| `db-query-layer.md` §3.2 | marginal clean-build cost per entity | +41.5 ms (Magnum), +86.6 ms (Quill) |
| `db-query-layer.md` §3.3 | warm incremental, edit one leaf of 20 files | 0.36 s to 0.65 s |
| `db-query-layer.md` §3.4 | warm incremental, change a case class 20 handlers use | 0.38 s to **2.68 s (Quill)** |
| `capture-checking.md` §5.3 | warm incremental, one file of 80 | 0.51 s to 0.54 s |
| this document §4.4 | warm `sbt ~`, one field of 24 to 504 files | **0.15 s to 0.30 s** |

The spread between 0.15 s and 0.65 s across these is harness overhead, not
compiler variance: this document's numbers are wall-clocked inside a resident sbt
and exclude any launcher, `db-query-layer`'s include a scala-cli process, and
`capture-checking`'s include the same. **The compiler's own contribution to a
one-file warm edit on this hardware is on the order of 100 to 200 ms.**

---

## 9. Shortlist of configurations worth measuring in the reload spike

Ranked, cheapest first. Every entry carries the number that justifies its place.

**1. Resident `sbt ~` plus a framework-owned classloader swap, in-process.**
This is the cheapest thing that could work, and entry 3 is its refinement.
Cost to try: one afternoon. Justification: the compile term is already measured at
**148 to 300 ms** for eezo's single-file model edits (§4.4) and the reload term is measured
at **0.7 ms** to rebind Jetty on the same port (`http-server.md` §6). Predicted
total under 400 ms. The spike must measure the term nobody has measured: how long
eezo's own application initialisation takes inside a fresh loader, and whether
that loader is released across 20 consecutive reloads. Measure loader retention
with the `ClassLoaderSwap` rig already in
`research/harnesses/bench/http/`, which was written for exactly this and already
distinguishes Jetty from Helidon.

**2. The same, but restarting the whole JVM instead of swapping a loader.**
Cost to try: trivial, it is a subset of #1 minus the hard part. Justification:
**229 ms** wall clock from JVM start for Jetty (`http-server.md` §6), against a
3000 ms budget. If measurement #1's loader-retention result is anything other
than clean, this is the configuration eezo should ship, and it costs 229 ms.
Measuring it first also gives #1 a control.

**3. Resident sbt driven through the sbt server / BSP rather than `~`, with an
explicit debounce.** Cost to try: one day. Justification: this is the largest
measured saving available anywhere in this document. `~` costs a **double rebuild
on multi-file edits**: at 204 sources an editor-style rename across 51 files
compiles in 215 ms when driven directly but takes **774 to 1101 ms** under `~`
(§4.4), a 3.6x to 5x penalty, and scala-cli driving the same edit once takes
638 ms. A debounce that waits for the writes to stop before compiling recovers
that whole gap, and driving the compile explicitly also lets eezo share one warm
compiler with the user's Metals session instead of running two.

**4. `sbt ~` at 2000 files with 1600 model-dependent files, on the `derives`-clause
edit.** Cost to try: half a day, the generator already takes the parameters.
Justification: this is the only measured edit whose cost is O(project size), at
**1.6 ms per invalidated source over a ~100 ms floor** (§4.3). The extrapolation
to 1600 invalidated files is **2.7 s**, which is the entire budget, and an
extrapolation across a 4x range is not evidence. This measurement either kills
the concern or promotes it to a design constraint.

**5. Mill with `runBackground`, same edits.** Cost to try: one day including
installing Mill. Justification: Mill uses **Zinc 2.0.1** (§1.3) so invalidation
is identical, which means the *only* thing this measures is process and watcher
overhead against sbt's measured 148 to 300 ms. Worth doing because #16 has to
choose and because I could not measure Mill at all on this machine.

**6. sbt 2.0.4 with the same rig.** Cost to try: one day, plus porting the rig's
two custom commands to the Scala 3 build DSL. Justification: sbt 2.0.0 went GA
**2026-06-14** and 2.0.4 shipped **2026-07-26**, so by Scala Days it will be a
year old, and its default task caching is a CI asset. Risk to measure: the
`_sbt2_3` plugin republishing requirement (§3.1) may mean eezo's own plugin has to
be cross-published, and sbt-revolver does not exist there at all.

**7. JetBrains Runtime plus HotswapAgent with `-XX:+AllowEnhancedClassRedefinition`.**
Cost to try: two days, and it requires writing an eezo HotswapAgent plugin.
Justification: it is the only mechanism that can preserve live component state
across an edit that adds a field, because DCEVM permits "Add/remove/modify class
fields" (§5.2). Ranked last because it saves at most the **229 ms** restart term,
requires the user to change JDK, and has no Scala plugin to build on. Measure it
only if #10 shows that discarding LiveView component state on reload is
unacceptable in the demo.

**Explicitly not on the list, with reasons:** AOT cache and AppCDS (JEP 483's
"Class paths must contain only JAR files; directories in class paths are not
supported" makes them unusable in a dev loop, §6.2); CRaC and CRIU (Linux-only,
and a checkpoint predates the edit, §6.3); plain HotSwap without DCEVM (cannot
add a field, which is eezo's most common edit, §5.1); scala-cli as the shipped
build integration (no plugin system for the route generator, §3.3).

---

## 10. Recommendation

**Ship a resident sbt 1.12.x with eezo's own dev-server command, which watches
class files, compiles through a debounced call into the resident sbt, and swaps a
child classloader holding application classes over a parent holding Jetty, the
Scala library and eezo itself.** Do not use sbt-revolver, whose last release was
2023 and which has no sbt 2 artifact; implement the fork-and-swap directly, which
is roughly a hundred lines and gives eezo control over the debounce and the
browser push. Plan the migration to sbt 2.x as a follow-up rather than a
prerequisite, and ship the route-table generator as `Compile / sourceGenerators`
in an eezo sbt plugin.

**Confidence: 75%.**

That number is held down by three things and held up by one. Up: the compile
term, which is the term everyone worries about, is measured at 148 to 300 ms for
eezo's single-file model edits across three project sizes and three independent rigs on this
machine, and it is O(1) in project size for every edit except one. Down, in
order of seriousness:

**The weakest link is that the reload term has not been measured for eezo,
because eezo does not exist yet.** Every number in §9's first entry is a
*component* measurement: Jetty rebinds in 0.7 ms, Jetty's loader is released,
the compiler takes 200 ms. Nobody has measured eezo's own application
initialisation inside a fresh classloader, which is where `derives`-generated
instance construction, route-table registration, connection-pool re-acquisition
and template compilation all land. In Quarkus that term is large enough that its
authors wrote a four-second warning about it. If eezo's initialisation costs a
second, the recommendation still holds but the margin does not.

Second: the `derives`-clause edit is O(dependents) and extrapolates to **2.7 s at
1600 dependent files**, which is the whole budget from one term. I measured up to
504 files; a 4x extrapolation is a guess.

Third: Mill was not measured at all, so the build-tool half of this
recommendation rests on ergonomics and Zinc-sharing arguments rather than on a
head-to-head latency number.

---

## 11. What this means for the three-second budget

### 11.1 The worked budget for the leading configuration

Resident `sbt ~`, classloader swap, 204-source project, the common edit (add a
field to a `derives`-heavy model). Every term is measured except the two marked.

| Term | Cost | Source |
|---|---|---|
| File save to watcher trigger | included below | not separable with this instrument |
| ...to compile complete | **194 to 224 ms** | measured, §4.4, `sbt ~`, 204 sources, add a field |
| ...of which compilation proper | 106 ms | measured, §4.3, so the watcher and Zinc's scan are roughly 90 to 120 ms |
| Detect new class files, decide to reload | ~5 ms | Play does this by max `lastModified` over the classpath; **estimated** |
| Discard child classloader, construct new one | ~5 ms | **estimated**; loader construction is cheap, GC of the old one is asynchronous |
| Re-run eezo application initialisation in the new loader | **UNMEASURED** | this is the gap, §10 |
| Jetty rebind on the same port | **0.7 ms** | `http-server.md` §6, measured |
| Push reload over the existing WebSocket, browser re-render | **UNMEASURED** | localhost round trip plus DOM patch; #10 owns this |
| **Measured subtotal** | **~215 ms** | |

The same budget for the two harder edits at 204 sources, taking the measured
end-to-end `sbt ~` figures:

| Edit | save to compiled | measured subtotal |
|---|---|---|
| add a field | 194 to 224 ms | **~215 ms** |
| add a typeclass to `derives` | 419 to 456 ms | **~440 ms** |
| rename a field 50 files use (IDE refactor, double rebuild) | 774 to 1101 ms | **~800 ms to 1.1 s** |

At 504 sources the `derives`-clause compile alone is 766 ms, so that row would be
roughly **790 ms** of measured subtotal.

### 11.2 Does three seconds survive contact with a `derives`-heavy model?

**Yes, comfortably, for every edit except one, and that one has a stated
mitigation.**

The honest form of the answer has three parts.

**For field-level edits, which are the overwhelming majority of what eezo invites
a user to do, the compiler term is 97 to 129 ms and does not grow with project
size.** The three-second budget is not merely met, it is met with a factor of
twenty in hand. This was the thing most at risk from eezo's "derive everything
from the case class" thesis and the measurement clears it: because Zinc's API
model is signature-based and a derived given's *signature* does not change when a
field is added, adding a field recompiles one file whether the class derives one
typeclass or four, and whether four files depend on it or four hundred.

**For `derives`-clause edits, the budget survives at realistic project sizes and
is threatened at large ones.** 182 ms at 24 sources, 336 ms at 204, 766 ms at
504. The slope is about 1.6 ms per invalidated file. A project would need roughly
1800 model-dependent files before this term alone consumed three seconds. eezo
should not pretend that is impossible, and should mitigate it structurally: put
the model's `derives` clause in a file that as little as possible depends on
directly, keep the framework's generated route table free of `given` values
(§3.3), and consider whether eezo's own `derives` support can generate instances
into a *separate* file, which §4.3 measures at exactly 2 files invalidated rather
than all dependents.

**The unmeasured terms are where three seconds would actually die, and they are
not the compiler.** eezo's application initialisation inside a fresh classloader
is unbounded by anything measured here. A framework that scans the classpath at
startup, or builds a routing trie by reflection, or opens a connection pool
eagerly, can spend a second there without anybody noticing until the demo. The
three-second constraint should therefore be enforced as a **budget with named
line items** in eezo's dev server, printing each term, in the style of Quarkus's
`Live reload total time` but itemised. That is a cheap feature and it converts a
promise into a measurement.

---

## 12. Open items handed onward

| Item | Why it is open | Ticket that should own it |
|---|---|---|
| eezo's own initialisation cost inside a fresh classloader, and loader retention across 20 reloads | The single unmeasured term in §11.1, and the weakest link in §10 | [#10](https://github.com/rcardin/eezo/issues/10) |
| Browser round trip: WebSocket push to repainted DOM | Not measurable before eezo's client exists | [#10](https://github.com/rcardin/eezo/issues/10) |
| `derives`-clause edit at 2000 files | §4.3's slope extrapolates to 2.7 s and the extrapolation is 4x beyond what was measured | [#10](https://github.com/rcardin/eezo/issues/10), rig is ready in `zinc-lab/gen.sh` |
| Mill head-to-head, and sbt 2.0.4 head-to-head | Neither was measured; §9 entries 5 and 6 | [#16](https://github.com/rcardin/eezo/issues/16) |
| Whether eezo's sbt plugin cross-publishes to `_sbt2_3` | sbt 2 changed the plugin suffix; sbt-revolver has no sbt 2 artifact at all | [#16](https://github.com/rcardin/eezo/issues/16) |
| Route-table generator must not emit `given` values | §1.1's implicit-scope rule would make every route change invalidate every route consumer | [#16](https://github.com/rcardin/eezo/issues/16) |
| Whether eezo generates derived instances into a companion or a separate file | §4.3 measures the separate-file shape at 2 invalidated sources versus all dependents | [#16](https://github.com/rcardin/eezo/issues/16) |
| JDK 21 versus 25 for reload purposes | §6 finds the AOT cache (JEP 483/514/515) is a deploy-story argument only, and CRaC is inapplicable on both; the real 21-versus-25 argument remains JEP 491, already made in `http-server.md` §1.2 | [#35](https://github.com/rcardin/eezo/issues/35) |
| Ban on chained `transparent inline` in eezo's derivation code | [scala/scala3#25728](https://github.com/scala/scala3/issues/25728) is open and exponential | whichever ticket owns `derives` support |
| Itemised reload budget printed by the dev server | Converts the three-second promise into a measurement, §11.2 | [#16](https://github.com/rcardin/eezo/issues/16) |
| Derivation cost on *nested* models | §8.1's inductive-implicit shape is superlinear in depth and eezo has only measured flat case classes | [#10](https://github.com/rcardin/eezo/issues/10) |

---

## Appendix A: reproduction

### A.0 What was wrong with the salvaged rig

Three defects, all diagnosed from the preserved logs, all fixed:

1. **`C_rename_field` did not compile.** It renamed `User.name` without updating
   the six `Named*.scala` files that read `u.name`. Six `E008 Not Found` errors
   in `logs/2026-07-26-salvage/C_rename_field.log`. Fixed by making
   `edits.sh rename_used` rename the field *and* fix every consumer in the same
   edit, and by adding `rename_unused` as the control that isolates a pure API
   hash change.
2. **`D_separate_given` did not compile.** `OrderUse.scala` summoned
   `Codec[Order]` from a wildcard import that does not bring givens into scope.
   `E172` in `logs/2026-07-26-salvage/D_separate_given.log`. Fixed by adding
   `import model.given`, verified by the fact that the equivalent experiment
   (`order_add_field`) now runs green in every repetition.
3. **Every experiment was timed in a cold `sbt -batch` process.** §1.2 measures
   that overhead at 1.7 s, which is 10x the quantity being measured. Fixed by
   running the whole matrix inside one warm sbt session through two custom sbt
   commands.

A fourth defect was introduced and removed during this run: see §2.3. A fifth was
introduced and removed in the `derives-scale.sh` parser, which expected the
compiler profiler to emit `123ms` when it actually emits
`typer,run ns = 410150833,...`; the compiles were correct and only the parse was
wrong, so `reparse-derives.sh` re-derives the table from the logs already on
disk. `logs/derives-scale.tsv`'s profiler column is all zeroes and is kept only
so the wall-clock column in it can be cross-checked against
`logs/derives-scale-profiler.tsv`.

### A.1 Running the rig

```bash
cd research/harnesses/zinc-lab

# invalidation sets (debug log) + timing matrix at 24 and 204 sources,
# with derives=1 and derives=4 variants
./all.sh /tmp/eezo-zinc-lab

# 504-source point
./gen.sh /tmp/eezo-zinc-lab/big-derives4 200 200 100 4
./run.sh /tmp/eezo-zinc-lab/big-derives4 logs/big-derives4.log 3 false
./analyse.sh logs/big-derives4.log

# loop terms: cold sbt, sbt ~ watch latency, scala-cli
./all3.sh /tmp/eezo-zinc-lab

# compiler cost of a derives clause, by clause length and model count
./derives-scale.sh /tmp/dscale logs/derives-scale
```

`gen.sh <outdir> <n_typeclass_consumers> <n_member_consumers> <n_unrelated> <n_derives>`
takes `SCALA_VERSION` and `SBT_VERSION` from the environment, defaulting to 3.8.4
and 1.12.1. `analyse.sh` emits `label, sources, rounds, microseconds`.

### A.2 Raw logs

| Path | Contents |
|---|---|
| `logs/invalidation-debug.log` | debug-level run, the source of every "Zinc says" quote in §4 |
| `logs/small-derives4.{log,tsv}` | 24 sources, four typeclasses, 3 reps |
| `logs/small-derives1.{log,tsv}` | 24 sources, one typeclass, 3 reps |
| `logs/scale-derives4.{log,tsv}` | 204 sources, four typeclasses, 3 reps |
| `logs/scale-derives1.{log,tsv}` | 204 sources, one typeclass, 3 reps |
| `logs/big-derives4.{log,tsv}` | 504 sources, four typeclasses, 3 reps |
| `logs/loop-small.tsv`, `logs/loop-small/` | cold sbt, `sbt ~` latency, scala-cli, 24 sources |
| `logs/loop-scale.tsv`, `logs/loop-scale/` | the same at 204 sources |
| `logs/loop-small-v1-suspect.tsv`, `logs/loop-scale-v1-suspect.tsv` | the discarded first watch measurement, §2.3, **not quoted** |
| `logs/derives-scale/` | raw `-Yprofile-enabled` output, 24 compiles |
| `logs/derives-scale.tsv` | first parse, **profiler column is 0.0 and must not be used**, see below |
| `logs/derives-scale-profiler.tsv` | the correct parse, produced by `reparse-derives.sh`, quoted in §4.5 |
| `logs/2026-07-26-salvage/` | the previous run's logs, **not quoted** |

### A.3 Reading `analyse.sh` output

`sources` is summed across Zinc's internal rounds, because Zinc compiles the
directly changed files first, recomputes the API, and then compiles what that
invalidated. Where the split matters it is given in §4.3. For `add_derive` at 504
sources the rounds are literally `compiling 1 Scala source` followed by
`compiling 400 Scala sources`, which is the two-phase structure §1.1 predicts:
one file changes, its implicit-scope name hash changes, and every member-reference
dependent falls.

---

## Appendix B: sources

**Zinc and incremental compilation**
- [`MemberRefInvalidator.scala`](https://github.com/sbt/zinc/blob/develop/internal/zinc-core/src/main/scala/sbt/internal/inc/MemberRefInvalidator.scala): the `UseScope.Implicit` branch
- [`NameHashing.scala`](https://github.com/sbt/zinc/blob/develop/internal/zinc-apiinfo/src/main/scala/xsbt/api/NameHashing.scala): implicit/regular partition
- [`IncrementalNameHashing.scala`](https://github.com/sbt/zinc/blob/develop/internal/zinc-core/src/main/scala/sbt/internal/inc/IncrementalNameHashing.scala): macro-expansion invalidation
- [Understanding Incremental Recompilation](https://www.scala-sbt.org/1.x/docs/Understanding-Recompilation.html): name hashing, member reference versus inheritance
- [`ExtractAPI.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/sbt/ExtractAPI.scala): inline body hashing, `GivenOrImplicit` modifier
- [`ExtractDependencies.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/sbt/ExtractDependencies.scala): dependency contexts, `DependencyByMacroExpansion`

**Build tools**
- [sbt releases](https://github.com/sbt/sbt/releases), [Zinc releases](https://github.com/sbt/zinc/releases)
- [sbt 2.0 change summary](https://www.scala-sbt.org/2.x/docs/en/changes/sbt-2.0-change-summary.html)
- [Bloop releases](https://github.com/scalacenter/bloop/releases) and [commits](https://github.com/scalacenter/bloop/commits)
- [Mill `Deps.scala`](https://github.com/com-lihaoyi/mill/blob/main/mill-build/src/millbuild/Deps.scala), [Mill flags](https://mill-build.org/mill/cli/flags.html), [Mill design principles](https://mill-build.org/mill/depth/design-principles.html)
- [sbt-revolver](https://github.com/spray/sbt-revolver) and its [Maven Central coordinates](https://repo1.maven.org/maven2/io/spray/sbt-revolver_2.12_1.0/)
- [scala-cli compile docs](https://scala-cli.virtuslab.org/docs/commands/compile)

**Class reloading**
- [JVM TI specification, `RedefineClasses`](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#RedefineClasses)
- [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) and its [releases](https://github.com/JetBrains/JetBrainsRuntime/releases)
- [HotswapAgent](https://github.com/HotswapProjects/HotswapAgent), [hotswapagent.org](http://hotswapagent.org/)

**JVM start**
- [JEP 483: Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483)
- [JEP 514: Ahead-of-Time Command-Line Ergonomics](https://openjdk.org/jeps/514)
- [JEP 515: Ahead-of-Time Method Profiling](https://openjdk.org/jeps/515)
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [CRaC documentation](https://github.com/CRaC/docs), [CRaC OpenJDK builds](https://github.com/CRaC/openjdk-builds/releases)

**Comparable frameworks**
- [Play `BuildLink`](https://github.com/playframework/playframework/blob/main/dev-mode/play-build-link/src/main/java/play/core/BuildLink.java), [`DevServerReloader`](https://github.com/playframework/playframework/blob/main/dev-mode/play-run-support/src/main/java/play/runsupport/DevServerReloader.java), [`DelegatingClassLoader`](https://github.com/playframework/playframework/blob/main/dev-mode/play-run-support/src/main/java/play/runsupport/classloader/DelegatingClassLoader.java)
- [Quarkus `RuntimeUpdatesProcessor`](https://github.com/quarkusio/quarkus/blob/main/core/deployment/src/main/java/io/quarkus/deployment/dev/RuntimeUpdatesProcessor.java), [Quarkus class-loading reference](https://quarkus.io/guides/class-loading-reference), [Quarkus dev mode differences](https://quarkus.io/guides/dev-mode-differences)
- [Spring Boot DevTools](https://docs.spring.io/spring-boot/reference/using/devtools.html)
- [`Phoenix.CodeReloader`](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/code_reloader.ex)

**Scala 3 compile speed**
- [scala3 `bench/profiles`](https://github.com/scala/scala3/tree/main/bench/profiles), [`inductive-implicits.scala`](https://github.com/scala/scala3/blob/main/tests/bench/inductive-implicits.scala)
- [scala/scala3#25728](https://github.com/scala/scala3/issues/25728): exponential PostTyper with chained transparent inline
- [scala/scala3#25930](https://github.com/scala/scala3/issues/25930): heavy slowdown from a missing type annotation
- [scala/scala3#25975](https://github.com/scala/scala3/issues/25975): opaque-type exponential under capture checking

**Prior eezo research reused**
- `research/http-server.md` §1.4 (classloader retention), §6 (boot and rebind)
- `research/db-query-layer.md` §3 (compile scaling for Magnum, Quill, ScalaSql)
- `research/capture-checking.md` §5 (warm incremental baseline, profiler convention)
