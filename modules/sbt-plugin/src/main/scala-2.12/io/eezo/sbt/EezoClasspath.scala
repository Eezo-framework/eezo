package io.eezo.sbt

import sbt._
import sbt.Keys._

/** The application's runtime classpath, as plain files — the sbt 1 half.
  *
  * This is the one task the two sbt axes cannot share a source for: sbt 1's `fullClasspath` is
  * `Seq[Attributed[File]]` and sbt 2's carries virtual file references that need a `FileConverter`
  * to become paths. Everything downstream (`DevProcess`) sees `Seq[File]` and stays shared.
  */
private[sbt] object EezoClasspath {

  def files: Def.Initialize[Task[Seq[File]]] = Def.task {
    (Runtime / fullClasspath).value.map(_.data)
  }

  /** This project's own artifact, as a plain file — same split, same reason. */
  def packagedJar: Def.Initialize[Task[File]] = Def.task {
    (Compile / packageBin).value
  }
}
