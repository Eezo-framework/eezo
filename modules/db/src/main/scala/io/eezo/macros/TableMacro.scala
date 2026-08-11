package io.eezo.db.macros

import java.sql.{PreparedStatement, ResultSet}
import scala.quoted.*

import io.eezo.db.*

object TableMacro {

  def snake(s: String): String =
    s.foldLeft(new StringBuilder) { (b, ch) =>
      if (ch.isUpper && b.nonEmpty) { b += '_'; b += ch.toLower }
      else b += ch.toLower
    }.toString

  def derive[T: Type](using q: Quotes): Expr[Table[T]] = {
    val s = new Structure[q.type](using q)
    import q.reflect.*

    val sym    = TypeRepr.of[T].typeSymbol
    val fields = s.of[T]

    if (fields.isEmpty)
      report.errorAndAbort(s"${sym.name} has no fields.", Position.ofMacroExpansion)

    if (!fields.exists(_.name == "id"))
      report.errorAndAbort(
        s"${sym.name} has no `id` field. Every Eezo table needs one, e.g. `id: Id[${sym.name}]`.",
        Position.ofMacroExpansion
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
          val c = summonOrFail[t](f)
          '{
            ColumnDef.of[t](
              ${ Expr(snake(f.name)) },
              ${ Expr(f.name == "id") }
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

    '{
      new Table[T] {
        def tableName: String                                          = ${ Expr(snake(sym.name)) }
        val columns: List[ColumnDef]                                   = ${ Expr.ofList(colDefs) }
        def encode(ps: PreparedStatement, offset: Int, value: T): Unit =
          ${ encodeBody('ps, 'offset, 'value) }
        def decode(rs: ResultSet, offset: Int): T =
          ${ decodeBody('rs, 'offset) }
      }
    }
  }
}
