# Research: why `fileInputs` on a source generator does not reach sbt's `~`, and what does

Follow up to [`research/sbt-plugin.md`](sbt-plugin.md) §1.3, which measured the
negative result and inferred, without verification, why it happens. This document
verifies the mechanism against sbt's source and re run experiments, and it
**refutes the inferred cause while confirming the measured fact**.

Date of investigation: 2026-08-28.

**Empirical basis.** Every behavioural claim below is either (a) read off sbt's
source at a named branch, file and line range, (b) quoted from sbt's reference
manual or an sbt issue or PR, or (c) measured on this machine. Claims are tagged
**verified**, **measured** or **inferred**, and nothing is left ambiguous.

Source read: `sbt/sbt` branch `1.12.x` at commit `521c6969` (2026-08-24) and
branch `2.0.x` at commit `3127e8d6` (2026-08-27), both shallow cloned locally so
the line numbers below are exact for those commits.

Toolchain for the experiments: **sbt 1.12.14** and **sbt 2.0.8** (launcher 1.12.1
from SDKMAN), Scala **2.13.18** (sbt 1 lab) and **3.8.4** (sbt 2 lab), Temurin
**JDK 26**, Apple M4 Pro, macOS 15.7.4. Same machine as `research/sbt-plugin.md`.
Reproduction is in Appendix A.

---

## 1. The verdict up front

§1.3 said:

> Declaring the generator's input with `fileInputs` on the generator's own task
> key is not enough, because `sourceGenerators` is a *setting* holding anonymous
> `Task` values and sbt's watch computes its monitored set from the task graph it
> can see.

The measured fact is right. The stated cause is wrong on its first half and
imprecise on its second.

1. **Anonymity is not the cause.** A generator registered through a *named* task
   key with `fileInputs` declared on it still does not trigger (**measured**,
   §6, row A). An *anonymous* `Def.task { ... }.taskValue` generator **does**
   trigger (**measured**, §6, row C). The two rows differ in one thing only, and
   it is not anonymity.
2. **It is the settings graph, not the task graph.** The walk runs over
   `BuildStructure.compiledMap`, the static settings dependency graph built at
   load time, not over the runtime task graph (**verified**, §2).
3. **The real cause is that nothing depends on `<key> / fileInputs`.** sbt only
   collects a `fileInputs` setting when its scoped key appears as an *edge* in
   that graph. The edge is created by the task body actually consuming its own
   inputs, through `foo.inputFiles`, `foo.inputFileChanges`, or an explicit
   `(foo / allInputFiles).value` / `(foo / inputFileStamps).value`. Declaring
   `fileInputs` and never reading it creates no edge, so the glob is never
   registered with the file monitor (**verified** in source, §3; **measured**,
   §6).
4. **The named task key variant works, but only with the consumption.** This is
   the answer to the question that matters for eezo's wiring: see §7.

sbt's own source states the contract in a code comment, and sbt's reference
manual states it in prose. Both are quoted in §3 and §5.

---

## 2. How sbt computes the watched set for `~<task>`

**Verified.** The chain, in order.

**Entry point.** `Continuous.getConfig` is called once per watched key when a
continuous build starts
([`main/src/main/scala/sbt/internal/Continuous.scala`, 1.12.x, L172-197](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L172-L197)):

```scala
    val inputs = {
      val configs = scopedKey.get(internalDependencyConfigurations).getOrElse(Nil)
      import WatchTransitiveDependencies.{ Arguments => DArguments }
      val args = new DArguments(scopedKey, extracted, compiledMap, logger, configs, state)
      WatchTransitiveDependencies.transitiveDynamicInputs(args)
    }

    val repository = getRepository(state)
    dynamicInputs ++= inputs
    logger.debug(s"[watch] [${scopedKey.show}] Found inputs: ${inputs.map(_.glob).mkString(",")}")
    inputs.foreach(i => repository.register(i.glob).foreach(_.close()))
```

So **the function that assembles the monitored globs is
`sbt.internal.WatchTransitiveDependencies.transitiveDynamicInputs`**, and the
globs it returns are what gets registered with the `FileTreeRepository` that the
watch listens to. The scoped key referenced in the docstring of `getConfig`
describes it as walking "the dependency graph for the key" and extracting "all of
the transitive globs specified by the inputs and triggers keys"
([same file, L158-163](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L158-L163)).

