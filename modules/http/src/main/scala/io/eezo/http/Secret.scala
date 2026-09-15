package io.eezo.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The application secret: the key the session cookie is signed with.
  *
  * One value, held once, read from `EEZO_SECRET` by default and overridden on `HttpApp` beside
  * `port`. A class rather than a `String` so that it cannot be logged, compared with `==` on a
  * clock, or handed where text is expected by mistake: `toString` redacts, `equals` compares in
  * constant time, and the bytes are visible to the signer alone.
  *
  * Signing is HMAC-SHA256, which every JDK is required to ship. A fresh `Mac` per call, because
  * `Mac` is not thread safe and a handler runs on whichever virtual thread took the request.
  */
final class Secret private (private val bytes: Array[Byte]) {

  private val key = new SecretKeySpec(bytes, Secret.Algorithm)

  private[http] def sign(payload: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance(Secret.Algorithm)
    mac.init(key)
    mac.doFinal(payload)
  }

  override def equals(other: Any): Boolean = other match {
    case that: Secret => MessageDigest.isEqual(bytes, that.bytes)
    case _            => false
  }

  override def hashCode: Int = Arrays.hashCode(bytes)

  override def toString: String = "Secret(redacted)"
}

object Secret {

  private val Algorithm = "HmacSHA256"

  /** The variable the default reads: `EEZO_SECRET`, beside `EEZO_DB_URL`. */
  val EnvVar: String = "EEZO_SECRET"

  /** The shortest key accepted, in bytes: the length of an HMAC-SHA256 tag, which RFC 2104 names as
    * the floor, and the length of a throwaway.
    */
  private val MinBytes = 32

  /** A secret from configured text, its UTF-8 bytes as the key. Fewer than 32 bytes is refused,
    * empty text included: HMAC would accept any of them, but a short key can be guessed, and a
    * guessed key signs a session anyone can forge. Bytes, not characters, because the bytes are the
    * key.
    */
  def parse(text: String): Secret = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    require(
      bytes.length >= MinBytes,
      s"$EnvVar must be at least $MinBytes bytes, and is ${bytes.length}; make one with " +
        "`openssl rand -base64 32`"
    )
    new Secret(bytes)
  }

  /** A throwaway: 32 bytes from `SecureRandom`, which reads `/dev/urandom` and never blocks. Not
    * `getInstanceStrong`, which can stall a boot on a fresh machine waiting for entropy.
    */
  def throwaway(): Secret = {
    val bytes = new Array[Byte](MinBytes)
    new SecureRandom().nextBytes(bytes)
    new Secret(bytes)
  }

  /** What `HttpApp.secret` does by default: [[EnvVar]] when it is set, and a throwaway otherwise,
    * so that `hello` runs with no configuration. Under the sbt plugin's dev loop [[EnvVar]] is
    * always set, because the plugin generates one secret per sbt session and hands it to every
    * restart, so sessions survive an edit; the throwaway is what a run outside that loop gets. It
    * is announced once, at WARNING, because it is the right default for trying an application and
    * the wrong one for a deployment: every session ends with the process, and two instances cannot
    * read each other's cookies.
    */
  def fromEnv(env: Map[String, String] = sys.env): Secret =
    env.get(EnvVar).map(parse).getOrElse {
      Eezo.log.log(
        System.Logger.Level.WARNING,
        s"$EnvVar is not set; a throwaway secret was generated, so every session ends when this " +
          s"process does. Set $EnvVar in production."
      )
      throwaway()
    }
}
