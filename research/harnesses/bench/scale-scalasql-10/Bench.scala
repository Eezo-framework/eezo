//> using scala 3.7.3
//> using dep com.lihaoyi::scalasql:0.3.1
package bench
import scalasql.*, scalasql.dialects.PostgresDialect.*

case class Ent0[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent0 extends Table[Ent0]
object Ent0Q:
  val all = Ent0.select
  val byName = Ent0.select.filter(_.name === "x")
  val cnt = Ent0.select.size

case class Ent1[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent1 extends Table[Ent1]
object Ent1Q:
  val all = Ent1.select
  val byName = Ent1.select.filter(_.name === "x")
  val cnt = Ent1.select.size

case class Ent2[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent2 extends Table[Ent2]
object Ent2Q:
  val all = Ent2.select
  val byName = Ent2.select.filter(_.name === "x")
  val cnt = Ent2.select.size

case class Ent3[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent3 extends Table[Ent3]
object Ent3Q:
  val all = Ent3.select
  val byName = Ent3.select.filter(_.name === "x")
  val cnt = Ent3.select.size

case class Ent4[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent4 extends Table[Ent4]
object Ent4Q:
  val all = Ent4.select
  val byName = Ent4.select.filter(_.name === "x")
  val cnt = Ent4.select.size

case class Ent5[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent5 extends Table[Ent5]
object Ent5Q:
  val all = Ent5.select
  val byName = Ent5.select.filter(_.name === "x")
  val cnt = Ent5.select.size

case class Ent6[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent6 extends Table[Ent6]
object Ent6Q:
  val all = Ent6.select
  val byName = Ent6.select.filter(_.name === "x")
  val cnt = Ent6.select.size

case class Ent7[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent7 extends Table[Ent7]
object Ent7Q:
  val all = Ent7.select
  val byName = Ent7.select.filter(_.name === "x")
  val cnt = Ent7.select.size

case class Ent8[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent8 extends Table[Ent8]
object Ent8Q:
  val all = Ent8.select
  val byName = Ent8.select.filter(_.name === "x")
  val cnt = Ent8.select.size

case class Ent9[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent9 extends Table[Ent9]
object Ent9Q:
  val all = Ent9.select
  val byName = Ent9.select.filter(_.name === "x")
  val cnt = Ent9.select.size
