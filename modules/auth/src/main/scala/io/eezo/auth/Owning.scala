package io.eezo.auth

import io.eezo.core.{Id, OwnerOf}
import io.eezo.http.{Action, Guarded, Owned, Request, Route}

/** What a guard's `required`, `only` and `except` answer: the declaration a model writes, still
  * holding the guard that made it.
  *
  * A [[io.eezo.http.Guarded]] and nothing more as far as every existing call site can tell, which
  * is why `given Guarded[Post] = User.guard.required` still says exactly what it said. The one
  * thing it adds is [[owning]], and it can add it only because it still knows who the guard says is
  * behind a request, its [[currentUser]]: a plain `Guarded` has a `Route => Route` and no way back
  * to the user, so ownership could not be chained onto one.
  *
  * @param currentUser
  *   who is signed in, as the key that a row's owner field holds, and nobody when the request
  *   carries no live sign in. Read out of the session rather than through the guard's `find`,
  *   because a covered route is a guarded route and the wrapper already looked the user up before
  *   the handler ran; a second lookup per request would buy nothing.
  * @param identify
  *   the guard's own stamp, handed on to `Guarded` and read by nothing here. It is the same
  *   function the guard's wrapper puts on the pages it lets through, so what a declaration hands
  *   the route table and what a guarded page is handed are one rule rather than two.
  */
final class GuardedBy[A, U] private[auth] (
    actions: Set[Action],
    through: Route => Route,
    carries: Seq[Route],
    val currentUser: Request => Option[Id[U]],
    identify: Request => Request
) extends Guarded[A](actions, through, carries, identify) {

  /** Which field of the model records its owner, written as a selector so the compiler checks it.
    *
    * `inline`, because the field's **name** is what `db` renders into the owner condition of four
    * statements, and the only place that name exists as something a compiler can check is the
    * selector's own tree. Read at compile time it is the model's field; typed by hand it is a
    * string that is right until somebody renames the field.
    *
    * The body calls [[owningField]] and reaches nothing private, which is what an inline method
    * expanded in a user's companion object is allowed to do.
    */
  inline def owning(inline selector: A => Id[U]): Owning[A, U] =
    owningField(Selector.nameOf(selector), selector)

  /** [[owning]]'s body, after the selector has given up its name.
    *
    * Public only because an inline method's expansion has to be able to call it from wherever the
    * declaration is written. A caller who writes the name by hand gets what they asked for, which
    * is the thing [[owning]] exists to make unnecessary.
    */
  def owningField(name: String, get: A => Id[U]): Owning[A, U] =
    new Owning[A, U](OwnerOf(name, get), currentUser, this)
}

/** An ownership declaration waiting to be told which routes it covers.
  *
  * The chain ends in one of three, and the three read as what they are: `all` for a model nobody
  * but its owner may even see, `except` for one whose reading routes are everybody's, `only` for
  * the odd shape neither of those describes. There is no default, because "which routes does owning
  * a row decide" has no safe silence: covering everything makes a blog's index private, and
  * covering nothing makes the declaration do nothing at all.
  *
  * These are not [[io.eezo.http.Guarded]]'s `only` and `except` under another name, and the
  * difference is worth saying twice. There the question is who has to be signed in. Here every
  * covered route already needs a signed in user, and the question is which of them are narrowed to
  * that user's own rows.
  */
final class Owning[A, U] private[auth] (
    ownerOf: OwnerOf[A, Id[U]],
    currentUser: Request => Option[Id[U]],
    guarded: Guarded[A]
) {

  /** Every route reads and writes this user's rows alone. A cart, a draft, a private note. */
  def all: Owned[A, U] = covering(Action.values.toSet)

  /** Exactly these routes are the owner's; the rest read the whole table. */
  def only(actions: Action*): Owned[A, U] = covering(actions.toSet)

  /** Everything but these is the owner's, which is the blog's shape: everybody reads, each edits
    * their own.
    */
  def except(actions: Action*): Owned[A, U] = covering(Action.values.toSet -- actions)

  private def covering(covers: Set[Action]): Owned[A, U] =
    Owned(ownerOf, currentUser, covers, guarded)
}
