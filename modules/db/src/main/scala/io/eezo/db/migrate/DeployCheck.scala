package io.eezo.db.migrate

import io.eezo.db.schema.{Change, CheckSpelling, SchemaSnap}

import java.sql.Connection

/** The last step of a deploy: does the database this process is about to serve actually match the
  * model it was compiled against?
  */
object DeployCheck {
  def verify(
      c: Connection,
      code: SchemaSnap,
      schema: String = "public"
  ): Either[List[Change], Unit] = {
    val d = CheckSpelling.drift(c, code, schema)
    if (d.isEmpty) Right(()) else Left(d)
  }
}
