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

case class QEnt10(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt10Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt10] }
  inline def byName(inline n: String) = quote { query[QEnt10].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt10].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt11(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt11Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt11] }
  inline def byName(inline n: String) = quote { query[QEnt11].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt11].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt12(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt12Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt12] }
  inline def byName(inline n: String) = quote { query[QEnt12].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt12].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt13(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt13Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt13] }
  inline def byName(inline n: String) = quote { query[QEnt13].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt13].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt14(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt14Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt14] }
  inline def byName(inline n: String) = quote { query[QEnt14].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt14].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt15(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt15Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt15] }
  inline def byName(inline n: String) = quote { query[QEnt15].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt15].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt16(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt16Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt16] }
  inline def byName(inline n: String) = quote { query[QEnt16].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt16].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt17(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt17Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt17] }
  inline def byName(inline n: String) = quote { query[QEnt17].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt17].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt18(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt18Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt18] }
  inline def byName(inline n: String) = quote { query[QEnt18].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt18].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))

case class QEnt19(id: Long, name: String, email: String, age: Int, active: Boolean, score: Double, bio: String)
object QEnt19Q:
  import QCtx.ctx.*
  inline def all = quote { query[QEnt19] }
  inline def byName(inline n: String) = quote { query[QEnt19].filter(_.name == lift(n)) }
  inline def older(inline a: Int) = quote { query[QEnt19].filter(_.age > lift(a)).sortBy(_.score) }
  def run1 = QCtx.ctx.run(all)
  def run2(n: String) = QCtx.ctx.run(byName(n))
  def run3(a: Int) = QCtx.ctx.run(older(a))
