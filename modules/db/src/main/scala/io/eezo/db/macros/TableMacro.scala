package io.eezo.db.macros

import java.sql.{PreparedStatement, ResultSet}
import scala.quoted.*

// `Expr` is excluded: `io.eezo.db.Expr` is the query predicate and `scala.quoted.Expr` is the macro
// one, and every macro in this package needs the latter. The exclusion is local to macro sources.
import io.eezo.db.{Expr as _, *}
import io.eezo.core.Id

/** `derives Table`. Only what needs `Quotes` is here: reading the case class, summoning the givens,
  * building the trees. Every decision that needs no compiler is in `TableRules`, where a suite
  * calls it. This code runs in the compiler while it compiles the test sources, so a suite sees
  * only the trees it builds and the compile errors it reports.
  */
object TableMacro {

  def derive[T: Type](using q: Quotes): Expr[Table[T]] = new Expansion[T].table

  /** One expansion of `derives Table` for `T`. The checks run in this order: the case class checks
    * of `Structure` and then no fields and no `id`, while the fields below are read; then the type
    * of `id`, and then the first field with no `Column` given, while `table` runs. A model that
    * breaks several rules is refused for the first one.
    */
  private final class Expansion[T: Type](using val q: Quotes) {
    import q.reflect.*

    private val s         = new Structure[q.type](using q)
    private val className = TypeRepr.of[T].typeSymbol.name
    private val fields    = s.of[T]
    private val key       = fields(
      TableRules
        .keyIndex(className, fields.map(_.name))
        .fold(report.errorAndAbort(_, Position.ofMacroExpansion), identity)
    )

    def table: Expr[Table[T]] = {
      checkKeyType()
      val columnDefs = Expr.ofList(fields.map(columnDef))
      val colValues  = colsExpr
      '{
        new Table[T] {
          def tableName: String        = ${ Expr(TableRules.tableName(className)) }
          val columns: List[ColumnDef] = $columnDefs
          def encode(ps: PreparedStatement, offset: Int, value: T): Unit =
            ${ encodeBody('ps, 'offset, 'value) }
          def decode(rs: ResultSet, offset: Int): T =
            ${ decodeBody('rs, 'offset) }
          def idOf(value: T): Id[T] = ${ idOfBody('value) }
          val cols: ColsOf[T]       = $colValues
        }
      }
    }

    // Checked, not merely suggested: `findById`, `update` and `delete` are typed in terms of
    // `Id[T]`, and `idOf` below reads this field, so a differently typed `id` would fail later and
    // further from the cause.
    private def checkKeyType(): Unit =
      if (!(key.tpe =:= TypeRepr.of[Id[T]]))
        report.errorAndAbort(TableRules.wrongKeyType(className, key.tpe.show), key.pos)

    private def column[A: Type](f: s.FieldInfo): Expr[Column[A]] =
      Expr
        .summon[Column[A]]
        .getOrElse(
          report.errorAndAbort(TableRules.noMapping(Type.show[A], f.name), f.pos)
        )

    private def refTarget[A: Type]: Option[Expr[RefTarget[A]]] = Expr.summon[RefTarget[A]]

    private def columnName[A: Type](f: s.FieldInfo): Expr[String] =
      Expr(TableRules.columnName(f.name, refTarget[A].isDefined))

    private def columnDef(f: s.FieldInfo): Expr[ColumnDef] =
      f.tpe.asType match {
        case '[t] =>
          val c                            = column[t](f)
          val target: Expr[Option[String]] =
            refTarget[t].map(r => '{ Some($r.table) }).getOrElse('{ None })
          '{
            ColumnDef
              .of[t](${ columnName[t](f) }, ${ Expr(TableRules.isKey(f.name)) }, $target)(using $c)
          }
      }

    private def put(ps: Expr[PreparedStatement], off: Expr[Int], v: Expr[T])(
        f: s.FieldInfo,
        i: Int
    ): Term =
      f.tpe.asType match {
        case '[t] =>
          val c   = column[t](f)
          val fld = Select.unique(v.asTerm, f.name).asExprOf[t]
          '{ $c.put($ps, $off + ${ Expr(i) }, $fld) }.asTerm
      }

    private def encodeBody(ps: Expr[PreparedStatement], off: Expr[Int], v: Expr[T]): Expr[Unit] =
      Block(fields.zipWithIndex.map(put(ps, off, v)), '{ () }.asTerm).asExprOf[Unit]

    private def get(rs: Expr[ResultSet], off: Expr[Int])(f: s.FieldInfo, i: Int): Term =
      f.tpe.asType match {
        case '[t] =>
          val c = column[t](f)
          '{ $c.get($rs, $off + ${ Expr(i) }) }.asTerm
      }

    private def decodeBody(rs: Expr[ResultSet], off: Expr[Int]): Expr[T] = {
      val args = fields.zipWithIndex.map(get(rs, off))
      Apply(Select(New(TypeTree.of[T]), TypeRepr.of[T].typeSymbol.primaryConstructor), args)
        .asExprOf[T]
    }

    private def idOfBody(v: Expr[T]): Expr[Id[T]] =
      Select.unique(v.asTerm, key.name).asExprOf[Id[T]]

    private def col(f: s.FieldInfo): Expr[Any] =
      f.tpe.asType match {
        case '[t] => '{ new Col[T, t](${ columnName[t](f) })(using ${ column[t](f) }) }
      }

    /** The `Col` values, in declaration order.
      *
      * Assembled as an ordinary tuple and cast, rather than through `NamedTuple.build`: a named
      * tuple *is* a `Tuple` at run time, and the labels come from `NamedTuple.From[T]`, which reads
      * the same constructor parameters in the same order as `fields`. So the cast asserts an
      * ordering the compiler derives from the same source, not an assumption about the user.
      */
    private def colsExpr: Expr[ColsOf[T]] =
      '{ ${ Expr.ofTupleFromSeq(fields.map(col)) }.asInstanceOf[ColsOf[T]] }
  }
}
