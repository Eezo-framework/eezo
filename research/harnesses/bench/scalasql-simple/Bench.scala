//> using scala 3.7.3
//> using dep com.lihaoyi::scalasql:0.3.1
package bench
import scalasql.simple.*, scalasql.dialects.PostgresDialect.*

case class Ent0(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent0 extends SimpleTable[Ent0]
object Ent0Q:
  val all = Ent0.select
  val byName = Ent0.select.filter(_.name === "x")
  val cnt = Ent0.select.size

case class Ent1(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent1 extends SimpleTable[Ent1]
object Ent1Q:
  val all = Ent1.select
  val byName = Ent1.select.filter(_.name === "x")
  val cnt = Ent1.select.size

case class Ent2(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent2 extends SimpleTable[Ent2]
object Ent2Q:
  val all = Ent2.select
  val byName = Ent2.select.filter(_.name === "x")
  val cnt = Ent2.select.size

case class Ent3(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent3 extends SimpleTable[Ent3]
object Ent3Q:
  val all = Ent3.select
  val byName = Ent3.select.filter(_.name === "x")
  val cnt = Ent3.select.size

case class Ent4(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent4 extends SimpleTable[Ent4]
object Ent4Q:
  val all = Ent4.select
  val byName = Ent4.select.filter(_.name === "x")
  val cnt = Ent4.select.size

case class Ent5(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent5 extends SimpleTable[Ent5]
object Ent5Q:
  val all = Ent5.select
  val byName = Ent5.select.filter(_.name === "x")
  val cnt = Ent5.select.size

case class Ent6(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent6 extends SimpleTable[Ent6]
object Ent6Q:
  val all = Ent6.select
  val byName = Ent6.select.filter(_.name === "x")
  val cnt = Ent6.select.size

case class Ent7(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent7 extends SimpleTable[Ent7]
object Ent7Q:
  val all = Ent7.select
  val byName = Ent7.select.filter(_.name === "x")
  val cnt = Ent7.select.size

case class Ent8(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent8 extends SimpleTable[Ent8]
object Ent8Q:
  val all = Ent8.select
  val byName = Ent8.select.filter(_.name === "x")
  val cnt = Ent8.select.size

case class Ent9(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent9 extends SimpleTable[Ent9]
object Ent9Q:
  val all = Ent9.select
  val byName = Ent9.select.filter(_.name === "x")
  val cnt = Ent9.select.size

case class Ent10(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent10 extends SimpleTable[Ent10]
object Ent10Q:
  val all = Ent10.select
  val byName = Ent10.select.filter(_.name === "x")
  val cnt = Ent10.select.size

case class Ent11(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent11 extends SimpleTable[Ent11]
object Ent11Q:
  val all = Ent11.select
  val byName = Ent11.select.filter(_.name === "x")
  val cnt = Ent11.select.size

case class Ent12(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent12 extends SimpleTable[Ent12]
object Ent12Q:
  val all = Ent12.select
  val byName = Ent12.select.filter(_.name === "x")
  val cnt = Ent12.select.size

case class Ent13(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent13 extends SimpleTable[Ent13]
object Ent13Q:
  val all = Ent13.select
  val byName = Ent13.select.filter(_.name === "x")
  val cnt = Ent13.select.size

case class Ent14(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent14 extends SimpleTable[Ent14]
object Ent14Q:
  val all = Ent14.select
  val byName = Ent14.select.filter(_.name === "x")
  val cnt = Ent14.select.size

case class Ent15(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent15 extends SimpleTable[Ent15]
object Ent15Q:
  val all = Ent15.select
  val byName = Ent15.select.filter(_.name === "x")
  val cnt = Ent15.select.size

case class Ent16(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent16 extends SimpleTable[Ent16]
object Ent16Q:
  val all = Ent16.select
  val byName = Ent16.select.filter(_.name === "x")
  val cnt = Ent16.select.size

case class Ent17(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent17 extends SimpleTable[Ent17]
object Ent17Q:
  val all = Ent17.select
  val byName = Ent17.select.filter(_.name === "x")
  val cnt = Ent17.select.size

case class Ent18(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent18 extends SimpleTable[Ent18]
object Ent18Q:
  val all = Ent18.select
  val byName = Ent18.select.filter(_.name === "x")
  val cnt = Ent18.select.size

case class Ent19(id: Int, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object Ent19 extends SimpleTable[Ent19]
object Ent19Q:
  val all = Ent19.select
  val byName = Ent19.select.filter(_.name === "x")
  val cnt = Ent19.select.size
