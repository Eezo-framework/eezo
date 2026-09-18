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

    /** Who is asking. Supplied by the guard that made this declaration, and read on every covered
      * request rather than once, because a process serves many browsers.
      */
    val owner: Request => Id[U],

    /** Which of the seven read and write through the owner's rows alone. */
    val covers: Set[Action],
    actions: Set[Action],
    through: Route => Route,
    carries: Seq[Route]
) extends Guarded[A](actions, through, carries)

object Owned {

  /** An ownership declaration refining one that already says who has to be signed in.
    *
    * The three inherited fields are taken from `guarded` rather than restated, so the guard that
    * produced it is still the one wrapping the routes and still the one whose login page travels
    * with them. A declaration built any other way would mount a login page nobody redirects to.
    */
  def apply[A, U](
      ownerOf: OwnerOf[A, Id[U]],
      owner: Request => Id[U],
      covers: Set[Action],
      guarded: Guarded[A]
  ): Owned[A, U] =
    new Owned[A, U](ownerOf, owner, covers, guarded.actions, guarded.through, guarded.carries)
}
