import io.eezo.auth.Password
import io.eezo.config.Config
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact

import models.User
import shop.CreateUserConfig

/** How the shop gets its first user, with no sign up page anywhere: the operator's job, run with
  * `SHOP_EMAIL` and `SHOP_PASSWORD` set beside the Stripe variables, since boot reads the whole of
  * `application.conf` before the job runs.
  *
  * The password arrives already hashed: `Parser[Password]` hashes on read, so a text bcrypt cannot
  * read is refused by boot, at its path, before the job ever holds it.
  */
object CreateUser extends DbApp {

  override def schema: Schema = AppSchema

  /** Same Postgres schema as `Main`, so the account lands where the app reads it. */
  override def databaseSchema: String = "shop"

  override def boot(): Unit =
    account(Config[CreateUserConfig].current) match {
      case Right((email, hashed)) =>
        transact(Table[User].insert(User(Id.gen(), email, hashed, token = None)))
        println(s"created $email; sign in at /login")
      case Left(refused) =>
        refuse(refused)
        sys.exit(1)
    }

  /** Prints the lines a boot refusal prints, through the same `[eezo] ` door on stderr, so the
    * operator reads one shape whichever of the two refused. Kept apart from `boot`, which exits, so
    * a suite can hold what the job prints to the shape a boot refusal prints.
    */
  def refuse(refused: Config.Refused): Unit = refused.lines.foreach(fail(Nil, _))

  /** The account to create, or the refusal boot would print for a value that is missing.
    *
    * The section's fields are optional so that the web server, which reads the same file, boots
    * without them. The job is the one reader that needs both, so the refusal is made here, in the
    * words and the exit code of a boot refusal, and an operator reads one kind of line whichever of
    * the two refused.
    */
  def account(section: CreateUserConfig): Either[Config.Refused, (String, Password)] =
    (section.email, section.password) match {
      case (Some(email), Some(password)) => Right(email -> password)
      case (email, password)             =>
        Left(
          Config.Refused(
            Seq(
              Option.when(email.isEmpty)(missing("email", "SHOP_EMAIL")),
              Option.when(password.isEmpty)(missing("password", "SHOP_PASSWORD"))
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
