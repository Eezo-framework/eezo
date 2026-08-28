package io.eezo.db

import java.sql.PreparedStatement

/** Binds one parameter. Pure: it closes over a value and its codec, both of which capture nothing,
  * and saying so is what lets these be stored in the tree below.
  */
type Bind = (PreparedStatement, Int) -> Unit

/** A predicate over `T`, as the small closed ADT BACKLOG §7 asks for. DESIGN §9.2.
  *
  * Values never reach the SQL string: each becomes a `?` and a `Bind` that goes through the same
  * `Column[A]` the macro summoned for `encode`. Injection is not defended against here, it is
  * unrepresentable.
  */
enum Expr[T] {
  case Cmp(col: String, op: String, bind: Bind)
  case In(col: String, binds: List[Bind])
  case And(l: Expr[T], r: Expr[T])
  case Or(l: Expr[T], r: Expr[T])
  case Not(e: Expr[T])

  /** The empty predicate. `Expr.and` treats it as the identity, so chaining `.where` does not
    * accumulate `true and ...`.
    */
  case All()

  infix def and(o: Expr[T]): Expr[T] = Expr.and(this, o)
  infix def or(o: Expr[T]): Expr[T]  = Expr.Or(this, o)
  def unary_! : Expr[T]              = Expr.Not(this)
}

object Expr {

  def and[T](l: Expr[T], r: Expr[T]): Expr[T] = (l, r) match {
    case (All(), x) => x
    case (x, All()) => x
    case _          => And(l, r)
  }

  /** Renders parameterised SQL and the binds that fill it, in the order they appear. */
  def render[T](e: Expr[T]): (String, List[Bind]) = e match {
    case Cmp(c, op, b) => (s""""$c" $op ?""", List(b))
    // `in ()` is a syntax error in Postgres, and an empty set matches nothing
    case In(_, Nil) => ("false", Nil)
    case In(c, bs)  => (s""""$c" in (${bs.map(_ => "?").mkString(", ")})""", bs)
    case And(l, r)  =>
      val (a, x) = render(l); val (b, y) = render(r); (s"($a and $b)", x ++ y)
    case Or(l, r) =>
      val (a, x) = render(l); val (b, y) = render(r); (s"($a or $b)", x ++ y)
    case Not(i) =>
      val (a, x) = render(i); (s"not $a", x)
    case All() => ("true", Nil)
  }
}

/** One `order by` term. Postgres's own NULL ordering is used — last for `asc`, first for `desc` —
  * rather than an explicit `NULLS` clause. DESIGN §9.4.
  */
final case class Order[T](column: String, direction: String) {
  def render: String = s""""$column" $direction"""
}
