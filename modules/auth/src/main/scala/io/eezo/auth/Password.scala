package io.eezo.auth

import java.nio.charset.StandardCharsets

import org.springframework.security.crypto.bcrypt.BCrypt

import io.eezo.http.Field

/** The stored hash of a user's password: one self describing string that names its own algorithm
  * and its own cost, and the only form of a password a model carries or a table stores.
  *
  * Opaque over `String` rather than a class, because the hash is what goes in the column and
  * `Column[String].imap(Password.stored)(_.value)` is the whole of its storage. It is not a secret
  * from the database that holds it, so nothing here redacts; [[Password.Plain]] is the value that
  * does.
  *
  * Being opaque is what keeps the two directions apart. A `String` read out of a row is a hash and
  * becomes one through [[Password.stored]]; a `String` typed into a browser is not, and the only
  * door from there is [[Password.hash]] or the [[Field]] below, both of which hash. Nothing in
  * between can pass one for the other by having the same underlying type.
  */
opaque type Password = String

object Password {

  /** The bcrypt cost, stated once and reachable from nowhere.
    *
    * No parameter, no configuration key and no overload takes it, because a cost is a decision
    * about the next ten years of an application's password file and not about one call. A knob here
    * would be read once, at the first `hash`, by whoever was writing that line, and every hash
    * after it would inherit whatever they picked. 12 is the OWASP floor at the time of writing and
    * is roughly a quarter of a second on the machine this was written on, which is the cost a login
    * is meant to pay and a password file is meant to make an attacker pay a few billion times over.
    */
  private val Strength: Int = 12

  /** The longest text bcrypt reads, in bytes.
    *
    * bcrypt's own limit, not eezo's: the algorithm reads 72 bytes of key and stops. Stated here so
    * that [[Field.read]] refuses a longer text with a message about the value, rather than letting
    * spring-security-crypto throw an `IllegalArgumentException` that reaches the browser as a 500.
    * Truncating instead would be worse than either: two different passwords that share a 72 byte
    * prefix would both open the account, in silence.
    */
  private val MaxBytes: Int = 72

  /** `$2b`, not the `$2a` that `BCrypt.gensalt()` still defaults to. `$2a` is the revision whose
    * sign extension bug on non-ASCII input was the reason `$2b` exists, and a hash carries the
    * revision it was made under forever, so picking it here means a hash eezo writes today cannot
    * be the one that has to be migrated later.
    *
    * `BCrypt` rather than spring-security-crypto's own `BCryptPasswordEncoder`, which is the class
    * this would otherwise be written against: that encoder's constructor builds a commons-logging
    * `Log`, and commons-logging is not in spring-security-crypto's POM. Using it would mean either
    * a `NoClassDefFoundError` at the first hash or a second dependency on the classpath of every
    * application with a login page, to carry one warning eezo does not want anyway.
    */
  private val Version: String = "$2b"

  /** What a login form decodes to: the text as typed, alive only until it is hashed or verified.
    *
    * A class whose `toString` redacts, for the reason `Secret`'s is one: the failure this type
    * exists to prevent is the plain text reaching a log, and a log line is written by interpolating
    * whatever is in scope. An opaque type over `String` cannot defend against that, since
    * `toString` is a member of `Any` and an extension method never wins against one, so the
    * redaction would be a comment rather than a guarantee.
    *
    * There is no way back to the text from outside this module, which is what makes storing one
    * impossible rather than merely discouraged.
    */
  final class Plain private[auth] (private[auth] val text: String) {

    /** Redacted, so that a `Plain` interpolated into a log line or printed by a debugger says
      * nothing. The stars are a fixed width rather than the value's, because a length is a hint.
      */
    override def toString: String = "****"
  }

  object Plain {

    def apply(text: String): Plain = new Plain(text)

    /** The text as typed, unhashed, and never longer than bcrypt reads.
      *
      * The 72 byte refusal lives on this side as well as on [[Password]]'s, because a login form
      * decodes to a `Plain` and a text bcrypt cannot read cannot match any stored hash. Refusing it
      * here says so, where letting it through would answer "wrong password" to a user whose
      * password is right and merely long.
      */
    given Field[Plain] = field(Plain.apply)
  }

  /** What both fields share: a password box, so a browser never paints the characters and a
    * password manager recognises it, a `show` that is empty for every value, and [[check]].
    */
  private def field[A](make: String => A): Field[A] =
    Field.of[A](Field.PasswordInput)(_ => "")(check(_).map(make))

  /** The one refusal both fields share, so that a length the storage side rejects and a length the
    * login side accepts cannot drift apart.
    *
    * Bytes, not characters. A cap written against `String.length` passes every ASCII test and then
    * silently rejects a password of 60 emoji, or, worse, accepts one bcrypt will truncate.
    */
  private def check(text: String): Either[String, String] =
    if (text.isEmpty) Left("is required")
    else {
      val bytes = text.getBytes(StandardCharsets.UTF_8).length
      if (bytes > MaxBytes) Left(s"is longer than the $MaxBytes bytes bcrypt reads")
      else Right(text)
    }

  /** The hash of a plain text, at [[Strength]], under a fresh salt. */
  def hash(plain: Plain): Password = BCrypt.hashpw(plain.text, BCrypt.gensalt(Version, Strength))

  /** A hash already made, as read out of a row or written into a seed script. Takes the string at
    * its word: what algorithm and cost it names is what [[verify]] will use, which is what lets a
    * test seed a cheap hash and an application store an expensive one.
    */
  def stored(hash: String): Password = hash

  extension (password: Password) {

    /** The hash as text, for the `Column[Password]` an application writes beside its model. */
    def value: String = password

    /** Whether `plain` is the text this hash was made from.
      *
      * Reads the algorithm and cost out of the stored string rather than assuming [[Strength]], so
      * a hash written before the cost moved still verifies. Nothing rehashes on a successful login
      * yet: that needs a second cost in the wild to be worth the write on every sign in.
      *
      * A stored string that is not a bcrypt hash throws rather than answering `false`. That is a
      * column holding something eezo did not write, and it is worth a 500 on one login: answering
      * `false` would read as a wrong password forever, and nothing anywhere would say why.
      */
    def verify(plain: Plain): Boolean = BCrypt.checkpw(plain.text, password)
  }

  /** The stored hash as a form field.
    *
    * `show` is empty for every value, a real hash included, so no page ever paints one: a hash is
    * not a secret from the database, but it is an offline attack for anyone who reads the page it
    * was rendered into. `read` hashes, which is what puts the hashing between the browser and the
    * case class rather than in a handler somebody has to remember to write.
    */
  given Field[Password] = field(text => hash(Plain(text)))
}
