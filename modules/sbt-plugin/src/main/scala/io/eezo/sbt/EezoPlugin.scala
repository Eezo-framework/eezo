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
  * auto-application of an empty-paren method. It says nothing about silent semantic divergence
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
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    Compile / eezoGenerateRoutes := generate.value,
    // The generated file lands in `sourceManaged`, not in a directory of eezo's own, because BSP
    // and IntelliJ take their source roots from `managedSources` rather than from what happens to
    // exist on disk. A file written anywhere else is invisible to the editor that has to navigate
    // it.
    Compile / sourceGenerators += (Compile / eezoGenerateRoutes).taskValue,
    // What the dev loop watches. `sourceGenerators` re-runs every generator on every evaluation,
    // so the caching below is this generator's own job; the trigger is what makes `~compile` react
    // to a new file under `app/` at all.
    Compile / eezoGenerateRoutes / watchTriggers +=
      Glob((Compile / scalaSource).value, RecursiveGlob / "*.scala")
  )

  /** A textual fingerprint of `RouteGenerator`'s emitted shape, written into the stamp file
    * `generate` hashes alongside the application's own sources below. It concatenates `render` on
    * no routes with `render` on one fixed, synthetic route, so the file's preamble, the empty-table
    * arm, and a single row's own template are all part of the fingerprint: a change to any of the
    * three changes this string, and therefore invalidates every project's cache automatically, with
    * no constant a human has to remember to bump. What it does not cover: a change to `routeFor`,
    * `sortRoutes`, or the verb table that alters which routes a real `app/` tree produces without
    * changing how `render` writes a route out. Such a change only invalidates a project's cache
    * once that project's own `app/` sources change, because those sources are the other half of the
    * hashed input set below.
    *
    * `private[sbt]` reads as a reference to the sbt library, and is not one. A qualifier inside an
    * access modifier is a single identifier, never a dotted path, so `private[io.eezo.sbt]` does
    * not parse and this is the only spelling available. The identifier is resolved against the
    * enclosing packages first, so `sbt` here is `io.eezo.sbt`, the package this file declares, and
    * not the root `sbt` that `import sbt._` brings in: visible to `EezoPluginSuite` in the same
    * package, and to nothing a consuming build sees.
    */
  private[sbt] def witness: String = {
    val samples = WitnessSources.flatMap(RouteGenerator.routeFor)
    val models  = RouteGenerator.modelsIn(WitnessModelSource, WitnessModel)
    RouteGenerator.render(Seq.empty, Seq.empty) + RouteGenerator.render(samples, models)
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
    * the scan's own rules — the package prefix and the `derives` window — are inside the
    * fingerprint alongside the derived half of `render`'s template.
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
    * content hash rather than existence, so a hand-edited or truncated Routes.scala is repaired on
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
    val stamp       = streams.value.cacheDirectory / "eezo-routes.version"
    IO.write(stamp, witness)
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
        relative(sourceRoot, source).toSeq.flatMap(RouteGenerator.modelsIn(_, IO.read(source)))
      }

      writeIfChanged(destination, RouteGenerator.render(routes, models))
      Set(destination)
    }

    cached(inputs).toSeq
  }

  /** `sourceGenerators` runs on every evaluation and the hashed input set covers the whole source
    * tree, so an edit to a file with no routes and no models in it reaches this point. Writing
    * identical bytes would still move the timestamp of the one file the following compile is
    * guaranteed to read, and so retrigger the work downstream of it; comparing first keeps a no-op
    * regeneration invisible to everything that watches the file.
    */
  private def writeIfChanged(destination: File, contents: String): Unit =
    if (!destination.exists() || IO.read(destination) != contents) IO.write(destination, contents)

  private def relative(directory: File, candidate: File): Option[String] =
    IO.relativize(directory, candidate).map(_.replace(java.io.File.separatorChar, '/'))
}
