package io.eezo.db

import java.sql.PreparedStatement
import scala.NamedTuple.{From, Map}

/** A typed reference to one column of `T`. DESIGN §9.1.
  *
  * `name` is the **database** name, so a `Ref` field carries its `_id` suffix and users never write
  * one: the named tuple's label is the field name, while the value knows the column. It carries its
  * `Column[A]` because the macro has already summoned one, and the query DSL needs it to bind
  * parameters through the same codec path as `encode`.
  */
final class Col[T, A](val name: String)(using val codec: Column[A]) {

  private def cmp(op: String, v: A): Expr[T] =
    Expr.Cmp(name, op, (ps, i) => codec.put(ps, i, v))

  infix def ===(v: A): Expr[T] = cmp("=", v)
  infix def <>(v: A): Expr[T]  = cmp("<>", v)
  infix def <(v: A): Expr[T]   = cmp("<", v)
  infix def <=(v: A): Expr[T]  = cmp("<=", v)
  infix def >(v: A): Expr[T]   = cmp(">", v)
  infix def >=(v: A): Expr[T]  = cmp(">=", v)

  infix def in(vs: Seq[A]): Expr[T] =
    Expr.In(name, vs.toList.map(v => (ps: PreparedStatement, i: Int) => codec.put(ps, i, v)))

  def asc: Order[T]  = Order(name, "asc")
  def desc: Order[T] = Order(name, "desc")

  override def toString: String = name
}

/** The columns of `T`, as a named tuple: `cols.title` is a `Col[T, String]`.
  *
  * Computed from the **type** `T`, which is what makes it work at all. Backlog §7's blocker was
  * that `derives Table` expands to a given with an ascribed type `Table[Book]`, widening away any
  * refinement — so `Cols` could not be a type member. There is no refinement here to widen.
  */
type ColsOf[T] = Map[From[T], [A] =>> Col[T, A]]
