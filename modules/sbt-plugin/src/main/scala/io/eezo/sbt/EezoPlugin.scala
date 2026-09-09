package io.eezo.sbt

import sbt._
import sbt.Keys._
import sbt.nio.Keys.watchTriggers
import sbt.nio.file.Glob
import sbt.nio.file.RecursiveGlob
import sbt.plugins.JvmPlugin

/** The sbt plugin that puts eezo's generated route table on a project's source path.
  *
  * Both overrides below are sbt's defaults, and both are written out anyway, because they are
  * decisions rather than accidents. `noTrigger` means the plugin does nothing until a build asks
  * for it with `.enablePlugins(EezoPlugin)`: an `allRequirements` plugin would have to scan every
  * subproject of every build that happens to have eezo on its plugin classpath just to discover it
  * has nothing to do. `requires = JvmPlugin` buys settings ordering, not scope: the
  * `Compile / sourceGenerators` key lives in `sbt.Keys` unconditionally, and `JvmPlugin` is
  * triggered by `allRequirements`, so it is enabled either way. What declaring the dependency
  * guarantees is that this plugin's settings land after `JvmPlugin`'s, so the
  * `Compile / sourceGenerators +=` below appends to that baseline instead of being clobbered by it.
  *
  * This source is compiled against sbt 1 on Scala 2.12 and against sbt 2 on Scala 3, so it must
  * stay inside the subset both accept. Nothing on the 2.12 axis enforces that subset. `-Xsource:3`
  * there is permissive rather than restrictive: it lets Scala 3 syntax through, so
  * `import scala.collection.mutable.*` compiles, while it still accepts Scala 2 constructs that
  * Scala 3 hard rejects, such as procedure syntax, symbol literals, `forSome`, `do ... while`, and
  * automatic application of an empty paren method. It says nothing about silent semantic divergence
  * either, such as a leading infix operand, which compiles clean on both axes and means different
  * things on each. Building both axes is the only real check.
  */
object EezoPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = noTrigger

  override def requires: Plugins = JvmPlugin

  object autoImport {

    // `@transient` opts the key out of sbt 2's automatic task caching, which refuses a
    // `java.io.File` result and asks for one of its virtual file types instead. sbt 1 has no such
    // caching and ignores the annotation, so one annotation serves both axes. Caching is not lost:
    // `FileFunction.cached` below is the generator's own, and it is the one sbt 1 needs anyway.
    @transient val eezoGenerateRoutes: TaskKey[Seq[File]] =
      taskKey[Seq[File]]("Generates io.eezo.generated.Routes from src/main/scala/app/.")

    // The command tasks. Each is one forward to `run <command>`, so `sbt eezoStatus` and
    // `sbt "run status"` are the same code path (the dispatch the entry trait inherits) and the
    // plugin stays sugar rather than mechanism (design/cli.md §4). `@transient` for the same
    // reason as above: these are effectful forwards, and sbt 2 must never satisfy one from a
    // task cache.
    @transient val eezoStatus: TaskKey[Unit] =
      taskKey[Unit]("Forwards to `run status`: code vs live database.")

    @transient val eezoRoutes: TaskKey[Unit] =
      taskKey[Unit]("Forwards to `run routes`: the mounted table, with boot's warnings.")

    @transient val eezoSync: InputKey[Unit] =
      inputKey[Unit]("Forwards to `run sync`; pass --apply and --force through.")

    @transient val eezoMigrate: InputKey[Unit] =
      inputKey[Unit]("Forwards to `run migrate`; pass --apply through.")

    @transient val eezoFreeze: InputKey[Unit] =
      inputKey[Unit]("Forwards to `run freeze <name>`: writes a migration from the model diff.")

    // The dev loop's two halves. `eezoRestart` is the kernel: kill the app's JVM, fork it again
    // on the fresh classpath, return so the watch can wait for the next edit. `eezoDev`, the
    // command a user actually types, is a command alias over it, defined in `globalSettings`.
    @transient val eezoRestart: TaskKey[Unit] =
      taskKey[Unit]("(Re)starts the application in dev mode in a background JVM.")

    @transient val eezoStop: TaskKey[Unit] =
      taskKey[Unit]("Stops the application `eezoRestart` started, if it is running.")

    // Deployment staging: everything sbt-shaped that `eezo deploy` needs, so the launcher itself
    // never touches a classpath. `@transient` because the result is a `File`.
    @transient val eezoStage: TaskKey[File] =
      taskKey[File](
        "Stages the application for deployment under target/eezo/stage: lib/ with every runtime " +
          "jar, the db/ migrations, and a generated Dockerfile."
      )

    val eezoJavaVersion: SettingKey[String] =
      settingKey[String]("Java version of the deployment base image (eclipse-temurin JRE).")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    Compile / eezoGenerateRoutes := generate.value,
    // The generated file lands in `sourceManaged`, not in a directory of eezo's own, because BSP
    // and IntelliJ take their source roots from `managedSources` rather than from what happens to
    // exist on disk. A file written anywhere else is invisible to the editor that has to navigate
    // it.
    Compile / sourceGenerators += (Compile / eezoGenerateRoutes).taskValue,
    // What the dev loop watches. `sourceGenerators` runs every generator again on every evaluation,
    // so the caching below is this generator's own job; the trigger is what makes `~compile` react
    // to a new file under `app/` at all.
    Compile / eezoGenerateRoutes / watchTriggers +=
      Glob((Compile / scalaSource).value, RecursiveGlob / "*.scala"),
    // The command tasks, project level rather than scoped to `Compile` so that `sbt eezoStatus`
    // needs no scope to type. `toTask` on `run` rather than `runner` directly, so the forward
    // inherits everything the build already decided about running (fork, working directory,
    // javaOptions) instead of restating it.
    eezoStatus  := unit((Compile / run).toTask(" status")).value,
    eezoRoutes  := unit((Compile / run).toTask(" routes")).value,
    eezoSync    := forward("sync").evaluated,
    eezoMigrate := forward("migrate").evaluated,
    eezoFreeze  := forward("freeze").evaluated,
    // `freeze` prompts on destructive changes, and a forked `run` reads nothing unless the build
    // forwards stdin. Set here rather than left to every application's build, because a prompt
    // that hangs on unforwarded input looks like a hang, not like a setting.
    Compile / run / connectInput := true,
    // The restart kernel. `fullClasspath` is what makes it compile first: asking for the
    // classpath compiles the project, so a broken edit fails here and the previous process keeps
    // serving; the dev loop never kills a working server for a compile error.
    eezoRestart := {
      val log  = streams.value.log
      val main = (Compile / run / mainClass).value
        .getOrElse(
          sys.error(
            "eezoRestart: no main class. Define an `object Main` extending `EezoApp`, `HttpApp` or `DbApp`."
          )
        )
      DevProcess.restart(
        javaHome = (Compile / run / javaHome).value,
        jvmOptions = (Compile / run / javaOptions).value,
        classpath = EezoClasspath.files.value,
        mainClass = main,
        args = Seq("dev"),
        workingDirectory = baseDirectory.value,
        log = message => log.info(message)
      )
    },
    eezoStop := {
      val log = streams.value.log
      DevProcess.stop(quiet = false, log = message => log.info(message))
    },
    eezoJavaVersion := "25",
    // The staged layout is the whole deployment contract: lib/ holds every runtime jar (this
    // project's own classes via packageBin, so directory classpath entries are not shipped raw),
    // db/ holds the committed migrations (present even when empty, so the Dockerfile's COPY never
    // fails), and the Dockerfile is regenerated every time — it lives in target/, is never
    // user-edited, and `Deploy.dockerfile`'s comment says why its shape is what it is.
    eezoStage := {
      val log   = streams.value.log
      val stage = target.value / "eezo" / "stage"
      val lib   = stage / "lib"
      IO.delete(stage)
      IO.createDirectory(lib)

      val own       = EezoClasspath.packagedJar.value
      val classpath = EezoClasspath.files.value
      val jars      = classpath.filter(f => f.isFile && f.getName.endsWith(".jar")) :+ own
      classpath.filter(_.isDirectory).foreach { dir =>
        // The project's own classes directory is covered by packageBin. Anything else here is an
        // internal dependency of a multi-project build, which v0 staging does not package.
        log.debug(s"eezoStage: skipping directory classpath entry $dir")
      }

      // Names can collide across artifacts; the first keeps its name, later ones are prefixed.
      val used = scala.collection.mutable.Set.empty[String]
      jars.foreach { jar =>
        var name  = jar.getName
        var index = 1
        while (!used.add(name)) { name = s"$index-${jar.getName}"; index += 1 }
        IO.copyFile(jar, lib / name)
      }

      IO.createDirectory(stage / "db")
      val db = baseDirectory.value / "db"
      if (db.exists) IO.copyDirectory(db, stage / "db")

      val main = (Compile / run / mainClass).value.getOrElse(
        sys.error("eezoStage: no main class. Define an `object Main extends EezoApp`.")
      )
      IO.write(stage / "Dockerfile", Deploy.dockerfile(main, eezoJavaVersion.value))

      log.info(s"eezoStage: ${jars.size} jars staged at $stage")
      stage
    }
  )

  /** `eezoDev`: restart on every edit. The watch is sbt's own (`watchTriggers` above already covers
    * the source tree, and `eezoRestart`'s classpath dependency pulls the compile), so the loop is
    * one alias rather than machinery: research/build-reload.md measured resident `~` at 148 to 300
    * ms from save to rebuilt, which is the budget this rides on.
    *
    * `dev` is the http edge's command. On a database only application (`DbApp` alone) it is
    * unknown, so `eezoDev` prints the unknown command line on every save; a rerun on save loop for
    * a job is sbt's own `~run`.
    */
  override lazy val globalSettings: Seq[Setting[_]] =
    addCommandAlias("eezoDev", "~eezoRestart")

  /** `eezoSync --apply` → `run sync --apply`: the task's own arguments, appended after the command.
    * One helper so the three input tasks cannot drift apart.
    */
  private def forward(command: String): Def.Initialize[InputTask[Unit]] =
    Def.inputTaskDyn {
      val args = sbt.complete.DefaultParsers.spaceDelimited("<args>").parsed
      unit((Compile / run).toTask((command +: args).mkString(" ", " ", "")))
    }

  /** The forward's result, discarded through `dependsOn`.
    *
    * On sbt 1 `run` yields `Unit` and this is redundant; on sbt 2 it yields a union type that a
    * `TaskKey[Unit]` cannot hold. `dependsOn` takes the dependency existentially on both axes, so
    * sequencing through it instead of `.value` is the one spelling the shared source can use.
    */
  private def unit[A](fwd: Def.Initialize[Task[A]]): Def.Initialize[Task[Unit]] =
    Def.task(()).dependsOn(fwd)

  /** A textual fingerprint of `RouteGenerator`'s emitted shape, written into the stamp file
    * `generate` hashes alongside the application's own sources below. It concatenates `render` on
    * no routes with `render` on one fixed, synthetic route, so the file's preamble, the empty table
    * arm, and a single row's own template are all part of the fingerprint: a change to any of the
    * three changes this string, and therefore invalidates every project's cache automatically, with
    * no constant a human has to remember to bump. What it does not cover: a change to `routeFor`,
    * `sortRoutes`, or the verb table that alters which routes a real `app/` tree produces without
    * changing how `render` writes a route out. Such a change only invalidates a project's cache
    * once that project's own `app/` sources change, because those sources are the other half of the
    * hashed input set below.
    *
    * `dbOnClasspath` is folded in rather than left to the emitted text alone, and it is the one
    * input that no source file carries. Adding `eezo-db` to an existing application changes its
    * `libraryDependencies` and nothing under `src/main/scala`, so without the flag here every
    * hashed input would be byte identical, the cache would hit, and the application would keep a
    * `Routes.scala` whose `storeFor` only ever mints an in memory store. Every model deriving
    * `Table` would then go on losing its rows at shutdown, with no error anywhere to say why. The
    * flag being part of the fingerprint is what turns adding the dependency into a cache miss.
    *
    * `private[sbt]` reads as a reference to the sbt library, and is not one. A qualifier inside an
    * access modifier is a single identifier, never a dotted path, so `private[io.eezo.sbt]` does
    * not parse and this is the only spelling available. The identifier is resolved against the
    * enclosing packages first, so `sbt` here is `io.eezo.sbt`, the package this file declares, and
    * not the root `sbt` that `import sbt._` brings in: visible to `EezoPluginSuite` in the same
    * package, and to nothing a consuming build sees.
    */
  private[sbt] def witness(dbOnClasspath: Boolean): String = {
    val samples = WitnessSources.flatMap(RouteGenerator.routeFor)
    val models  = RouteGenerator.modelsIn(WitnessModelSource, WitnessModel)
    RouteGenerator.render(Seq.empty, Seq.empty, dbOnClasspath) +
      RouteGenerator.render(samples, models, dbOnClasspath)
  }

  /** Two files below `app/`, chosen so that `witness` covers more of the generator than `render`'s
    * own template. They run through `routeFor`, so the filename to verb table and the `_seg` to
    * `:seg` translation are inside the fingerprint. There are two of them rather than one, because
    * a single row never exercises the separator `render` joins rows with, and they are listed with
    * the parameterised one first, so `sortRoutes` has to reorder them and any change to emit order
    * changes the fingerprint too.
    */
  private val WitnessSources: Seq[String] =
    Seq("eezoWitness/_id/Show.scala", "eezoWitness/New.scala")

  /** One model, run through `modelsIn` rather than written as a `ModelCandidate` by hand, so that
    * the scan's own rules (the package prefix and the `derives` window) are inside the fingerprint
    * alongside the derived half of `render`'s template.
    */
  private val WitnessModelSource: String = "eezoWitness/Model.scala"

  private val WitnessModel: String =
    "package eezoWitness\n\ncase class Model(id: Long) derives Form\n"

  /** Reads the application's sources under `app/` and writes `io.eezo.generated.Routes`.
    *
    * `sourceGenerators` runs every generator on every evaluation of `compile`, so a generator that
    * does not cache rewrites its output on every keystroke of a watch loop and invalidates the
    * compile that follows. `FileFunction.cached` is what makes the rewrite conditional. The hashed
    * input set is the `.scala` files under `app/` plus a stamp file carrying `witness`, not the
    * whole `Compile` source tree, so editing a file outside `app/`, such as `Main.scala`, no longer
    * misses the cache. The stamp is what makes upgrading the plugin itself miss the cache even when
    * the application's own sources are untouched, since any change to `RouteGenerator`'s emitted
    * shape changes `witness`, and therefore a hashed input, with no version number to bump by hand;
    * it also guarantees the input set is never empty when a project has no `app/` directory yet, so
    * the cached body still runs once and writes a table with no rows. The output is tracked by
    * content hash rather than existence, so a hand edited or truncated Routes.scala is repaired on
    * the next run, not only a deleted one.
    */
  private def generate: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log         = streams.value.log
    val sourceRoot  = (Compile / scalaSource).value
    val appRoot     = sourceRoot / "app"
    val destination = (Compile / sourceManaged).value / "io" / "eezo" / "generated" / "Routes.scala"
    val appInputs   = (appRoot ** "*.scala").get().toSet
    // Everything outside `app/` is where models live, and a new one has to reach the table, so the
    // whole source tree is hashed rather than `app/` alone. The cost is a rescan when any source
    // changes, which `writeIfChanged` below absorbs: a rescan that finds nothing new leaves the
    // generated file alone and does not invalidate the compile that follows it.
    val modelInputs = ((sourceRoot ** "*.scala").get().toSet -- appInputs)

    // Whether `eezo-db` reaches this project's *compile* classpath, which is what decides whether
    // the emitted `storeFor` may name `io.eezo.db.Table`. Read off the resolution report rather
    // than off `libraryDependencies`, because the declared list misses an `eezo-db` that arrives
    // transitively, through a shared internal module that depends on it. That miss is silent data
    // loss rather than an error: the generator would emit the in memory only arm, every model
    // deriving `Table` would compile and serve, and its rows would die at shutdown.
    //
    // The report has to be narrowed to one configuration, and the configuration is
    // `compile-internal` rather than `compile`. `UpdateReport.allModules` is not an answer here at
    // all: it merges every configuration report there is, compile, runtime, test, provided and
    // optional alike, so it is not what the compiler sees. An application that declares only
    // `"io.eezo" %% "eezo-testkit" % V % Test` gets `eezo-db_3` in its *test* configuration,
    // because testkit depends on db at compile scope; `allModules` reports it, this flag reads
    // true, and the generator writes `io.eezo.db.Table` into `src_managed/main`, which is a
    // Compile source. The application then fails `Compile / compile` with `value db is not a
    // member of io.eezo`, in a file its author cannot edit.
    //
    // `compile` is not the answer either, and the reason was measured jar for jar against
    // `Compile / dependencyClasspath` on sbt 1.12.14 and sbt 2.0.6. `ConfigRef("compile")` is NOT
    // equal to it: it omits `% Provided` dependencies (slf4j-api, in the measurement) and
    // `% Optional` ones (six upickle modules), every one of which is genuinely on the compile
    // classpath. Reading it would produce the mirror image of the bug above, and the worse half of
    // it: `eezo-db` present to the compiler, this flag false, and every model deriving `Table`
    // silently handed an in memory store that loses its rows at shutdown. `ConfigRef(
    // "compile-internal")` is equal to it, with zero modules missing and zero extra, on both sbt
    // versions, and including the inter project case where a library reaches the compile classpath
    // through `web.dependsOn(models)` while being absent from `web`'s own `libraryDependencies`.
    //
    // One source file serves both axes because `UpdateReport.configuration` has an identical
    // signature on each: the same `sbt.librarymanagement.ConfigRef` argument, the same
    // `Option[ConfigurationReport]` result, and the same `allModules` on that report.
    //
    // `Compile / dependencyClasspath` is accurate by construction and is still not used. It forces
    // every upstream project to compile before this generator may run, and on sbt 1 a sibling
    // in the build arrives on it as a directory named `classes`, which no filename match can
    // identify as `eezo-db`.
    //
    // Reading a resolution task from a source generator does not risk a cycle here: `update`
    // depends on `libraryDependencies` and `projectDependencies`, never on `Compile / sources`, so
    // the generator this flag feeds is downstream of it and not the other way round. Sharper, and
    // also measured: `dependencyClasspath`, `externalDependencyClasspath`,
    // `internalDependencyClasspath`, `managedClasspath` and `update` are all safe to read from a
    // source generator, while `fullClasspath`, `exportedProducts`, `products` and the same
    // project's own `compile` are not. Their failure mode is not a reported cycle that names the
    // keys involved; it is a silent hang.
    //
    // The name is matched with its cross version suffix as well as bare, because the resolved
    // report carries the artifact name, which for a Scala library is `eezo-db_3`.
    val dbOnClasspath = update.value
      .configuration(ConfigRef("compile-internal"))
      .exists(
        _.allModules.exists(module =>
          module.organization == "io.eezo" &&
            (module.name == "eezo-db" || module.name.startsWith("eezo-db_"))
        )
      )

    val stamp = streams.value.cacheDirectory / "eezo-routes.version"
    IO.write(stamp, witness(dbOnClasspath))
    val inputs = appInputs ++ modelInputs + stamp

    val cached = FileFunction.cached(
      streams.value.cacheDirectory / "eezo-routes",
      FilesInfo.hash,
      FilesInfo.hash
    ) { (changed: Set[File]) =>
      val _ = changed

      val appFiles = appInputs.toSeq.sortBy(_.getAbsolutePath)

      val routes = appFiles.flatMap { source =>
        relative(appRoot, source).flatMap(RouteGenerator.routeFor).map { route =>
          RouteGenerator.missingDef(route, IO.read(source)).foreach(log.warn(_))
          route
        }
      }

      val models = modelInputs.toSeq.sortBy(_.getAbsolutePath).flatMap { source =>
        relative(sourceRoot, source).toSeq.flatMap { relativePath =>
          val content = IO.read(source)
          RouteGenerator.unscannableModels(relativePath, content).foreach(log.warn(_))
          RouteGenerator.modelsIn(relativePath, content)
        }
      }

      writeIfChanged(destination, RouteGenerator.render(routes, models, dbOnClasspath))
      Set(destination)
    }

    cached(inputs).toSeq
  }

  /** `sourceGenerators` runs on every evaluation and the hashed input set covers the whole source
    * tree, so an edit to a file with no routes and no models in it reaches this point. Writing
    * identical bytes would still move the timestamp of the one file the following compile is
    * guaranteed to read, and so retrigger the work downstream of it; comparing first keeps an
    * unchanged regeneration invisible to everything that watches the file.
    */
  private def writeIfChanged(destination: File, contents: String): Unit =
    if (!destination.exists() || IO.read(destination) != contents) IO.write(destination, contents)

  private def relative(directory: File, candidate: File): Option[String] =
    IO.relativize(directory, candidate).map(_.replace(java.io.File.separatorChar, '/'))
}