**The monitored set is a mutable cell shared by the whole watch session.**
`Continuous.Config.inputs()` is `dynamicInputs.toSeq.sorted`
([L978-985](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L978-L985)),
and `dynamicInputs` is a single `mutable.Set[DynamicInput]` created once when the
watch starts ([L1234](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L1234))
and stashed into `State` under `Continuous.DynamicInputs`
([L1255](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L1255)).
That matters: there is a **second, runtime** path that adds to it, described in
§4.

**The graph that is walked.** `transitiveDynamicInputs`
([`WatchTransitiveDependencies.scala`, 1.12.x, L94-131](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L94-L131))
seeds `collectKeys` with the delegates of the watched key, then walks
`arguments.compiledMap`
([L168-227](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L168-L227)).
`compiledMap` is `BuildStructure.compiledMap`
([`main/src/main/scala/sbt/internal/BuildStructure.scala`, L35](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/BuildStructure.scala#L35)),
a `Map[ScopedKey[_], Def.Compiled[_]]` populated during build load
([`Load.scala`, L361](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Load.scala#L361)),
where

```scala
  final class Compiled[T](
      val key: ScopedKey[T],
      val dependencies: Iterable[ScopedKey[_]],
      val settings: Seq[Setting[T]]
  )
```

([`internal/util-collection/src/main/scala/sbt/internal/util/Settings.scala`, L424-430](https://github.com/sbt/sbt/blob/1.12.x/internal/util-collection/src/main/scala/sbt/internal/util/Settings.scala#L424-L430)).

`Compiled.dependencies` are the scoped keys that a setting's `Initialize`
references. This is a **static, settings level** graph. It is not "the task graph
sbt can see" at runtime: it exists before any task runs, and it includes settings
that are never evaluated.

**Answer to item 1 of the brief:** the key/function that assembles the monitored
globs is `WatchTransitiveDependencies.transitiveDynamicInputs`, driven from
`Continuous.getConfig`, with the result accumulated in the session's
`dynamicInputs` set and registered with the `FileTreeRepository`.

**Answer to item 2:** neither of the two options as posed. It is a transitive walk
over the **settings** dependency graph (`compiledMap`), so a `fileInputs` key must
be a reachable *settings* dependency to contribute; and there is *additionally* a
`dynamicInputs` cell that is topped up when a task is actually evaluated (§4).
Both paths require the same edge.

---

## 3. Where `fileInputs` enters, and the single edge that lets it

**Verified.** `collectKeys` accumulates only keys for which `isGlobKey` holds:

```scala
  private[this] def isGlobKey(key: ScopedKey[_]): Boolean = key.key match {
    case fileInputs.key | watchTriggers.key => true
    case _                                  => false
  }
```

([`WatchTransitiveDependencies.scala`, 1.12.x, L228-231](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L228-L231))

and it only ever sees a key if that key is either the node being visited or one of
`compiled.dependencies ++ compiled.settings.map(_.key)`
([L182-214](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L182-L214)).
There is no scan of "all `fileInputs` settings in the build". A `fileInputs`
scoped key contributes if and only if some reached node depends on it.

sbt says this itself, in a comment inside `transitiveDynamicInputs`, present
verbatim on both branches
([1.12.x L99-101](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L99-L101),
[2.0.x L107-109](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L107-L109)):

```scala
    // We add the triggers to the delegate scopes to make it possible for the user to do something
    // like: Compile / compile / watchTriggers += baseDirectory.value ** "*.proto". We do not do the
    // same for inputs because inputs are expected to be explicitly used as part of the task.
```

"Inputs are expected to be explicitly used as part of the task" is the whole
contract, in sbt's own words.

**What creates the edge.** When any `fileInputs` setting is defined,
`sbt.nio.Settings.inject` intercepts it and injects a family of task definitions
into the same scope
([`main/src/main/scala/sbt/nio/Settings.scala`, 1.12.x, L42-45](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Settings.scala#L42-L45)):

```scala
      val inject =
        if (s.key.key == fileInputs.key) inputPathSettings(s)
        else maybeAddOutputsAndFileStamps(s, fileOutputScopes)
```

`inputPathSettings` defines `<scope> / allInputPathsAndAttributes`,
`<scope> / inputFileStamps`, `<scope> / allInputFiles` and
`<scope> / changedInputFiles`, and it is
`allInputPathsAndAttributes` that reads `fileInputs`
([L146-163](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Settings.scala#L146-L163)).
So the graph contains

```
<scope> / allInputFiles  ->  <scope> / allInputPathsAndAttributes  ->  <scope> / fileInputs
```

and nothing else points at any of them. If the user's task never references
`<scope> / allInputFiles` (or `inputFileStamps`, or `changedInputFiles`), the
whole subtree is an orphan island in the settings graph, unreachable from
`Compile / compile`, and `collectKeys` never lands on it.

**The convenience syntax is exactly that edge.** `foo.inputFiles` is a macro that
expands to `rescope(fooScope, allInputFiles).value`
([`main/src/main/scala/sbt/internal/FileChangesMacro.scala`, 1.12.x, L83-86](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/FileChangesMacro.scala#L83-L86)),
and `foo.inputFileChanges` expands to `.value` references on
`changedInputFiles`, `allInputFiles` and `inputFileStamps`
([L46-52, L66-82](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/FileChangesMacro.scala#L46-L82)).
They are sugar for creating the dependency edge.

**sbt's own tasks follow the contract.** `Compile / unmanagedSources` declares its
globs and then *consumes* them
([`Defaults.scala`, 1.12.x, L607-620](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala#L607-L620)):

```scala
    unmanagedSources / fileInputs := { ... },
    unmanagedSources := (unmanagedSources / inputFileStamps).value.map(_._1.toFile),
```

That second line is why `~compile` watches `src/main/scala` at all. Without it,
editing a `.scala` file would not trigger either.

**The reference manual says the same thing.** From
[Track file inputs and outputs](https://www.scala-sbt.org/1.x/docs/Howto-Track-File-Inputs-and-Outputs.html),
quoted verbatim:

> In a continuous build, `~bar`, for an arbitrary task, `bar`, given some task,
> `foo`, any calls to `foo.inputFiles` and `foo.inputFileChanges` within `bar`
> will cause all of the globs specified by `foo / fileInputs` to be monitored in a
> continuous build.

and

> Note that calling `buildObjects.inputFileChanges` also causes
> `buildObjects / fileInputs` to automatically be watched in a continuous build.

The documented trigger is the *call*, not the declaration. **This is item 6 of the
brief:** the reference manual is the doc that states the intended contract, and
[sbt/sbt#4512](https://github.com/sbt/sbt/pull/4512), the watch rewrite that
introduced these keys, is the PR that introduced the settings graph traversal.

---

## 4. The second, runtime path, and why it does not rescue an unused `fileInputs`

**Verified.** `allInputPathsAndAttributes` does two side effecting things when it
runs ([`nio/Settings.scala`, 1.12.x, L149-162](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Settings.scala#L149-L162)):

```scala
      // This makes watch work by ensuring that the input glob is registered with the
      // repository used by the watch process.
      state.value.get(globalFileTreeRepository).foreach { repo =>
        inputs.foreach(repo.register(_).foreach(_.close()))
      }
      dynamicInputs.foreach(_ ++= inputs.map(g => DynamicInput(g, stamper, forceTrigger)))
```

So the monitored set is the union of the static walk's result and everything
registered by tasks that actually evaluated their `allInputPathsAndAttributes`
during a build. This is what the `~foo` docstring for `Continuous.dynamicInputs`
calls "the input globs found during task evaluation that are used in watch"
([`Continuous.scala`, L128-130](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala#L128-L130)).

It does not rescue an unused `fileInputs`, because it is triggered by the same
thing: evaluation of `allInputPathsAndAttributes`, which only happens if something
depends on it. A declared but unconsumed `fileInputs` is invisible to both paths.

---

## 5. Why `watchTriggers` behaves differently

**Verified.** `watchTriggers` is collected by two mechanisms that have no
`fileInputs` counterpart.

**(a) The watched key's own task scope is seeded directly**
([`WatchTransitiveDependencies.scala`, 1.12.x, L96-104](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L96-L104)):

```scala
    val taskScope = Project.fillTaskAxis(scopedKey).scope
    ...
    val allKeys: Seq[ScopedKey[_]] =
      (delegates(scopedKey).toSet ++ delegates(ScopedKey(taskScope, watchTriggers.key))).toSeq
```

For `~compile` that puts `Compile / compile / watchTriggers` (and its delegates)
into the walk before anything else happens. This is precisely the case the
adjacent comment describes.

**(b) Every node visited contributes its own `watchTriggers`**
([L215-221](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L215-L221)):

```scala
          // Append the Keys.triggers key in case there are no other references to Keys.triggers.
          val transitiveTrigger = compiled.key.scope.task.toOption match {
            case _: Some[_] => ScopedKey(compiled.key.scope, watchTriggers.key)
            case None       => ScopedKey(Project.fillTaskAxis(compiled.key).scope, watchTriggers.key)
          }
```

So for every key reached in the walk, `<that key> / watchTriggers` is looked up
unconditionally. No dependency edge is required, which is the exact opposite of
the `fileInputs` rule.

`watchTriggers` globs are also stamped differently: they are always
`FileStamper.LastModified` with `forceTrigger = true`, while `fileInputs` globs
use the scope's `inputFileStamper`, which defaults to `Hash`
([L105-122](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L105-L122)).
Practical consequence: a `touch` with no content change triggers a `watchTriggers`
glob but not a `fileInputs` glob. (**Verified** in source; the experiments below
changed content, not just mtime, for this reason.)

The reference manual describes `watchTriggers` as being for exactly the case
where the edge does not exist, quoted verbatim from
[Triggered Execution](https://www.scala-sbt.org/1.x/docs/Triggered-Execution.html):

> `watchTriggers: Seq[Glob]` adds search queries for files that should task
> trigger evaluation but that the task does not directly depend on.

**This is item 5 of the brief.** `Compile / compile / watchTriggers` works because
`Compile / compile` is the watched key, so mechanism (a) picks it up with no graph
reachability requirement at all.

---

## 6. What `.taskValue` does, and the experiment that settles it

### 6.1 `.taskValue` is a settings level `.value`

**Verified.** `taskValue` is a macro whose implementation is
`InputWrapper.wrapInit[Task[T]]`
([`main-settings/src/main/scala/sbt/std/InputWrapper.scala`, 1.12.x, L161-168](https://github.com/sbt/sbt/blob/1.12.x/main-settings/src/main/scala/sbt/std/InputWrapper.scala#L161-L168),
declared at [L191-196](https://github.com/sbt/sbt/blob/1.12.x/main-settings/src/main/scala/sbt/std/InputWrapper.scala#L191-L196)).
It unwraps an `Initialize[Task[T]]` to a `Task[T]` **without joining the task**,
so the enclosing setting depends on the inner `Initialize` at the settings level
but does not run it. `sourceGenerators += <Initialize[Task[A]]>` inserts the call
automatically ([`TaskMacro.scala`, L269-295](https://github.com/sbt/sbt/blob/1.12.x/main-settings/src/main/scala/sbt/std/TaskMacro.scala#L269-L295)):

```scala
      // To allow Initialize[Task[A]] in the position of += RHS, we're going to call "taskValue" automatically.
```

Consequences that matter here:

- `(Compile / gen).taskValue` records `ScopedKey(Compile, gen)` as a dependency of
  `Compile / sourceGenerators`, so `Compile / gen` **is** reachable in the walk.
  (**Verified** in source; **measured** in §6.2 row E, where
  `Compile / gen / watchTriggers` on that same key does fire.)
- `Def.task { ... }.taskValue` records the *body's* `.value` references as
  dependencies of `Compile / sourceGenerators`. The task itself has no scoped key,
  so there is nothing to hang a `fileInputs` scope on and nothing named to reach,
  but the body's edges are all present.
- **`.taskValue` does not "erase a scoped key" in any sense relevant to the
  watch.** For an anonymous `Def.task` there was never a key to erase; for a
  `TaskKey` the key is exactly what gets recorded as the dependency. The runtime
  `taskDefinitionKey` that `Previous` and `streams` rely on is set by
  `Settings.addTaskDefinition`
  ([`nio/Settings.scala`, L134-135](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Settings.scala#L134-L135))
  and is a runtime concern, not a watch concern.

### 6.2 The experiment

**Measured**, sbt 1.12.14, Scala 2.13.18, one live `~compile` session per run,
one content changing edit at a time with a 15 second settle. Full sources and
driver in Appendix A.

| # | generator wiring | watch declaration | edit | build triggered? |
|---|---|---|---|---|
| A | named `taskKey` `genA`, registered via `(Compile / genA).taskValue`, body does **not** read its inputs | `Compile / genA / fileInputs` | `confA/in.txt` | **no** |
| B | named `taskKey` `genB`, same registration, body reads `(Compile / genB / allInputFiles).value` | `Compile / genB / fileInputs` | `confB/in.txt` | **yes** |
| C | **anonymous** `Def.task { ... }.taskValue`, body reads `(Compile / genC / allInputFiles).value` where `genC` is a key used only as a scope holder | `Compile / genC / fileInputs` | `confC/in.txt` | **yes** |
| D | none, watched task itself | `Compile / compile / watchTriggers` | `confD/in.txt` | **yes** |
| E | same `genA` as row A, body still does not read its inputs | `Compile / genA / watchTriggers` | `confA/in.txt` | **yes** |
| F | named `taskKey` `genE`, body reads `(Compile / genE / allInputFiles).value`, **not** registered in `sourceGenerators` | `Compile / genE / fileInputs` | `confE/in.txt` | **no** |
| - | baseline | none | `src/main/scala/Main.scala` | **yes** |

Rows A, B, C, D are one run; rows E and F are a second run in a second project.

Read the rows against each other:

- **A vs C**: named key fails, anonymous task succeeds. Anonymity is not the
  cause. This refutes the first half of §1.3's inference.
- **A vs B**: same shape, same named key, same `fileInputs` declaration. The only
  difference is whether the body reads `allInputFiles`. That is the cause.
- **A vs E**: the *same* key with the *same* glob triggers when the glob is
  declared as `watchTriggers` and does not when it is declared as `fileInputs`.
  This proves `Compile / genA` is reached by the walk (mechanism (b) of §5 only
  fires for reached nodes), so the failure is not reachability of the generator.
- **F**: a task with the consumption edge but no path from `compile` does not
  trigger. Reachability is still necessary; it is just not sufficient, and it was
  never the missing ingredient in the original experiment.

At debug log level, the `confA` edit in row A produces **no log line at all**, not
even `Received event for path`, whereas `confB` and `confC` edits produce the full
`Accepted event ... -> Trigger` sequence. The `confA` glob was never registered
with the file tree repository. (**Measured**; log preserved in Appendix A.)

---

## 7. Item 4: does the named task key variant change eezo's wiring?

**Yes, but not for the reason the brief hypothesised.** The change is not
"name the key". It is "**consume the inputs**". Concretely, this works
(**measured**, row B):

```scala
val generateRoutes = taskKey[Seq[File]]("generate the eezo route table")

Compile / generateRoutes / fileInputs += baseDirectory.value.toGlob / "conf" / "routes",
Compile / generateRoutes := {
  val inputs = (Compile / generateRoutes / allInputFiles).value   // <- the load bearing line
  ...
},
Compile / sourceGenerators += (Compile / generateRoutes).taskValue,
```

and so does the anonymous form, provided some key exists to scope `fileInputs` on
and the body reads it (**measured**, row C). Dropping the `allInputFiles` line
from either form silently reverts to the row A behaviour: no trigger, no error,
stale generated file. `foo.inputFiles` and `foo.inputFileChanges` are the
idiomatic spellings of that line.

**Why this is worth changing.** `research/sbt-plugin.md` §3.4 currently recommends
one anonymous generator plus a `Compile / compile / watchTriggers` entry for any
non `.scala` input. That still works (row D). But the `fileInputs` route is
strictly better for eezo on three counts, all **verified** in source:

1. `fileInputs` globs are hash stamped by default, `watchTriggers` globs are
   mtime stamped with `forceTrigger = true` (§5). A `git checkout` that rewrites
   mtimes without changing content re triggers a `watchTriggers` build and does
   not re trigger a `fileInputs` build. That is the same argument that already
   made `FileInfo.hash` the right choice for `FileFunction.cached` in §3.4, and it
   points the same way here.
2. The declaration lives next to the generator that owns it, in the generator's
   own scope, instead of reaching into `Compile / compile`'s scope from a plugin.
   A plugin writing into `Compile / compile / watchTriggers` is writing into the
   user's scope for a task it does not own.
3. The same `fileInputs` declaration also feeds `inputFileChanges`, which is the
   supported way to do incremental generation, so the generator can eventually
   drop its hand rolled `FileFunction.cached` layer if sbt's own change tracking
   proves sufficient. (**Inferred**; not measured here.)

The counter argument for keeping `watchTriggers` is that it is one line and has no
"and you must remember to read `allInputFiles`" footgun. If eezo keeps it, the
scripted test §3.4 already asks for should assert the *trigger*, not the presence
of the setting.

---

## 8. sbt 2.0.x

**Verified.** The mechanism is structurally identical on `2.0.x`:
`transitiveDynamicInputs` walks `compiledMap`, `isGlobKey` matches the same two
keys, the "inputs are expected to be explicitly used as part of the task" comment
is unchanged, the per node `watchTriggers` append is unchanged
([`WatchTransitiveDependencies.scala`, 2.0.x, L102-146, L225-226, L237](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L102-L146)),
`inputPathSettings` still registers globs with the repository and the
`dynamicInputs` cell at evaluation time
([`nio/Settings.scala`, 2.0.x, L154-171](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/nio/Settings.scala#L154-L171)),
and `unmanagedSources` still consumes its own `inputFileStamps`
([`Defaults.scala`, 2.0.x, L601-614](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/Defaults.scala#L601-L614)).

**One behavioural difference exists and it looks dangerous on paper.** Commit
`0c6b4c97` (2026-01-14), "[2.x] Fix watchTriggers to control what triggers instead
of adding to fileInputs", [sbt/sbt#8525](https://github.com/sbt/sbt/pull/8525),
fixing [sbt/sbt#7130](https://github.com/sbt/sbt/issues/7130), adds this to
`transitiveDynamicInputs`
([2.0.x L138-145](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L138-L145)):

```scala
    // If watchTriggers is explicitly set (non-empty), use only watchTriggers instead of combining with fileInputs
    // This allows users to control what triggers the watch by setting watchTriggers
    val result = if (triggerGlobs.nonEmpty) {
      triggerGlobs ++ legacy(keys :+ scopedKey, args)
    } else {
      inputGlobs ++ triggerGlobs ++ legacy(keys :+ scopedKey, args)
    }
```

Read literally, setting any reachable `watchTriggers` on sbt 2 discards **every**
`fileInputs` glob from the static walk, including
`Compile / unmanagedSources / fileInputs`, and `watchSources` is `:== Nil` by
default on both branches
([1.12.x `Defaults.scala` L330](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala#L330),
[2.0.x L339](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/Defaults.scala#L339)),
so `legacy` contributes nothing to compensate.

**Measured**, sbt 2.0.8 (released 2026-08-28, so it contains the January commit),
Scala 3.8.4, one live `~compile` session, with `Compile / compile / watchTriggers`
set **and** a `genB` style generator declaring and consuming `fileInputs`:

| edit | build triggered? |
|---|---|
| `confB/in.txt` (a `fileInputs` glob) | **yes** |
| `confD/in.txt` (the `watchTriggers` glob) | **yes** |
| `src/main/scala/Main.scala` | **yes** |

So the suppression does **not** manifest in practice. **Inferred** explanation:
the runtime path of §4 puts the `fileInputs` globs back. Both
`Compile / unmanagedSources / allInputPathsAndAttributes` and
`Compile / genB / allInputPathsAndAttributes` are evaluated during the first
build of the watch session, and each appends its globs to the same session
`dynamicInputs` set that `getConfig` seeded. I did not construct a case that
isolates the static walk from the runtime path, so this explanation is reasoned
from the source rather than measured directly.

**What this means for eezo.** Nothing breaks today on either sbt 1.12.x or sbt
2.0.8. But the sbt 2 code path deliberately makes `watchTriggers` *exclusive*
rather than *additive*, and the only thing currently masking that is a runtime
side effect. That is a further reason to prefer the `fileInputs` plus
`allInputFiles` wiring of §7 over `Compile / compile / watchTriggers`: on sbt 2,
`watchTriggers` is documented by its own fix commit as a way to *narrow* the
watch, which is not what eezo wants it for.

---

## 9. What was verified, what was measured, what remains inferred

**Verified in sbt source (branch, file, line range given inline):**

- The monitored set comes from `WatchTransitiveDependencies.transitiveDynamicInputs`, driven by `Continuous.getConfig`.
- The walk is over `BuildStructure.compiledMap`, the static settings graph.
- `fileInputs` contributes only when its scoped key is a graph edge; sbt's own comment states this is deliberate.
- `watchTriggers` is seeded from the watched key's task scope and auto appended for every visited node, requiring no edge.
- `fileInputs` globs are hash stamped, `watchTriggers` globs are mtime stamped with `forceTrigger = true`.
- `taskValue` is `wrapInit[Task[T]]`, a settings level `.value`; `+=` inserts it automatically.
- `foo.inputFiles` / `foo.inputFileChanges` expand to `.value` references on the injected `allInputFiles` / `changedInputFiles` / `inputFileStamps` keys.
- sbt's own `unmanagedSources` follows the declare then consume pattern.
- The sbt 2.0.x `watchTriggers` exclusivity change and its PR and issue numbers.

**Measured on this machine:**

- Rows A through F of §6.2 and the sbt 2.0.8 table of §8.
- The absence of any file event for the row A glob at debug log level.

**Inferred, not verified:**

- That the runtime `dynamicInputs` path is what masks the sbt 2.0.x
  `watchTriggers` exclusivity (§8). Consistent with the source and with the
  measurement, but not isolated by an experiment.
- That `inputFileChanges` could eventually replace eezo's `FileFunction.cached`
  layer (§7, point 3). Not tried.
- Behaviour of `fileInputs` under a multi project build with `dependsOn`. Not
  investigated; the `transitiveClasspathDependency` branch of `collectKeys`
  ([1.12.x L200-211](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala#L200-L211))
  exists for it and was read but not exercised.

---

## VERDICT

§1.3's *measurement* stands and should be kept: declaring `fileInputs` on a source
generator's key does not make `~compile` trigger, and `Compile / compile /
watchTriggers` does. §1.3's *inferred mechanism* is wrong and should be replaced.
Anonymity has nothing to do with it, proved both ways: a named task key with an
unconsumed `fileInputs` does not trigger, and an anonymous `Def.task { ...
}.taskValue` whose body reads `allInputFiles` does. The graph sbt walks is the
static settings graph `BuildStructure.compiledMap`, not the runtime task graph,
and a `fileInputs` key contributes only when it is a dependency edge in that
graph, which happens only when the task body actually consumes its inputs through
`allInputFiles`, `inputFileStamps`, `changedInputFiles`, or the `foo.inputFiles` /
`foo.inputFileChanges` sugar; sbt's own source comment says so ("inputs are
expected to be explicitly used as part of the task") and the reference manual says
so in prose. `watchTriggers` differs because it is seeded from the watched key's
own task scope and auto appended for every node the walk visits, so it needs no
edge at all. On item 4 specifically: yes, this changes eezo's recommended wiring,
but the fix is not "use a named task key", it is "**read your own inputs**". The
recommended shape becomes a generator (named key preferred, for legibility and for
the `fileInputs` scope) that declares `<key> / fileInputs` and opens its body with
`(<key> / allInputFiles).value`, which gets hash based triggering, keeps the
declaration in the generator's own scope instead of reaching into `Compile /
compile`, and avoids the sbt 2.0.x change that makes `watchTriggers` exclusive
rather than additive. `research/sbt-plugin.md` §1.3, §3.4 and the confidence entry
at the end of that document should be amended accordingly.

---

## Appendix A: reproduction

All three lab projects were built under the session scratchpad, not in the eezo
repo.

### A.1 sbt 1.12.14, rows A to D (`watchlab`)

`project/build.properties` is `sbt.version=1.12.14`. `build.sbt`:

```scala
import sbt.nio.Keys._

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / watchLogLevel := Level.Debug

val genA = taskKey[Seq[File]]("A")
val genB = taskKey[Seq[File]]("B")
val genC = taskKey[Seq[File]]("C (scope holder only)")

lazy val root = (project in file("."))
  .settings(
    Compile / genA / fileInputs += baseDirectory.value.toGlob / "confA" / "*.txt",
    Compile / genA := {
      val out = (Compile / sourceManaged).value / "A.scala"
      IO.write(out, "object A { val v = 1 }\n"); Seq(out)
    },
    Compile / sourceGenerators += (Compile / genA).taskValue,

    Compile / genB / fileInputs += baseDirectory.value.toGlob / "confB" / "*.txt",
    Compile / genB := {
      val ins = (Compile / genB / allInputFiles).value
      val out = (Compile / sourceManaged).value / "B.scala"
      IO.write(out, s"object B { val v = ${ins.size} }\n"); Seq(out)
    },
    Compile / sourceGenerators += (Compile / genB).taskValue,

    Compile / genC / fileInputs += baseDirectory.value.toGlob / "confC" / "*.txt",
    Compile / sourceGenerators += Def.task {
      val ins = (Compile / genC / allInputFiles).value
      val out = (Compile / sourceManaged).value / "C.scala"
      IO.write(out, s"object C { val v = ${ins.size} }\n"); Seq(out)
    }.taskValue,

    Compile / compile / watchTriggers += baseDirectory.value.toGlob / "confD" / "*.txt",
  )
```

Driver: start `sbt "~compile"` in the background with stdin on `/dev/null`, wait
60 s for the first `Monitoring source files` line, then for each of `confA`,
`confB`, `confC`, `confD` append `date +%s` to `<dir>/in.txt` and wait 15 s, then
append a comment line to `src/main/scala/Main.scala` and wait 20 s, then kill sbt.
Content is appended rather than `touch`ed because `fileInputs` globs are hash
stamped (§5).

Result: `Build triggered by ... confB/in.txt`, `... confC/in.txt`,
`... confD/in.txt`, `... src/main/scala/Main.scala`. Four triggers, five edits.
`confA` produced no event of any kind at debug level.

### A.2 sbt 1.12.14, rows E and F (`watchlab2`)

Same shape, with `Compile / genA / watchTriggers` added alongside the unused
`Compile / genA / fileInputs` on the same key and the same glob, and a second
named key `genE` that declares and consumes `fileInputs` but is never registered
in `sourceGenerators`. Edits: `confA/in.txt` then `confE/in.txt`.

Result: one trigger, `Build triggered by ... confA/in.txt`. `confE` produced
nothing.

### A.3 sbt 2.0.8 (`watchlab3`)

`sbt.version=2.0.8`, `scalaVersion := "3.8.4"`, one named generator `genB` that
declares and consumes `fileInputs`, plus
`Compile / compile / watchTriggers += baseDirectory.value.toGlob / "confD" / "*.txt"`.
The generator body must be wrapped in `Def.uncached { ... }` because sbt 2 refuses
to cache a task returning `Seq[File]`: "java.io.File and Path are not valid output
types for a cached task". Edits: `confB/in.txt`, `confD/in.txt`,
`src/main/scala/Main.scala`. All three triggered.

### A.4 Source reading

```
git clone --depth 1 --branch 1.12.x https://github.com/sbt/sbt.git   # 521c6969, 2026-08-24
git clone --depth 1 --branch 2.0.x  https://github.com/sbt/sbt.git   # 3127e8d6, 2026-08-27
curl -s "https://api.github.com/repos/sbt/sbt/commits?sha=2.0.x&path=main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala"
curl -s "https://api.github.com/repos/sbt/sbt/issues/7130"
curl -s "https://api.github.com/repos/sbt/sbt/releases?per_page=15"
```

## Appendix B: sources

sbt source, branch `1.12.x`:

- [`main/src/main/scala/sbt/internal/Continuous.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/Continuous.scala)
- [`main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala)
- [`main/src/main/scala/sbt/nio/Settings.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Settings.scala)
- [`main/src/main/scala/sbt/nio/Keys.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/nio/Keys.scala)
- [`main/src/main/scala/sbt/internal/FileChangesMacro.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/FileChangesMacro.scala)
- [`main/src/main/scala/sbt/internal/BuildStructure.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/internal/BuildStructure.scala)
- [`main/src/main/scala/sbt/Defaults.scala`](https://github.com/sbt/sbt/blob/1.12.x/main/src/main/scala/sbt/Defaults.scala)
- [`main-settings/src/main/scala/sbt/std/InputWrapper.scala`](https://github.com/sbt/sbt/blob/1.12.x/main-settings/src/main/scala/sbt/std/InputWrapper.scala)
- [`main-settings/src/main/scala/sbt/std/TaskMacro.scala`](https://github.com/sbt/sbt/blob/1.12.x/main-settings/src/main/scala/sbt/std/TaskMacro.scala)
- [`internal/util-collection/src/main/scala/sbt/internal/util/Settings.scala`](https://github.com/sbt/sbt/blob/1.12.x/internal/util-collection/src/main/scala/sbt/internal/util/Settings.scala)

sbt source, branch `2.0.x`:

- [`main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala`](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/internal/WatchTransitiveDependencies.scala)
- [`main/src/main/scala/sbt/nio/Settings.scala`](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/nio/Settings.scala)
- [`main/src/main/scala/sbt/Defaults.scala`](https://github.com/sbt/sbt/blob/2.0.x/main/src/main/scala/sbt/Defaults.scala)

sbt documentation and tracker:

- [Track file inputs and outputs](https://www.scala-sbt.org/1.x/docs/Howto-Track-File-Inputs-and-Outputs.html)
- [Triggered Execution](https://www.scala-sbt.org/1.x/docs/Triggered-Execution.html)
- [sbt/sbt#4512, "Watch rewrite"](https://github.com/sbt/sbt/pull/4512)
- [sbt/sbt#7130, "watchTriggers doesn't work as expected"](https://github.com/sbt/sbt/issues/7130)
- [sbt/sbt#8525, "[2.x] Fix watchTriggers to control what triggers instead of adding to fileInputs"](https://github.com/sbt/sbt/pull/8525)
