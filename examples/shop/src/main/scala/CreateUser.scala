import io.eezo.auth.Password
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact
import io.eezo.http.Field

import models.User

object CreateUser extends DbApp {

  override def schema: Schema = AppSchema

  /** Same Postgres schema as `Main`, so the account lands where the app reads it. */
  override def databaseSchema: String = "shop"

  override def boot(): Unit = {
    val email  = sys.env("SHOP_EMAIL")
    val hashed = Field[Password]
      .read(sys.env("SHOP_PASSWORD"))
      .fold(message => throw new IllegalStateException(s"SHOP_PASSWORD $message"), identity)
    transact(Table[User].insert(User(Id.gen(), email, hashed, token = None)))
    println(s"created $email; sign in at /login")
  }
}
