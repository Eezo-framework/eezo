import io.eezo.db.Schema

import models.Reminder

/** The application's schema: which models get tables, plus what the types alone cannot say. The job
  * reads by `dueOn`, so that column gets an index; the selector is typed, so renaming the field
  * breaks the index at compile time instead of at migration time.
  */
object AppSchema extends Schema {
  val reminders = table[Reminder].index(_.dueOn)
}
