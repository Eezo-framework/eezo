import io.eezo.db.Schema

import models.User

object AppSchema extends Schema {
  val users = table[User].unique(_.email)
}
