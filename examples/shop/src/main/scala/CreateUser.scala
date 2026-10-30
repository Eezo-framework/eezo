import io.eezo.auth.Password
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact

import models.User

import config.{Configured, Current, Ops}

object CreateUser extends DbApp with Configured[Ops] {

  override def schema: Schema = AppSchema

  /** Same Postgres schema as `Main`, so the account lands where the app reads it. */
  override def databaseSchema: String = "shop"

  /** SPIKE, seam B: the job's two variables are a Config too, read by the same `Configured` before
    * `boot`, the password hashed by `Parser[Password]` on the way in.
    */
  override def boot(): Unit = {
    val ops = Current[Ops]
    transact(Table[User].insert(User(Id.gen(), ops.shopEmail, ops.shopPassword, token = None)))
    println(s"created ${ops.shopEmail}; sign in at /login")
  }
}
