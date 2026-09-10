import io.eezo.db.Schema

import models.Post

/** The application's schema: which models get tables, plus what the types alone cannot say. A title
  * is unique, so two posts cannot share one; the selector is typed, so renaming the field breaks
  * the constraint at compile time instead of at migration time.
  */
object AppSchema extends Schema {
  val posts = table[Post].unique(_.title)
}
