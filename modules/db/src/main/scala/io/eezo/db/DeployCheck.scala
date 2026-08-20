package io.eezo.db

import java.sql.Connection

object DeployCheck {
  def verify(c: Connection, code: SchemaSnap): Either[List[Change], Unit] = {
    val live = Introspect.snapshot(c)
    val d = Differ.diff(live, code)
    if (d.isEmpty) Right(()) else Left(d)
  }
}
