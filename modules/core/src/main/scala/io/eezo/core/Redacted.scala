package io.eezo.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Text an application must not leak, such as an API key.
  *
  * `toString` shows `Redacted(redacted)`, no pattern match or copy gives the text back, and
  * [[reveal]] is the one way to read it. `equals` compares in constant time.
  */
final class Redacted private (text: String) {

  /** The only way back to the text. */
  def reveal: String = text

  private def bytes: Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  override def equals(other: Any): Boolean = other match {
    case that: Redacted => MessageDigest.isEqual(bytes, that.bytes)
    case _              => false
  }

  /** From a digest of the text, not from the hash of the text itself. */
  override def hashCode: Int =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getInt

  override def toString: String = "Redacted(redacted)"
}

object Redacted {

  /** Wraps `text`. A test builds one directly, with no Parser. */
  def apply(text: String): Redacted = new Redacted(text)

  /** Accepts any text, the empty text included, and never refuses. */
  given Parser[Redacted] = text => Right(Redacted(text))
}
