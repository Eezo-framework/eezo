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
