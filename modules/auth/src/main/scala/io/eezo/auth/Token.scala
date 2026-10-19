package io.eezo.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, HexFormat}

/** The stored hash of an API token: what an application keeps of a secret it handed a program once,
  * and the value a guard looks the program's user up by.
  *
  * Opaque over `String` for [[Password]]'s reason, so `Column[String].imap(Token.stored)(_.value)`
  * is the whole of its storage and a `String` read out of a row cannot pass for one a program sent.
  *
  * Unsalted and fast where a [[Password]] is salted and slow, because the two are asked different
  * questions. A password is verified against the one row an email names, so its hash may differ on
  * every write; a token arrives with nothing beside it, so the only way to its row is to hash it
  * the same way twice and look the hash up. What makes that safe is [[gen]], not the hash: a person
  * picks a password from a space an attacker can walk, and nobody picks a token, so a leaked table
  * of `SHA-256` hashes over 256 random bits gives an attacker nothing to walk.
  */
opaque type Token = String

object Token {

  /** How many random bytes a plain token is made of: twice the 128 bit floor, because the cost is a
    * few characters in a header and the margin is the whole of what keeps a fast hash safe.
    */
  private val Bytes: Int = 32

  private val random: SecureRandom = new SecureRandom()

  /** A fresh token: the plain text to show its program once, and the hash to store.
    *
    * The pair rather than the plain text alone, so that an application never has to remember to
    * hash before it stores: the value it writes is the one it was handed for writing. The plain
    * text is base64url without padding, the one alphabet that goes into a header, a URL and a shell
    * command without escaping.
    */
  def gen(): (String, Token) = {
    val bytes = new Array[Byte](Bytes)
    random.nextBytes(bytes)
    val plain = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    (plain, hash(plain))
  }

  /** The hash of a plain token, the same every time, which is what lets an application find the row
    * a program's token names by this value alone.
    */
  def hash(plain: String): Token =
    HexFormat
      .of()
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8))
      )

  /** A hash already made, as read out of a row. */
  def stored(hash: String): Token = hash

  extension (token: Token) {

    /** The hash as text, for the `Column[Token]` an application writes beside its model. */
    def value: String = token
  }
}
