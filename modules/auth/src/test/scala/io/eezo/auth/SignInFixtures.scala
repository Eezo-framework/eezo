package io.eezo.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import io.eezo.core.Id
import io.eezo.http.Session

/** The moment the auth suites are fixed at, and the sessions a signed in browser carries.
  *
  * Kept in one place because a guard and an ownership declaration are read the same way: both take
  * the user out of the session and both refuse a sign in the stamp says is too old. Two suites
  * keeping their own reading of the clock is two suites that can disagree about what expired means.
  */
object SignInFixtures {

  /** The one moment every guard in these suites is fixed at, so a test that moves time moves it in
    * the session's stamp rather than in the clock. A fixed clock is what keeps the suites free of
    * order dependence: no guard here holds a reading another test can have already advanced.
    */
  val now: Instant = Instant.parse("2026-09-18T12:00:00Z")

  val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

  /** A sign in a second too old to count against the default lifetime. */
  val stale: Instant = now.minus(Guard.DefaultLifetime).minusSeconds(1)

  /** The session of a browser that named `who` and signed in at `since`, which defaults to the
    * reading every guard here is fixed at, so a caller with nothing to say about time reads as
    * signed in just now.
    */
  def signedInSession[T](who: Id[T], since: Instant = now): Session =
    Session.empty
      .withReserved(Guard.UserEntry, who.show)
      .withReserved(Guard.StampEntry, Guard.stamped(since))

  /** The session of a browser that named `who` and carries no stamp beside it: the shape every
    * session signed before the lifetime shipped has, and the one a forged escalation would most
    * like.
    */
  def stamplessSession[T](who: Id[T]): Session =
    Session.empty.withReserved(Guard.UserEntry, who.show)
}
