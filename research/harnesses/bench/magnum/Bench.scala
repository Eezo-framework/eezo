//> using scala 3.7.3
//> using dep com.augustnagro::magnum:2.0.0-M3
package bench
import com.augustnagro.magnum.*

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent0(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent0Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent0Repo:
  val repo = Repo[Ent0Creator, Ent0, Long]
  def byName(n: String)(using DbCon): Vector[Ent0] =
    sql"""SELECT * FROM ent0 WHERE name = $n""".query[Ent0].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent1(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent1Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent1Repo:
  val repo = Repo[Ent1Creator, Ent1, Long]
  def byName(n: String)(using DbCon): Vector[Ent1] =
    sql"""SELECT * FROM ent1 WHERE name = $n""".query[Ent1].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent2(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent2Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent2Repo:
  val repo = Repo[Ent2Creator, Ent2, Long]
  def byName(n: String)(using DbCon): Vector[Ent2] =
    sql"""SELECT * FROM ent2 WHERE name = $n""".query[Ent2].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent3(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent3Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent3Repo:
  val repo = Repo[Ent3Creator, Ent3, Long]
  def byName(n: String)(using DbCon): Vector[Ent3] =
    sql"""SELECT * FROM ent3 WHERE name = $n""".query[Ent3].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent4(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent4Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent4Repo:
  val repo = Repo[Ent4Creator, Ent4, Long]
  def byName(n: String)(using DbCon): Vector[Ent4] =
    sql"""SELECT * FROM ent4 WHERE name = $n""".query[Ent4].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent5(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent5Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent5Repo:
  val repo = Repo[Ent5Creator, Ent5, Long]
  def byName(n: String)(using DbCon): Vector[Ent5] =
    sql"""SELECT * FROM ent5 WHERE name = $n""".query[Ent5].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent6(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent6Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent6Repo:
  val repo = Repo[Ent6Creator, Ent6, Long]
  def byName(n: String)(using DbCon): Vector[Ent6] =
    sql"""SELECT * FROM ent6 WHERE name = $n""".query[Ent6].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent7(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent7Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent7Repo:
  val repo = Repo[Ent7Creator, Ent7, Long]
  def byName(n: String)(using DbCon): Vector[Ent7] =
    sql"""SELECT * FROM ent7 WHERE name = $n""".query[Ent7].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent8(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent8Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent8Repo:
  val repo = Repo[Ent8Creator, Ent8, Long]
  def byName(n: String)(using DbCon): Vector[Ent8] =
    sql"""SELECT * FROM ent8 WHERE name = $n""".query[Ent8].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent9(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent9Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent9Repo:
  val repo = Repo[Ent9Creator, Ent9, Long]
  def byName(n: String)(using DbCon): Vector[Ent9] =
    sql"""SELECT * FROM ent9 WHERE name = $n""".query[Ent9].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent10(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent10Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent10Repo:
  val repo = Repo[Ent10Creator, Ent10, Long]
  def byName(n: String)(using DbCon): Vector[Ent10] =
    sql"""SELECT * FROM ent10 WHERE name = $n""".query[Ent10].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent11(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent11Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent11Repo:
  val repo = Repo[Ent11Creator, Ent11, Long]
  def byName(n: String)(using DbCon): Vector[Ent11] =
    sql"""SELECT * FROM ent11 WHERE name = $n""".query[Ent11].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent12(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent12Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent12Repo:
  val repo = Repo[Ent12Creator, Ent12, Long]
  def byName(n: String)(using DbCon): Vector[Ent12] =
    sql"""SELECT * FROM ent12 WHERE name = $n""".query[Ent12].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent13(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent13Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent13Repo:
  val repo = Repo[Ent13Creator, Ent13, Long]
  def byName(n: String)(using DbCon): Vector[Ent13] =
    sql"""SELECT * FROM ent13 WHERE name = $n""".query[Ent13].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent14(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent14Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent14Repo:
  val repo = Repo[Ent14Creator, Ent14, Long]
  def byName(n: String)(using DbCon): Vector[Ent14] =
    sql"""SELECT * FROM ent14 WHERE name = $n""".query[Ent14].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent15(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent15Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent15Repo:
  val repo = Repo[Ent15Creator, Ent15, Long]
  def byName(n: String)(using DbCon): Vector[Ent15] =
    sql"""SELECT * FROM ent15 WHERE name = $n""".query[Ent15].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent16(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent16Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent16Repo:
  val repo = Repo[Ent16Creator, Ent16, Long]
  def byName(n: String)(using DbCon): Vector[Ent16] =
    sql"""SELECT * FROM ent16 WHERE name = $n""".query[Ent16].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent17(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent17Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent17Repo:
  val repo = Repo[Ent17Creator, Ent17, Long]
  def byName(n: String)(using DbCon): Vector[Ent17] =
    sql"""SELECT * FROM ent17 WHERE name = $n""".query[Ent17].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent18(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent18Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent18Repo:
  val repo = Repo[Ent18Creator, Ent18, Long]
  def byName(n: String)(using DbCon): Vector[Ent18] =
    sql"""SELECT * FROM ent18 WHERE name = $n""".query[Ent18].run()

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent19(@Id id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ent19Creator(name: String, email: String, age: Int, active: Boolean, score: Double, bio: String) derives DbCodec

object Ent19Repo:
  val repo = Repo[Ent19Creator, Ent19, Long]
  def byName(n: String)(using DbCon): Vector[Ent19] =
    sql"""SELECT * FROM ent19 WHERE name = $n""".query[Ent19].run()
