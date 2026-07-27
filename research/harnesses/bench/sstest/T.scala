//> using scala 3.3.7
//> using dep com.lihaoyi::scalasql:0.3.1
package t
import scalasql.*, scalasql.dialects.PostgresDialect.*

case class City[T[_]](id: T[Int], name: T[String])
object City extends Table[City]

object Q:
  val a = City.select
  val b = City.select.filter(_.name === "x")
