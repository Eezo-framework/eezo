package io.eezo.db

final case class SchemaError(msg: String)
    extends RuntimeException(msg)
    with scala.util.control.NoStackTrace
