//> using scala 3.7.3
//> using dep com.augustnagro::magnum:2.0.0-M3
package bench
import com.augustnagro.magnum.*

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent0(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent0C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent0Repo:
  val repo = Repo[Ent0C, Ent0, Long]
  def byName(n: String)(using DbCon): Vector[Ent0] =
    sql"""SELECT * FROM ent0 WHERE name = $n""".query[Ent0].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent1(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent1C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent1Repo:
  val repo = Repo[Ent1C, Ent1, Long]
  def byName(n: String)(using DbCon): Vector[Ent1] =
    sql"""SELECT * FROM ent1 WHERE name = $n""".query[Ent1].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent2(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent2C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent2Repo:
  val repo = Repo[Ent2C, Ent2, Long]
  def byName(n: String)(using DbCon): Vector[Ent2] =
    sql"""SELECT * FROM ent2 WHERE name = $n""".query[Ent2].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent3(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent3C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent3Repo:
  val repo = Repo[Ent3C, Ent3, Long]
  def byName(n: String)(using DbCon): Vector[Ent3] =
    sql"""SELECT * FROM ent3 WHERE name = $n""".query[Ent3].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent4(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent4C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent4Repo:
  val repo = Repo[Ent4C, Ent4, Long]
  def byName(n: String)(using DbCon): Vector[Ent4] =
    sql"""SELECT * FROM ent4 WHERE name = $n""".query[Ent4].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent5(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent5C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent5Repo:
  val repo = Repo[Ent5C, Ent5, Long]
  def byName(n: String)(using DbCon): Vector[Ent5] =
    sql"""SELECT * FROM ent5 WHERE name = $n""".query[Ent5].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent6(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent6C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent6Repo:
  val repo = Repo[Ent6C, Ent6, Long]
  def byName(n: String)(using DbCon): Vector[Ent6] =
    sql"""SELECT * FROM ent6 WHERE name = $n""".query[Ent6].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent7(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent7C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent7Repo:
  val repo = Repo[Ent7C, Ent7, Long]
  def byName(n: String)(using DbCon): Vector[Ent7] =
    sql"""SELECT * FROM ent7 WHERE name = $n""".query[Ent7].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent8(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent8C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent8Repo:
  val repo = Repo[Ent8C, Ent8, Long]
  def byName(n: String)(using DbCon): Vector[Ent8] =
    sql"""SELECT * FROM ent8 WHERE name = $n""".query[Ent8].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent9(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent9C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent9Repo:
  val repo = Repo[Ent9C, Ent9, Long]
  def byName(n: String)(using DbCon): Vector[Ent9] =
    sql"""SELECT * FROM ent9 WHERE name = $n""".query[Ent9].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent10(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent10C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent10Repo:
  val repo = Repo[Ent10C, Ent10, Long]
  def byName(n: String)(using DbCon): Vector[Ent10] =
    sql"""SELECT * FROM ent10 WHERE name = $n""".query[Ent10].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent11(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent11C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent11Repo:
  val repo = Repo[Ent11C, Ent11, Long]
  def byName(n: String)(using DbCon): Vector[Ent11] =
    sql"""SELECT * FROM ent11 WHERE name = $n""".query[Ent11].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent12(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent12C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent12Repo:
  val repo = Repo[Ent12C, Ent12, Long]
  def byName(n: String)(using DbCon): Vector[Ent12] =
    sql"""SELECT * FROM ent12 WHERE name = $n""".query[Ent12].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent13(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent13C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent13Repo:
  val repo = Repo[Ent13C, Ent13, Long]
  def byName(n: String)(using DbCon): Vector[Ent13] =
    sql"""SELECT * FROM ent13 WHERE name = $n""".query[Ent13].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent14(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent14C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent14Repo:
  val repo = Repo[Ent14C, Ent14, Long]
  def byName(n: String)(using DbCon): Vector[Ent14] =
    sql"""SELECT * FROM ent14 WHERE name = $n""".query[Ent14].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent15(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent15C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent15Repo:
  val repo = Repo[Ent15C, Ent15, Long]
  def byName(n: String)(using DbCon): Vector[Ent15] =
    sql"""SELECT * FROM ent15 WHERE name = $n""".query[Ent15].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent16(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent16C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent16Repo:
  val repo = Repo[Ent16C, Ent16, Long]
  def byName(n: String)(using DbCon): Vector[Ent16] =
    sql"""SELECT * FROM ent16 WHERE name = $n""".query[Ent16].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent17(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent17C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent17Repo:
  val repo = Repo[Ent17C, Ent17, Long]
  def byName(n: String)(using DbCon): Vector[Ent17] =
    sql"""SELECT * FROM ent17 WHERE name = $n""".query[Ent17].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent18(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent18C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent18Repo:
  val repo = Repo[Ent18C, Ent18, Long]
  def byName(n: String)(using DbCon): Vector[Ent18] =
    sql"""SELECT * FROM ent18 WHERE name = $n""".query[Ent18].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent19(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent19C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent19Repo:
  val repo = Repo[Ent19C, Ent19, Long]
  def byName(n: String)(using DbCon): Vector[Ent19] =
    sql"""SELECT * FROM ent19 WHERE name = $n""".query[Ent19].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent20(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent20C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent20Repo:
  val repo = Repo[Ent20C, Ent20, Long]
  def byName(n: String)(using DbCon): Vector[Ent20] =
    sql"""SELECT * FROM ent20 WHERE name = $n""".query[Ent20].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent21(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent21C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent21Repo:
  val repo = Repo[Ent21C, Ent21, Long]
  def byName(n: String)(using DbCon): Vector[Ent21] =
    sql"""SELECT * FROM ent21 WHERE name = $n""".query[Ent21].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent22(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent22C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent22Repo:
  val repo = Repo[Ent22C, Ent22, Long]
  def byName(n: String)(using DbCon): Vector[Ent22] =
    sql"""SELECT * FROM ent22 WHERE name = $n""".query[Ent22].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent23(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent23C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent23Repo:
  val repo = Repo[Ent23C, Ent23, Long]
  def byName(n: String)(using DbCon): Vector[Ent23] =
    sql"""SELECT * FROM ent23 WHERE name = $n""".query[Ent23].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent24(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent24C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent24Repo:
  val repo = Repo[Ent24C, Ent24, Long]
  def byName(n: String)(using DbCon): Vector[Ent24] =
    sql"""SELECT * FROM ent24 WHERE name = $n""".query[Ent24].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent25(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent25C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent25Repo:
  val repo = Repo[Ent25C, Ent25, Long]
  def byName(n: String)(using DbCon): Vector[Ent25] =
    sql"""SELECT * FROM ent25 WHERE name = $n""".query[Ent25].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent26(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent26C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent26Repo:
  val repo = Repo[Ent26C, Ent26, Long]
  def byName(n: String)(using DbCon): Vector[Ent26] =
    sql"""SELECT * FROM ent26 WHERE name = $n""".query[Ent26].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent27(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent27C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent27Repo:
  val repo = Repo[Ent27C, Ent27, Long]
  def byName(n: String)(using DbCon): Vector[Ent27] =
    sql"""SELECT * FROM ent27 WHERE name = $n""".query[Ent27].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent28(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent28C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent28Repo:
  val repo = Repo[Ent28C, Ent28, Long]
  def byName(n: String)(using DbCon): Vector[Ent28] =
    sql"""SELECT * FROM ent28 WHERE name = $n""".query[Ent28].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent29(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent29C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent29Repo:
  val repo = Repo[Ent29C, Ent29, Long]
  def byName(n: String)(using DbCon): Vector[Ent29] =
    sql"""SELECT * FROM ent29 WHERE name = $n""".query[Ent29].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent30(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent30C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent30Repo:
  val repo = Repo[Ent30C, Ent30, Long]
  def byName(n: String)(using DbCon): Vector[Ent30] =
    sql"""SELECT * FROM ent30 WHERE name = $n""".query[Ent30].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent31(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent31C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent31Repo:
  val repo = Repo[Ent31C, Ent31, Long]
  def byName(n: String)(using DbCon): Vector[Ent31] =
    sql"""SELECT * FROM ent31 WHERE name = $n""".query[Ent31].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent32(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent32C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent32Repo:
  val repo = Repo[Ent32C, Ent32, Long]
  def byName(n: String)(using DbCon): Vector[Ent32] =
    sql"""SELECT * FROM ent32 WHERE name = $n""".query[Ent32].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent33(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent33C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent33Repo:
  val repo = Repo[Ent33C, Ent33, Long]
  def byName(n: String)(using DbCon): Vector[Ent33] =
    sql"""SELECT * FROM ent33 WHERE name = $n""".query[Ent33].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent34(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent34C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent34Repo:
  val repo = Repo[Ent34C, Ent34, Long]
  def byName(n: String)(using DbCon): Vector[Ent34] =
    sql"""SELECT * FROM ent34 WHERE name = $n""".query[Ent34].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent35(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent35C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent35Repo:
  val repo = Repo[Ent35C, Ent35, Long]
  def byName(n: String)(using DbCon): Vector[Ent35] =
    sql"""SELECT * FROM ent35 WHERE name = $n""".query[Ent35].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent36(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent36C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent36Repo:
  val repo = Repo[Ent36C, Ent36, Long]
  def byName(n: String)(using DbCon): Vector[Ent36] =
    sql"""SELECT * FROM ent36 WHERE name = $n""".query[Ent36].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent37(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent37C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent37Repo:
  val repo = Repo[Ent37C, Ent37, Long]
  def byName(n: String)(using DbCon): Vector[Ent37] =
    sql"""SELECT * FROM ent37 WHERE name = $n""".query[Ent37].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent38(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent38C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent38Repo:
  val repo = Repo[Ent38C, Ent38, Long]
  def byName(n: String)(using DbCon): Vector[Ent38] =
    sql"""SELECT * FROM ent38 WHERE name = $n""".query[Ent38].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent39(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent39C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent39Repo:
  val repo = Repo[Ent39C, Ent39, Long]
  def byName(n: String)(using DbCon): Vector[Ent39] =
    sql"""SELECT * FROM ent39 WHERE name = $n""".query[Ent39].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent40(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent40C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent40Repo:
  val repo = Repo[Ent40C, Ent40, Long]
  def byName(n: String)(using DbCon): Vector[Ent40] =
    sql"""SELECT * FROM ent40 WHERE name = $n""".query[Ent40].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent41(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent41C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent41Repo:
  val repo = Repo[Ent41C, Ent41, Long]
  def byName(n: String)(using DbCon): Vector[Ent41] =
    sql"""SELECT * FROM ent41 WHERE name = $n""".query[Ent41].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent42(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent42C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent42Repo:
  val repo = Repo[Ent42C, Ent42, Long]
  def byName(n: String)(using DbCon): Vector[Ent42] =
    sql"""SELECT * FROM ent42 WHERE name = $n""".query[Ent42].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent43(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent43C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent43Repo:
  val repo = Repo[Ent43C, Ent43, Long]
  def byName(n: String)(using DbCon): Vector[Ent43] =
    sql"""SELECT * FROM ent43 WHERE name = $n""".query[Ent43].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent44(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent44C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent44Repo:
  val repo = Repo[Ent44C, Ent44, Long]
  def byName(n: String)(using DbCon): Vector[Ent44] =
    sql"""SELECT * FROM ent44 WHERE name = $n""".query[Ent44].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent45(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent45C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent45Repo:
  val repo = Repo[Ent45C, Ent45, Long]
  def byName(n: String)(using DbCon): Vector[Ent45] =
    sql"""SELECT * FROM ent45 WHERE name = $n""".query[Ent45].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent46(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent46C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent46Repo:
  val repo = Repo[Ent46C, Ent46, Long]
  def byName(n: String)(using DbCon): Vector[Ent46] =
    sql"""SELECT * FROM ent46 WHERE name = $n""".query[Ent46].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent47(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent47C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent47Repo:
  val repo = Repo[Ent47C, Ent47, Long]
  def byName(n: String)(using DbCon): Vector[Ent47] =
    sql"""SELECT * FROM ent47 WHERE name = $n""".query[Ent47].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent48(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent48C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent48Repo:
  val repo = Repo[Ent48C, Ent48, Long]
  def byName(n: String)(using DbCon): Vector[Ent48] =
    sql"""SELECT * FROM ent48 WHERE name = $n""".query[Ent48].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent49(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent49C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent49Repo:
  val repo = Repo[Ent49C, Ent49, Long]
  def byName(n: String)(using DbCon): Vector[Ent49] =
    sql"""SELECT * FROM ent49 WHERE name = $n""".query[Ent49].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent50(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent50C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent50Repo:
  val repo = Repo[Ent50C, Ent50, Long]
  def byName(n: String)(using DbCon): Vector[Ent50] =
    sql"""SELECT * FROM ent50 WHERE name = $n""".query[Ent50].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent51(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent51C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent51Repo:
  val repo = Repo[Ent51C, Ent51, Long]
  def byName(n: String)(using DbCon): Vector[Ent51] =
    sql"""SELECT * FROM ent51 WHERE name = $n""".query[Ent51].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent52(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent52C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent52Repo:
  val repo = Repo[Ent52C, Ent52, Long]
  def byName(n: String)(using DbCon): Vector[Ent52] =
    sql"""SELECT * FROM ent52 WHERE name = $n""".query[Ent52].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent53(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent53C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent53Repo:
  val repo = Repo[Ent53C, Ent53, Long]
  def byName(n: String)(using DbCon): Vector[Ent53] =
    sql"""SELECT * FROM ent53 WHERE name = $n""".query[Ent53].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent54(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent54C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent54Repo:
  val repo = Repo[Ent54C, Ent54, Long]
  def byName(n: String)(using DbCon): Vector[Ent54] =
    sql"""SELECT * FROM ent54 WHERE name = $n""".query[Ent54].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent55(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent55C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent55Repo:
  val repo = Repo[Ent55C, Ent55, Long]
  def byName(n: String)(using DbCon): Vector[Ent55] =
    sql"""SELECT * FROM ent55 WHERE name = $n""".query[Ent55].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent56(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent56C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent56Repo:
  val repo = Repo[Ent56C, Ent56, Long]
  def byName(n: String)(using DbCon): Vector[Ent56] =
    sql"""SELECT * FROM ent56 WHERE name = $n""".query[Ent56].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent57(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent57C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent57Repo:
  val repo = Repo[Ent57C, Ent57, Long]
  def byName(n: String)(using DbCon): Vector[Ent57] =
    sql"""SELECT * FROM ent57 WHERE name = $n""".query[Ent57].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent58(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent58C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent58Repo:
  val repo = Repo[Ent58C, Ent58, Long]
  def byName(n: String)(using DbCon): Vector[Ent58] =
    sql"""SELECT * FROM ent58 WHERE name = $n""".query[Ent58].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent59(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent59C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent59Repo:
  val repo = Repo[Ent59C, Ent59, Long]
  def byName(n: String)(using DbCon): Vector[Ent59] =
    sql"""SELECT * FROM ent59 WHERE name = $n""".query[Ent59].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent60(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent60C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent60Repo:
  val repo = Repo[Ent60C, Ent60, Long]
  def byName(n: String)(using DbCon): Vector[Ent60] =
    sql"""SELECT * FROM ent60 WHERE name = $n""".query[Ent60].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent61(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent61C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent61Repo:
  val repo = Repo[Ent61C, Ent61, Long]
  def byName(n: String)(using DbCon): Vector[Ent61] =
    sql"""SELECT * FROM ent61 WHERE name = $n""".query[Ent61].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent62(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent62C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent62Repo:
  val repo = Repo[Ent62C, Ent62, Long]
  def byName(n: String)(using DbCon): Vector[Ent62] =
    sql"""SELECT * FROM ent62 WHERE name = $n""".query[Ent62].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent63(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent63C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent63Repo:
  val repo = Repo[Ent63C, Ent63, Long]
  def byName(n: String)(using DbCon): Vector[Ent63] =
    sql"""SELECT * FROM ent63 WHERE name = $n""".query[Ent63].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent64(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent64C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent64Repo:
  val repo = Repo[Ent64C, Ent64, Long]
  def byName(n: String)(using DbCon): Vector[Ent64] =
    sql"""SELECT * FROM ent64 WHERE name = $n""".query[Ent64].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent65(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent65C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent65Repo:
  val repo = Repo[Ent65C, Ent65, Long]
  def byName(n: String)(using DbCon): Vector[Ent65] =
    sql"""SELECT * FROM ent65 WHERE name = $n""".query[Ent65].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent66(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent66C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent66Repo:
  val repo = Repo[Ent66C, Ent66, Long]
  def byName(n: String)(using DbCon): Vector[Ent66] =
    sql"""SELECT * FROM ent66 WHERE name = $n""".query[Ent66].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent67(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent67C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent67Repo:
  val repo = Repo[Ent67C, Ent67, Long]
  def byName(n: String)(using DbCon): Vector[Ent67] =
    sql"""SELECT * FROM ent67 WHERE name = $n""".query[Ent67].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent68(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent68C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent68Repo:
  val repo = Repo[Ent68C, Ent68, Long]
  def byName(n: String)(using DbCon): Vector[Ent68] =
    sql"""SELECT * FROM ent68 WHERE name = $n""".query[Ent68].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent69(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent69C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent69Repo:
  val repo = Repo[Ent69C, Ent69, Long]
  def byName(n: String)(using DbCon): Vector[Ent69] =
    sql"""SELECT * FROM ent69 WHERE name = $n""".query[Ent69].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent70(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent70C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent70Repo:
  val repo = Repo[Ent70C, Ent70, Long]
  def byName(n: String)(using DbCon): Vector[Ent70] =
    sql"""SELECT * FROM ent70 WHERE name = $n""".query[Ent70].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent71(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent71C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent71Repo:
  val repo = Repo[Ent71C, Ent71, Long]
  def byName(n: String)(using DbCon): Vector[Ent71] =
    sql"""SELECT * FROM ent71 WHERE name = $n""".query[Ent71].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent72(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent72C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent72Repo:
  val repo = Repo[Ent72C, Ent72, Long]
  def byName(n: String)(using DbCon): Vector[Ent72] =
    sql"""SELECT * FROM ent72 WHERE name = $n""".query[Ent72].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent73(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent73C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent73Repo:
  val repo = Repo[Ent73C, Ent73, Long]
  def byName(n: String)(using DbCon): Vector[Ent73] =
    sql"""SELECT * FROM ent73 WHERE name = $n""".query[Ent73].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent74(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent74C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent74Repo:
  val repo = Repo[Ent74C, Ent74, Long]
  def byName(n: String)(using DbCon): Vector[Ent74] =
    sql"""SELECT * FROM ent74 WHERE name = $n""".query[Ent74].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent75(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent75C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent75Repo:
  val repo = Repo[Ent75C, Ent75, Long]
  def byName(n: String)(using DbCon): Vector[Ent75] =
    sql"""SELECT * FROM ent75 WHERE name = $n""".query[Ent75].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent76(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent76C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent76Repo:
  val repo = Repo[Ent76C, Ent76, Long]
  def byName(n: String)(using DbCon): Vector[Ent76] =
    sql"""SELECT * FROM ent76 WHERE name = $n""".query[Ent76].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent77(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent77C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent77Repo:
  val repo = Repo[Ent77C, Ent77, Long]
  def byName(n: String)(using DbCon): Vector[Ent77] =
    sql"""SELECT * FROM ent77 WHERE name = $n""".query[Ent77].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent78(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent78C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent78Repo:
  val repo = Repo[Ent78C, Ent78, Long]
  def byName(n: String)(using DbCon): Vector[Ent78] =
    sql"""SELECT * FROM ent78 WHERE name = $n""".query[Ent78].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent79(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent79C(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec
object Ent79Repo:
  val repo = Repo[Ent79C, Ent79, Long]
  def byName(n: String)(using DbCon): Vector[Ent79] =
    sql"""SELECT * FROM ent79 WHERE name = $n""".query[Ent79].run()
