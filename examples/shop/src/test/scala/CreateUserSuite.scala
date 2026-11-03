import io.eezo.auth.Password
import io.eezo.config.Config

import shop.CreateUserConfig

/** The operator's job refuses to create an account it was not given, with the lines boot prints for
  * a value that is missing, and not with an exception.
  */
class CreateUserSuite extends munit.FunSuite {

  test("an absent email and password are each refused at their path, beside their variable") {
    val refused = CreateUser.account(CreateUserConfig(email = None, password = None))
    assertEquals(
      refused.left.map(_.lines),
      Left(
        Seq(
          "createUser.email is missing: set SHOP_EMAIL, which application.conf names for it",
          "createUser.password is missing: set SHOP_PASSWORD, which application.conf names for it"
        )
      )
    )
  }

  test("an email and a password are the account the job creates") {
    val hashed = Password.stored("$2b$04$abcdefghijklmnopqrstuuJ0xK7zXbqP9fW4f7Zl4h8n2WvYd3s2e")
    assertEquals(
      CreateUser.account(CreateUserConfig(Some("you@example.com"), Some(hashed))),
      Right("you@example.com" -> hashed)
    )
  }

  test("a refusal is a Config.Refused, the value boot prints its own refusals from") {
    val refused = CreateUser.account(CreateUserConfig(Some("you@example.com"), None))
    assertEquals(
      refused,
      Left(
        Config.Refused(Seq(Config.Offender.Missing("createUser.password", List("SHOP_PASSWORD"))))
      )
    )
  }

  test("the job prints each refusal line on stderr behind the prefix the README shows") {
    val err = new java.io.ByteArrayOutputStream
    Console.withErr(err) {
      CreateUser.account(CreateUserConfig(None, None)).left.foreach(CreateUser.refuse)
    }
    assertEquals(
      err.toString("UTF-8"),
      "[eezo] createUser.email is missing: set SHOP_EMAIL, which application.conf names for it\n" +
        "[eezo] createUser.password is missing: set SHOP_PASSWORD, which application.conf names for it\n"
    )
  }
}
