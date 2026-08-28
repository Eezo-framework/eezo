package io.eezo.db.macros

import java.sql.{PreparedStatement, ResultSet}
import scala.quoted.*

// `Expr` is excluded: `io.eezo.db.Expr` is the query predicate and `scala.quoted.Expr` is the macro
// one, and every macro in this package needs the latter. The exclusion is local to macro sources.
import io.eezo.db.{Expr as _, *}
import io.eezo.db.internal.util.*

object TableMacro {

  def derive[T: Type](using q: Quotes): Expr[Table[T]] = {
    val s = new Structure[q.type](using q)
    import q.reflect.*

    val sym    = TypeRepr.of[T].typeSymbol
    val fields = s.of[T]

    if (fields.isEmpty)
      report.errorAndAbort(s"${sym.name} has no fields.", Position.ofMacroExpansion)

    val idField = fields.find(_.name == "id").getOrElse {
      report.errorAndAbort(
        s"${sym.name} has no `id` field. Every Eezo table needs one, e.g. `id: Id[${sym.name}]`.",
        Position.ofMacroExpansion
      )
    }

    // Checked, not merely suggested: `findById`, `update` and `delete` are typed in terms of
    // `Id[T]`, and `idOf` below reads this field, so a differently-typed `id` would fail later and
    // further from the cause.
    if (!(idField.tpe =:= TypeRepr.of[Id[T]]))
      report.errorAndAbort(
        s"`id` must be `Id[${sym.name}]`, but is `${idField.tpe.show}`.",
        idField.pos
      )

    def summonOrFail[A: Type](f: s.FieldInfo): Expr[Column[A]] =
      Expr.summon[Column[A]].getOrElse {
        report.errorAndAbort(
          s"""No database mapping for ${Type.show[A]} at field `${f.name}`.
             |
             |Provide one in scope:
             |  given Column[${Type.show[A]}] = Column[String].imap(...)(...)
             |""".stripMargin,
          f.pos
        )
      }

    val colDefs: List[Expr[ColumnDef]] = fields.map { f =>
      f.tpe.asType match {
        case '[t] =>
          val c       = summonOrFail[t](f)
          val rt      = Expr.summon[RefTarget[t]]
          val colName = if (rt.isDefined) snake(f.name) + "_id" else snake(f.name)
          val refExpr: Expr[Option[String]] = rt match {
            case Some(r) => '{ Some($r.table) }
            case None    => '{ None }
          }
          '{
            ColumnDef.of[t](
              ${ Expr(colName) },
              ${ Expr(f.name == "id") },
              $refExpr
            )(using $c)
          }
      }
    }

    def encodeBody(ps: Expr[PreparedStatement], off: Expr[Int], v: Expr[T]): Expr[Unit] = {
      val stmts = fields.zipWithIndex.map { case (f, i) =>
        f.tpe.asType match {
          case '[t] =>
            val c   = summonOrFail[t](f)
            val fld = Select.unique(v.asTerm, f.name).asExprOf[t]
            '{ $c.put($ps, $off + ${ Expr(i) }, $fld) }.asTerm
        }
      }
      Block(stmts, '{ () }.asTerm).asExprOf[Unit]
    }

    def decodeBody(rs: Expr[ResultSet], off: Expr[Int]): Expr[T] = {
      val args = fields.zipWithIndex.map { case (f, i) =>
        f.tpe.asType match {
          case '[t] =>
            val c = summonOrFail[t](f)
            '{ $c.get($rs, $off + ${ Expr(i) }) }.asTerm
        }
      }
      Apply(Select(New(TypeTree.of[T]), sym.primaryConstructor), args).asExprOf[T]
    }

    def idOfBody(v: Expr[T]): Expr[Id[T]] = Select.unique(v.asTerm, "id").asExprOf[Id[T]]

    /** The `Col` values, in declaration order.
      *
      * Assembled as an ordinary tuple and cast, rather than through `NamedTuple.build`: a named
      * tuple *is* a `Tuple` at run time, and the labels come from `NamedTuple.From[T]`, which reads
      * the same constructor parameters in the same order as `fields` below. So the cast asserts an
      * ordering the compiler derives from the same source — not an assumption about the user.
      */
    val colsExpr: Expr[ColsOf[T]] = {
      val values: List[Expr[Any]] = fields.map { f =>
        f.tpe.asType match {
          case '[t] =>
            val c       = summonOrFail[t](f)
            val rt      = Expr.summon[RefTarget[t]]
            val colName = if (rt.isDefined) snake(f.name) + "_id" else snake(f.name)
            '{ new Col[T, t](${ Expr(colName) })(using $c) }
        }
      }
      '{ ${ Expr.ofTupleFromSeq(values) }.asInstanceOf[ColsOf[T]] }
    }

    '{
      new Table[T] {
        def tableName: String                                          = ${ Expr(snake(sym.name)) }
        val columns: List[ColumnDef]                                   = ${ Expr.ofList(colDefs) }
        def encode(ps: PreparedStatement, offset: Int, value: T): Unit =
          ${ encodeBody('ps, 'offset, 'value) }
        def decode(rs: ResultSet, offset: Int): T =
          ${ decodeBody('rs, 'offset) }
        def idOf(value: T): Id[T] = ${ idOfBody('value) }
        val cols: ColsOf[T]       = $colsExpr
      }
    }
  }
}
