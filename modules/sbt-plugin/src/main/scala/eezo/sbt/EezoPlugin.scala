package eezo.sbt

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
      taskKey[Seq[File]]("Generates eezo.generated.Routes from src/main/scala/app/.")
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

  /** Bumped whenever `RouteGenerator`'s emitted shape changes, and hashed alongside the
    * application's own sources below. A hardcoded constant rather than the plugin's own artifact
    * version read off the jar manifest, because a local `publishLocal` republishes the same version
    * string repeatedly, which would leave the cache none the wiser about a changed generator.
    */
  private[sbt] val GeneratorVersion = "1"

  /** Reads the application's sources under `app/` and writes `eezo.generated.Routes`.
    *
    * `sourceGenerators` runs every generator on every evaluation of `compile`, so a generator that
    * does not cache rewrites its output on every keystroke of a watch loop and invalidates the
    * compile that follows. `FileFunction.cached` is what makes the rewrite conditional. The hashed
    * input set is the `.scala` files under `app/` plus a stamp file carrying `GeneratorVersion`,
    * not the whole `Compile` source tree, so editing a file outside `app/`, such as `Main.scala`,
    * no longer misses the cache. The stamp is what makes upgrading the plugin itself miss the cache
    * even when the application's own sources are untouched, since bumping `GeneratorVersion`
    * changes a hashed input; it also guarantees the input set is never empty when a project has no
    * `app/` directory yet, so the cached body still runs once and writes a table with no rows. The
    * output is tracked by content hash rather than existence, so a hand-edited or truncated
    * Routes.scala is repaired on the next run, not only a deleted one.
    */
  private def generate: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log         = streams.value.log
    val sourceRoot  = (Compile / scalaSource).value
    val appRoot     = sourceRoot / "app"
    val destination = (Compile / sourceManaged).value / "eezo" / "generated" / "Routes.scala"
    val appInputs   = (appRoot ** "*.scala").get().toSet
    val stamp       = streams.value.cacheDirectory / "eezo-routes.version"
    IO.write(stamp, GeneratorVersion)
    val inputs = appInputs + stamp

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

      IO.write(destination, RouteGenerator.render(routes))
      Set(destination)
    }

    cached(inputs).toSeq
  }

  private def relative(directory: File, candidate: File): Option[String] =
    IO.relativize(directory, candidate).map(_.replace(java.io.File.separatorChar, '/'))
}
