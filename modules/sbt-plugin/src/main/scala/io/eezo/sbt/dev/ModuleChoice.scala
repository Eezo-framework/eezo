package io.eezo.sbt
package dev

import java.io.File

/** Which project a directory names.
  *
  * The launcher applies the same rule to the projects it read from the build definition, because it
  * has to choose before sbt starts. `eezoDev` applies this one to sbt's own project list when the
  * current project does not enable the plugin. One project at the build root is that project.
  * Several are a refusal. An id that is not a project is unknown.
  */
private[sbt] object ModuleChoice {

  final case class Project(id: String, base: File)

  sealed trait Choice
  final case class Selected(id: String)        extends Choice
  final case class Ambiguous(ids: Seq[String]) extends Choice
  final case class Unknown(id: String)         extends Choice
  case object Unmatched                        extends Choice

  /** `explicit` overrides the directory. An id that is not in `projects` is [[Unknown]], even when
    * the directory would have named one.
    */
  def choose(
      cwd: File,
      buildRoot: File,
      projects: Seq[Project],
      explicit: Option[String]
  ): Choice = {
    val here    = canonical(cwd)
    val root    = canonical(buildRoot)
    val located = projects.map(project => project.copy(base = canonical(project.base)))
    explicit match {
      case Some(id) =>
        if (located.exists(_.id == id)) Selected(id) else Unknown(id)
      case None =>
        val matches = located.filter(project => isPrefix(project.base, here))
        if (matches.nonEmpty) Selected(longest(matches).id)
        else if (located.isEmpty && isPrefix(root, here)) Unmatched
        else if (located.size == 1 && same(here, root)) Selected(located.head.id)
        else if (located.size > 1 && same(here, root)) Ambiguous(located.map(_.id))
        else Unmatched
    }
  }

  /** What `eezoDev` does with a directory choice at the aggregate root.
    *
    * A selected project that enables the plugin is that project. The aggregate itself, or a refusal
    * from several projects, switches only when exactly one project enables the plugin. A selected
    * project that does not enable it is a refusal, so a sibling is not taken in its place.
    */
  def forConsole(choice: Choice, eezo: Seq[String], rootId: String): Either[Seq[String], String] =
    choice match {
      case Selected(id) if eezo.contains(id)              => Right(id)
      case Selected(id) if id == rootId && eezo.size == 1 => Right(eezo.head)
      case Ambiguous(_) if eezo.size == 1                 => Right(eezo.head)
      case Unmatched if eezo.size == 1                    => Right(eezo.head)
      case _                                              => Left(eezo)
    }

  private def longest(projects: Seq[Project]): Project =
    projects.maxBy(_.base.getPath.length)

  private def same(left: File, right: File): Boolean =
    left.getPath == right.getPath

  private def isPrefix(base: File, path: File): Boolean = {
    val root = base.getPath
    val here = path.getPath
    here == root || here.startsWith(root + File.separator)
  }

  private def canonical(file: File): File =
    try file.getCanonicalFile
    catch { case _: java.io.IOException => file.getAbsoluteFile }
}
