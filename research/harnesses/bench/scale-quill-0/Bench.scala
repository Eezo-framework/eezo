//> using scala 3.7.3
//> using dep io.getquill::quill-jdbc:4.8.6
package bench
import io.getquill.*
object QCtx:
  lazy val ctx = new PostgresJdbcContext(SnakeCase, "ctx")
