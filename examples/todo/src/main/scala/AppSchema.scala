import io.eezo.db.Schema

import models.Todo

/** The application's schema: which models get tables, plus what the types alone cannot say —
  * indexes. `table[Todo]` reads the compiler-derived `Table[Todo]`; the selectors are typed, so
  * renaming a field breaks the index at compile time instead of at migration time.
  */
object AppSchema extends Schema {
  val todos = table[Todo].index(_.done).unique(_.title)
}
