import com.typesafe.config.ConfigFactory

import io.eezo.auth.Password
import io.eezo.config.Config

import blog.{BlogConfig, CreateUserConfig}

/** Pins what the operator's tool has to keep: a password that spring-security-crypto would refuse
  * is refused by eezo's own message, at its path in `application.conf`, before that library ever
  * sees the text, and an account nobody gave is refused rather than created.
  *
  * Reads the blog's own `application.conf` against an environment map of the suite's, the read boot
  * makes before the job runs, so no variable has to be set on the running JVM.
  */
class CreateUserSuite extends munit.FunSuite {

  private lazy val file = ConfigFactory.parseURL(getClass.getResource("/application.conf"))

  private def read(password: String): Either[Config.Refused, BlogConfig] =
    Config[BlogConfig].read(
      file,
      Map("BLOG_EMAIL" -> "you@example.com", "BLOG_PASSWORD" -> password)
    )

  test("a password over the 72 byte cap is refused at its path with the password's message") {
    assertEquals(
      read("e" * 73).left.map(_.lines),
      Left(Seq("createUser.password is longer than the 72 bytes bcrypt reads"))
    )
  }

  test("a password of exactly 72 UTF-8 bytes hashes rather than refuses") {
    val seventyTwo = "e" * 72
    val stored     = read(seventyTwo).toOption.flatMap(_.createUser.password)
    assert(stored.exists(_.verify(Password.Plain(seventyTwo))))
  }

  test("an empty password is refused by the job as missing, not hashed as an empty string") {
    val section = read("").map(_.createUser)
    assertEquals(
      section.flatMap(CreateUser.account).left.map(_.lines),
      Left(
        Seq(
          "createUser.password is missing: set BLOG_PASSWORD, which application.conf names for it"
        )
      )
    )
  }

  test("an absent email and password are each refused beside the variable that feeds them") {
    val section = Config[BlogConfig].read(file, Map.empty).map(_.createUser)
    assertEquals(
      section.flatMap(CreateUser.account).left.map(_.lines),
      Left(
        Seq(
          "createUser.email is missing: set BLOG_EMAIL, which application.conf names for it",
          "createUser.password is missing: set BLOG_PASSWORD, which application.conf names for it"
        )
      )
    )
  }

  test("a password within the cap is the hash the job stores, and only its own text verifies") {
    val account = read("correct horse battery staple").map(_.createUser).flatMap(CreateUser.account)
    val stored  = account.map(_._2).getOrElse(fail(s"expected an account, got $account"))
    assert(stored.verify(Password.Plain("correct horse battery staple")))
    assert(!stored.verify(Password.Plain("wrong")))
  }

  test("the job prints each refusal line on stderr behind the prefix the README shows") {
    val err = new java.io.ByteArrayOutputStream
    Console.withErr(err) {
      CreateUser.account(CreateUserConfig(None, None)).left.foreach(CreateUser.refuse)
    }
    assertEquals(
      err.toString("UTF-8"),
      "[eezo] createUser.email is missing: set BLOG_EMAIL, which application.conf names for it\n" +
        "[eezo] createUser.password is missing: set BLOG_PASSWORD, which application.conf names for it\n"
    )
  }
}
