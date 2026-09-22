package io.eezo.http

import io.eezo.core.{Id, OwnerOf}

/** The declaration, made beside a model, that a row belongs to the user who created it and that
  * some of its routes are that user's alone.
  *
  * A [[Guarded]] and not a sibling of one, because owned implies guarded and never the reverse: the
  * routes covered here are routes whose handler asks who is signed in, and a route that refuses
  * nobody has nobody to compare a row's owner with. Being a subtype is also what keeps the route
  * table's guard lookup and its `mounting` exactly as they were, and what lets a model declare
  * `given Owned[Post, User]` and nothing else.
  *
  * Data, like [[Guarded]], and for the same reason: `modules/auth` ships one way of producing one
  * and any other way has to produce one of these and nothing more.
  *
  * [[covers]] is a second set beside the inherited `actions`, and the two are genuinely different
  * questions. `actions` is which routes need a signed in user; `covers` is which of them read and
  * write through that user's rows alone. A blog guards all seven and scopes five of them, so that
  * everybody reads everybody's posts and nobody edits another's; a cart guards and scopes the same
  * seven, so that a cart is invisible to anyone but its owner. Folding them into one set would make
  * the blog's index private and the cart's enumerable, depending on which reading won.
  *
  * @tparam U
  *   the application's own user model, which appears only as the type of a key. Nothing here reads
  *   a user, and that is what keeps `http` free of a guard.
  */
final class Owned[A, U](
    /** Which field of the model records the owner, by name for storage and by getter for a
      * comparison.
      */
    val ownerOf: OwnerOf[A, Id[U]],

    /** The current user, as the key a row's owner field holds: who the guard says is behind this
      * request, and never the owner of any particular row. Supplied by the guard that made this
      * declaration, and read on every covered request rather than once, because a process serves
      * many browsers.
      *
      * `None` when nobody is signed in, because this is also asked about requests nothing vouched
      * for: a public show page reads it to decide whether to offer the owner's controls, and an
      * anonymous browser there is an ordinary visitor rather than a failure. Where a signed in user
      * is the precondition, inside a covered handler, the caller turns the `None` into the mistake
      * it is; answering with an exception here would make the public page pay a `catch` for the
      * covered one's rule.
      */
    val currentUser: Request => Option[Id[U]],

    /** Which of the seven read and write through the current user's own rows alone. */
    val covers: Set[Action],

    /** The declaration this one refines, which already says who has to be signed in. Its fields are
      * taken rather than restated, so the guard that produced it is still the one wrapping the
      * routes, still the one whose login page travels with them, and still the one naming the
      * current user. There is no way to build an `Owned` that mounts a login page nobody redirects
      * to, and none that is guarded by one thing and named by another.
      */
    guarded: Guarded[A]
) extends Guarded[A](guarded.actions, guarded.through, guarded.carries, guarded.identify)
