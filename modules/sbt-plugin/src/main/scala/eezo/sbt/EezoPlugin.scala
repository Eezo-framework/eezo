package eezo.sbt

import sbt._
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
  * `Compile / sourceGenerators +=` this plugin will carry appends to that baseline instead of being
  * clobbered by it.
  *
  * This source is compiled against sbt 1 on Scala 2.12 and against sbt 2 on Scala 3, so it must
  * stay inside the subset both accept. Nothing on the 2.12 axis enforces that subset. `-Xsource:3`
  * there is permissive rather than restrictive: it lets Scala 3 syntax through, so
  * `import scala.collection.mutable.*` compiles, while it still accepts Scala 2 constructs that
  * Scala 3 hard rejects, such as procedure syntax, symbol literals, `forSome`, `do ... while`, and
  * auto-application of an empty-paren method. It says nothing about silent semantic divergence
  * either, such as a leading infix operand, which compiles clean on both axes and means different
  * things on each. Building both axes is the only real check.
  *
  * The generator itself is not here yet. Its shape, the scan that finds `derives Resource`, and
  * where it writes are settled by [[https://github.com/Eezo-framework/eezo/issues/109 issue 109]].
  */
object EezoPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = noTrigger

  override def requires: Plugins = JvmPlugin
}
