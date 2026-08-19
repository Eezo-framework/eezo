package io.eezo.db

final class SchemaError(msg: String)
    extends RuntimeException(msg)
    with scala.util.control.NoStackTrace
