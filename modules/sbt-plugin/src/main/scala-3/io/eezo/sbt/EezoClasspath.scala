package io.eezo.sbt

import sbt._
import sbt.Keys._

/** The application's runtime classpath, as plain files — the sbt 2 half.
  *
  * sbt 2's `fullClasspath` carries `HashedVirtualFileRef`s rather than files; `fileConverter` is
  * the sanctioned way back to real paths. See the sbt 1 half for why this object exists at all.
  */
private[sbt] object EezoClasspath {

  def files: Def.Initialize[Task[Seq[File]]] = Def.task {
    val converter = fileConverter.value
    (Runtime / fullClasspath).value.map(entry => converter.toPath(entry.data).toFile)
  }
  /** This project's own artifact, as a plain file — same split, same reason. */
  def packagedJar: Def.Initialize[Task[File]] = Def.task {
    fileConverter.value.toPath((Compile / packageBin).value).toFile
  }
}
