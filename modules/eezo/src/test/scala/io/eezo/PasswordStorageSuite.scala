package io.eezo

import io.eezo.auth.Password
import io.eezo.core.Id
import io.eezo.db.Column
import io.eezo.db.Table

/** Where `Password` meets storage, which is neither module's job and so is nobody's test but this
  * one's.
  *
  * `auth` depends on `core` and `http` and never on `db`, and `db` has never heard of bcrypt. The
  * umbrella is the first place both types are visible at once, which is what makes it the only
  * place these assertions can be written.
  */
class PasswordStorageSuite extends munit.FunSuite {

  case class Account(id: Id[Account], email: String, password: Password) derives Table

  object Account {

    /** The one line an application writes, quoted from `examples/blog`'s `User`. */
    given Column[Password] = Column[String].imap(Password.stored)(_.value)
  }

  test("a model storing a Password derives a Table through the application's own mapping") {
    assertEquals(Table[Account].cols.password.name, "password")
  }

  test("without the mapping the macro names Password at the field that needs it") {
    val failure = compileErrors(
      """
      case class NoMapping(id: io.eezo.core.Id[NoMapping], password: io.eezo.auth.Password)
          derives io.eezo.db.Table
      """
    )
    assert(clue(failure).contains("No database mapping for"), failure)
    assert(clue(failure).contains("io.eezo.auth.Password"), failure)
    assert(clue(failure).contains("at field `password`"), failure)
  }

  test("a plain password cannot be stored at all, because nothing anywhere maps one") {
    // The type is the guarantee that a password is never written as typed. `auth` ships no
    // `Column[Plain]` and `db` cannot see the type to ship one, so this is a compile error rather
    // than a rule somebody has to remember.
    val failure = compileErrors(
      """
      case class Stores(id: io.eezo.core.Id[Stores], password: io.eezo.auth.Password.Plain)
          derives io.eezo.db.Table
      """
    )
    assert(clue(failure).contains("No database mapping for"), failure)
    assert(clue(failure).contains("Plain"), failure)
  }
}
