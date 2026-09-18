import io.eezo.db.Schema

import models.Post
import models.User

/** The application's schema: which models get tables, plus what the types alone cannot say. A title
  * is unique, so two posts cannot share one; the selector is typed, so renaming the field breaks
  * the constraint at compile time instead of at migration time.
  *
  * An email is unique for a harder reason than a title is. `User.byEmail` takes the first row it
  * finds, so two rows sharing an address would make which account a password opens depend on the
  * order Postgres happened to return, and the index is what makes that unrepresentable rather than
  * unlikely. Adding this table is drift against an existing blog database, so `run sync --apply`
  * once before the application serves.
  */
object AppSchema extends Schema {
  val posts = table[Post].unique(_.title)
  val users = table[User].unique(_.email)
}
