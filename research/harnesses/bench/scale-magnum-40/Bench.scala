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
