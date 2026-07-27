//> using scala 3.7.3
//> using dep io.getquill::quill-jdbc:4.8.6
package bench
import io.getquill.*
object QCtx:
  lazy val ctx = new PostgresJdbcContext(SnakeCase, "ctx")

case class QEnt0(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt0Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt0] }
  inline def byName(inline n: String) = quote { query[QEnt0].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt0].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt1(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt1Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt1] }
  inline def byName(inline n: String) = quote { query[QEnt1].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt1].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt2(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt2Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt2] }
  inline def byName(inline n: String) = quote { query[QEnt2].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt2].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt3(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt3Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt3] }
  inline def byName(inline n: String) = quote { query[QEnt3].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt3].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt4(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt4Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt4] }
  inline def byName(inline n: String) = quote { query[QEnt4].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt4].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt5(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt5Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt5] }
  inline def byName(inline n: String) = quote { query[QEnt5].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt5].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt6(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt6Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt6] }
  inline def byName(inline n: String) = quote { query[QEnt6].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt6].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt7(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt7Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt7] }
  inline def byName(inline n: String) = quote { query[QEnt7].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt7].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt8(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt8Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt8] }
  inline def byName(inline n: String) = quote { query[QEnt8].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt8].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt9(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt9Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt9] }
  inline def byName(inline n: String) = quote { query[QEnt9].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt9].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))
