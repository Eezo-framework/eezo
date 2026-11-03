import io.eezo.auth.Password
import io.eezo.config.Config
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact

import blog.CreateUserConfig
import models.User

/** How the blog gets its first user, with no registration page anywhere.
  *
  * printf 'password: '; IFS= read -rs BLOG_PASSWORD; export BLOG_PASSWORD
  * BLOG_EMAIL=you@example.com sbt "blog/runMain CreateUser" unset BLOG_PASSWORD
  *
  * A second `DbApp` rather than a command on `Main`, because `Main` is the application and this is
  * an operator's tool: the two have different lifetimes, and the one that inserts a user should not
  * be reachable from the one that serves the internet. It is also what lets `Main.scala` stay
  * exactly as it was.
  *
  * The email and password arrive in the environment rather than as arguments, which keeps the
  * secret out of the argument list that `ps` shows to every other process on the machine. The
  * password itself comes from a prompt that does not echo, not from a literal in the command, since
  * a shell appends the whole command line, secret included, to its history file; a password that
  * has been in either place is a password that has to be changed.
  *
  * Both variables used to carry `EEZO_` in front of their names. `application.conf` may not feed a
  * value from a variable under `EEZO_`, the framework's prefix, so they lost it.
  *
  * The connection settings below repeat `Main`'s. That duplication is real and is the cost of
  * leaving `Main.scala` untouched: `withDatabase` is `protected`, so a tool cannot borrow the
  * application's database, and `DbInit` is the seam both of them stand on instead.
  */
object CreateUser extends DbApp {

  override def schema: Schema = AppSchema

  override def databaseSchema: String = "blog"

  override def boot(): Unit =
    account(Config[CreateUserConfig].current) match {
      case Right((email, hashed)) =>
        transact {
          val users = Table[User]
          users.insert(User(Id.gen(), email, hashed))
        }
        println(s"created $email; sign in at /admin/login")
      case Left(refused) =>
        refuse(refused)
        sys.exit(1)
    }

  /** Prints the lines a boot refusal prints, through the same `[eezo] ` door on stderr, so the
    * operator reads one shape whichever of the two refused. Kept apart from `boot`, which exits, so
    * a suite can hold the README's line to what the job prints.
    */
  def refuse(refused: Config.Refused): Unit = refused.lines.foreach(fail(Nil, _))

  /** The account to create, or the refusal boot would print for a value that is missing.
    *
    * The password arrives hashed, at the strength `Password.hash` fixes: `Parser[Password]` is the
    * same door a login form's own submission goes through, and it hashes on read. That is what
    * makes the 72 byte refusal run here too, at boot and at the password's path, so an operator
    * sees "is longer than the 72 bytes bcrypt reads" rather than the `IllegalArgumentException`
    * spring-security-crypto throws three calls deeper.
    *
    * The fields are optional so that the server, which reads the same file, boots without them. The
    * job is the one reader that needs both, so it refuses here, in the words and the exit code of a
    * boot refusal, rather than creating a user nobody can sign in as.
    */
  def account(section: CreateUserConfig): Either[Config.Refused, (String, Password)] =
    (section.email, section.password) match {
      case (Some(email), Some(password)) => Right(email -> password)
      case (email, password)             =>
        Left(
          Config.Refused(
            Seq(
              Option.when(email.isEmpty)(missing("email", "BLOG_EMAIL")),
              Option.when(password.isEmpty)(missing("password", "BLOG_PASSWORD"))
            ).flatten
          )
        )
    }

  /** The path is the one `application.conf` writes the field under, and the variable the one it
    * feeds the field from, so the line names what the operator has to set.
    */
  private def missing(field: String, variable: String): Config.Offender =
    Config.Offender.Missing(s"createUser.$field", List(variable))
}
