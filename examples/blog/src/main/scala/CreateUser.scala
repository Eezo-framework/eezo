import java.sql.Connection

import io.eezo.auth.Password
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact
import io.eezo.http.Field

import models.User

/** How the blog gets its first user, with no registration page anywhere.
  *
  *   EEZO_BLOG_EMAIL=you@example.com EEZO_BLOG_PASSWORD=... sbt "blog/runMain CreateUser"
  *
  * A second `DbApp` rather than a command on `Main`, because `Main` is the application and this is
  * an operator's tool: the two have different lifetimes, and the one that inserts a user should not
  * be reachable from the one that serves the internet. It is also what lets `Main.scala` stay
  * exactly as it was.
  *
  * The credentials arrive in the environment rather than as arguments. A command line is visible to
  * every process on the machine through `ps` and is written to the shell's history file, and a
  * password that has been in either of those is a password that has to be changed.
  *
  * The connection settings below repeat `Main`'s. That duplication is real and is the cost of
  * leaving `Main.scala` untouched: `withDatabase` is `protected`, so a tool cannot borrow the
  * application's database, and `DbInit` is the seam both of them stand on instead.
  */
object CreateUser extends DbApp {

  override def schema: Schema = AppSchema

  override def databaseSchema: String = "blog"

  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try {
      st.execute("""create schema if not exists "blog"""")
      st.execute("""set search_path to "blog"""")
    } finally st.close()
  }

  override def boot(): Unit = {
    val email    = required("EEZO_BLOG_EMAIL")
    val password = required(PasswordVariable)

    // Hashed here, once, at the strength `Password.hash` fixes, and only through `Field[Password]`,
    // the same door a login form's own submission goes through. That is what makes the 72 byte
    // refusal run here too, so an operator sees "is longer than the 72 bytes bcrypt reads" rather
    // than the `IllegalArgumentException` spring-security-crypto throws three calls deeper. This is
    // the only place in the blog that ever holds the text, and it holds it for as long as one
    // bcrypt call takes.
    val hashed = hashPassword(password)

    transact {
      val users = Table[User]
      users.insert(User(Id.gen(), email, hashed))
    }

    println(s"created $email; sign in at /admin/login")
  }

  private inline val PasswordVariable = "EEZO_BLOG_PASSWORD"

  /** A missing variable stops the tool rather than creating a user nobody can sign in as. */
  private def required(name: String): String =
    sys.env.getOrElse(
      name,
      throw new IllegalStateException(
        s"$name is not set. Run: EEZO_BLOG_EMAIL=you@example.com $PasswordVariable=... " +
          "sbt \"blog/runMain CreateUser\""
      )
    )

  /** The one door from the environment's text to a stored hash, so a refusal an operator can act on
    * comes from eezo, not from three stack frames inside spring-security-crypto. `Field[Password]`
    * is the same check a login form's password box already runs, so this tool cannot accept a text
    * a login page would refuse to have ever hashed in the first place.
    *
    * Not private, for the one test that pins the refusal on the byte cap: that test cannot set
    * `EEZO_BLOG_PASSWORD` on a running JVM, so it has to reach this door directly rather than
    * through [[boot]].
    */
  def hashPassword(text: String): Password =
    Field[Password].read(text).fold(
      message => throw new IllegalStateException(s"$PasswordVariable $message"),
      identity
    )
}
