package io.eezo.db.macros

import io.eezo.core.internal.util.snake

/** The decisions of `derives Table` that need no compiler: the names a table and its columns get,
  * where the key is, and the text of each compile error.
  *
  * `TableMacro` calls these while the compiler expands a derivation. A suite sees a macro body only
  * through the trees it builds and the compile errors it reports, so the logic lives here, where
  * `TableRulesSuite` calls it directly, and the macro keeps only the work that needs `Quotes`.
  */
private[db] object TableRules {

  def tableName(className: String): String = snake(className)

  /** A `Ref` field stores the key of the row it names, so its column gets the `_id` suffix. */
  def columnName(fieldName: String, isRef: Boolean): String =
    if (isRef) snake(fieldName) + "_id" else snake(fieldName)

  /** Whether a field is the key: only the name `id`, in lower case. */
  def isKey(fieldName: String): Boolean = fieldName == "id"

  /** The place of the key among the fields, or the compile error that refuses the model. */
  def keyIndex(className: String, fieldNames: List[String]): Either[String, Int] = {
    val index = fieldNames.indexWhere(isKey)
    if (fieldNames.isEmpty) Left(s"$className has no fields.")
    else if (index < 0)
      Left(s"$className has no `id` field. Every Eezo table needs one, e.g. `id: Id[$className]`.")
    else Right(index)
  }

  def wrongKeyType(className: String, shown: String): String =
    s"`id` must be `Id[$className]`, but is `$shown`."

  def noMapping(shown: String, fieldName: String): String =
    s"""No database mapping for $shown at field `$fieldName`.
       |
       |Provide one in scope:
       |  given Column[$shown] = Column[String].imap(...)(...)
       |""".stripMargin
}
