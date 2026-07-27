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

case class Ent10[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent10 extends Table[Ent10]
object Ent10Q:
  val all = Ent10.select
  val byName = Ent10.select.filter(_.name === "x")
  val cnt = Ent10.select.size

case class Ent11[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent11 extends Table[Ent11]
object Ent11Q:
  val all = Ent11.select
  val byName = Ent11.select.filter(_.name === "x")
  val cnt = Ent11.select.size

case class Ent12[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent12 extends Table[Ent12]
object Ent12Q:
  val all = Ent12.select
  val byName = Ent12.select.filter(_.name === "x")
  val cnt = Ent12.select.size

case class Ent13[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent13 extends Table[Ent13]
object Ent13Q:
  val all = Ent13.select
  val byName = Ent13.select.filter(_.name === "x")
  val cnt = Ent13.select.size

case class Ent14[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent14 extends Table[Ent14]
object Ent14Q:
  val all = Ent14.select
  val byName = Ent14.select.filter(_.name === "x")
  val cnt = Ent14.select.size

case class Ent15[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent15 extends Table[Ent15]
object Ent15Q:
  val all = Ent15.select
  val byName = Ent15.select.filter(_.name === "x")
  val cnt = Ent15.select.size

case class Ent16[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent16 extends Table[Ent16]
object Ent16Q:
  val all = Ent16.select
  val byName = Ent16.select.filter(_.name === "x")
  val cnt = Ent16.select.size

case class Ent17[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent17 extends Table[Ent17]
object Ent17Q:
  val all = Ent17.select
  val byName = Ent17.select.filter(_.name === "x")
  val cnt = Ent17.select.size

case class Ent18[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent18 extends Table[Ent18]
object Ent18Q:
  val all = Ent18.select
  val byName = Ent18.select.filter(_.name === "x")
  val cnt = Ent18.select.size

case class Ent19[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent19 extends Table[Ent19]
object Ent19Q:
  val all = Ent19.select
  val byName = Ent19.select.filter(_.name === "x")
  val cnt = Ent19.select.size

case class Ent20[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent20 extends Table[Ent20]
object Ent20Q:
  val all = Ent20.select
  val byName = Ent20.select.filter(_.name === "x")
  val cnt = Ent20.select.size

case class Ent21[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent21 extends Table[Ent21]
object Ent21Q:
  val all = Ent21.select
  val byName = Ent21.select.filter(_.name === "x")
  val cnt = Ent21.select.size

case class Ent22[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent22 extends Table[Ent22]
object Ent22Q:
  val all = Ent22.select
  val byName = Ent22.select.filter(_.name === "x")
  val cnt = Ent22.select.size

case class Ent23[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent23 extends Table[Ent23]
object Ent23Q:
  val all = Ent23.select
  val byName = Ent23.select.filter(_.name === "x")
  val cnt = Ent23.select.size

case class Ent24[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent24 extends Table[Ent24]
object Ent24Q:
  val all = Ent24.select
  val byName = Ent24.select.filter(_.name === "x")
  val cnt = Ent24.select.size

case class Ent25[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent25 extends Table[Ent25]
object Ent25Q:
  val all = Ent25.select
  val byName = Ent25.select.filter(_.name === "x")
  val cnt = Ent25.select.size

case class Ent26[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent26 extends Table[Ent26]
object Ent26Q:
  val all = Ent26.select
  val byName = Ent26.select.filter(_.name === "x")
  val cnt = Ent26.select.size

case class Ent27[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent27 extends Table[Ent27]
object Ent27Q:
  val all = Ent27.select
  val byName = Ent27.select.filter(_.name === "x")
  val cnt = Ent27.select.size

case class Ent28[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent28 extends Table[Ent28]
object Ent28Q:
  val all = Ent28.select
  val byName = Ent28.select.filter(_.name === "x")
  val cnt = Ent28.select.size

case class Ent29[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent29 extends Table[Ent29]
object Ent29Q:
  val all = Ent29.select
  val byName = Ent29.select.filter(_.name === "x")
  val cnt = Ent29.select.size

case class Ent30[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent30 extends Table[Ent30]
object Ent30Q:
  val all = Ent30.select
  val byName = Ent30.select.filter(_.name === "x")
  val cnt = Ent30.select.size

case class Ent31[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent31 extends Table[Ent31]
object Ent31Q:
  val all = Ent31.select
  val byName = Ent31.select.filter(_.name === "x")
  val cnt = Ent31.select.size

case class Ent32[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent32 extends Table[Ent32]
object Ent32Q:
  val all = Ent32.select
  val byName = Ent32.select.filter(_.name === "x")
  val cnt = Ent32.select.size

case class Ent33[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent33 extends Table[Ent33]
object Ent33Q:
  val all = Ent33.select
  val byName = Ent33.select.filter(_.name === "x")
  val cnt = Ent33.select.size

case class Ent34[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent34 extends Table[Ent34]
object Ent34Q:
  val all = Ent34.select
  val byName = Ent34.select.filter(_.name === "x")
  val cnt = Ent34.select.size

case class Ent35[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent35 extends Table[Ent35]
object Ent35Q:
  val all = Ent35.select
  val byName = Ent35.select.filter(_.name === "x")
  val cnt = Ent35.select.size

case class Ent36[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent36 extends Table[Ent36]
object Ent36Q:
  val all = Ent36.select
  val byName = Ent36.select.filter(_.name === "x")
  val cnt = Ent36.select.size

case class Ent37[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent37 extends Table[Ent37]
object Ent37Q:
  val all = Ent37.select
  val byName = Ent37.select.filter(_.name === "x")
  val cnt = Ent37.select.size

case class Ent38[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent38 extends Table[Ent38]
object Ent38Q:
  val all = Ent38.select
  val byName = Ent38.select.filter(_.name === "x")
  val cnt = Ent38.select.size

case class Ent39[T[_]](id: T[Int], name: T[String], email: T[String], age: T[Int], active: T[Boolean], score: T[Double], bio: T[String])
object Ent39 extends Table[Ent39]
object Ent39Q:
  val all = Ent39.select
  val byName = Ent39.select.filter(_.name === "x")
  val cnt = Ent39.select.size
