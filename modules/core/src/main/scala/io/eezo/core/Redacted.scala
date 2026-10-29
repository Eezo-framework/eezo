package io.eezo.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Text an application must not leak, such as an API key, held so that the usual ways a value
  * escapes do not apply to it.
  *
  * Not a case class, because one would hand the text to anyone through `unapply`, `copy` and
  * `productElement`; and not a `String`, which `toString`, logging and interpolation show as it is.
  * `equals` compares in constant time, as the session's Secret does, so that checking a value an
  * attacker sent against one of these cannot be timed into revealing it.
  *
  * Unlike Secret, it has no length rule and no single use: a Secret is the one key a session is
  * signed with, and a Redacted is whatever the application itself must keep quiet.
  */
final class Redacted private (text: String) {

  /** The only way back to the text, named so that every place that needs it reads as a choice. */
  def reveal: String = text

  private def bytes: Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  override def equals(other: Any): Boolean = other match {
    case that: Redacted => MessageDigest.isEqual(bytes, that.bytes)
    case _              => false
  }

  /** From a digest of the text, not the text's own hash, which a short text can be read back from.
    */
  override def hashCode: Int =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getInt

  override def toString: String = "Redacted(redacted)"
}

object Redacted {

  /** Public, so that a handler under test is handed a case class built directly, with no map. */
  def apply(text: String): Redacted = new Redacted(text)

  /** Accepts any text, the empty text included: what a valid key looks like is the provider's
    * business, and a Parser that never refuses never has a reason that could repeat the text.
    */
  given Parser[Redacted] = text => Right(Redacted(text))
}
