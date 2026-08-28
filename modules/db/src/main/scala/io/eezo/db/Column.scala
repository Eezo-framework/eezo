package io.eezo.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** A codec is a pure description: it turns a value into a bound parameter and a result column back
  * into a value, and it captures nothing. The `->` arrows below say so to the capture checker,
  * which is what lets an anonymous `Column` be built from them — a `=>` function may capture
  * anything, and `Column` is a pure base class.
  */
trait Column[A] { self =>
  def pgType: PgType
  def nullable: Boolean   = false
  def checks: List[Check] = Nil

  def put(ps: PreparedStatement, i: Int, a: A): Unit
  def get(rs: ResultSet, i: Int): A

  def imap[B](f: A -> B)(g: B -> A): Column[B] = new Column[B] {
    def pgType: PgType                                 = self.pgType
    override def nullable: Boolean                     = self.nullable
    override def checks: List[Check]                   = self.checks
    def put(ps: PreparedStatement, i: Int, b: B): Unit = self.put(ps, i, g(b))
    def get(rs: ResultSet, i: Int): B                  = f(self.get(rs, i))
  }

  def withType(t: PgType): Column[A] = new Column[A] {
    def pgType: PgType                                 = t
    override def nullable: Boolean                     = self.nullable
    override def checks: List[Check]                   = self.checks
    def put(ps: PreparedStatement, i: Int, a: A): Unit = self.put(ps, i, a)
    def get(rs: ResultSet, i: Int): A                  = self.get(rs, i)
  }

  def withCheck(c: Check): Column[A] = new Column[A] {
    def pgType: PgType                                 = self.pgType
    override def nullable: Boolean                     = self.nullable
    override def checks: List[Check]                   = c :: self.checks
    def put(ps: PreparedStatement, i: Int, a: A): Unit = self.put(ps, i, a)
    def get(rs: ResultSet, i: Int): A                  = self.get(rs, i)
  }
}

object Column {
  def apply[A](using c: Column[A]): Column[A] = c

  private def base[A](t: PgType)(
      w: (PreparedStatement, Int, A) -> Unit,
      r: (ResultSet, Int) -> A
  ): Column[A] = new Column[A] {
    def pgType: PgType                                 = t
    def put(ps: PreparedStatement, i: Int, a: A): Unit = w(ps, i, a)
    def get(rs: ResultSet, i: Int): A                  = r(rs, i)
  }

  given Column[String] =
    base(PgType.Text)((ps, i, a) => ps.setString(i, a), (rs, i) => rs.getString(i))
  given Column[Int]  = base(PgType.Int4)((ps, i, a) => ps.setInt(i, a), (rs, i) => rs.getInt(i))
  given Column[Long] = base(PgType.Int8)((ps, i, a) => ps.setLong(i, a), (rs, i) => rs.getLong(i))
  given Column[Boolean] =
    base(PgType.Bool)((ps, i, a) => ps.setBoolean(i, a), (rs, i) => rs.getBoolean(i))

  given Column[BigDecimal] =
    base(PgType.Numeric(19, 4))(
      (ps, i, a) => ps.setBigDecimal(i, a.bigDecimal),
      (rs, i) => BigDecimal(rs.getBigDecimal(i))
    )

  given Column[UUID] =
    base(PgType.Uuid)((ps, i, a) => ps.setObject(i, a), (rs, i) => rs.getObject(i, classOf[UUID]))

  given Column[LocalDate] =
    base(PgType.Date)(
      (ps, i, a) => ps.setObject(i, a),
      (rs, i) => rs.getObject(i, classOf[LocalDate])
    )

  given Column[Instant] = base(PgType.Timestamptz)(
    (ps, i, a) => ps.setObject(i, OffsetDateTime.ofInstant(a, ZoneOffset.UTC)),
    (rs, i) => rs.getObject(i, classOf[OffsetDateTime]).toInstant
  )

  given Column[Array[Byte]] =
    base(PgType.Bytea)((ps, i, a) => ps.setBytes(i, a), (rs, i) => rs.getBytes(i))

  given [A](using inner: Column[A]): Column[Option[A]] = new Column[Option[A]] {
    def pgType: PgType                                         = inner.pgType
    override def nullable: Boolean                             = true
    override def checks: List[Check]                           = inner.checks
    def put(ps: PreparedStatement, i: Int, a: Option[A]): Unit = a match {
      case Some(v) => inner.put(ps, i, v)
      case None    => ps.setNull(i, Types.NULL)
    }
    def get(rs: ResultSet, i: Int): Option[A] = {
      val v = inner.get(rs, i)
      if (rs.wasNull()) None else Some(v)
    }
  }
}
