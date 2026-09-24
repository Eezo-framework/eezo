package io.eezo.http

import io.eezo.core.{Id, Store}

/** Everything that depends on what the model **declared**, built once per [[Resource.routes]] call
  * rather than once per derivation.
  *
  * The declaration is where ownership arrives, and ownership changes three things at once: which
  * store a handler reaches for, which fields the form has, and what a row somebody else owns
  * answers. All three are decided here and read by the handlers `Resource` mounts, so no handler
  * branches on whether the model is owned and the unowned path is the one it always was.
  *
  * Built at the same call that builds those handlers and before any of them exists, so the three
  * refusals below fire when the route table is assembled at boot rather than on the first request.
  */
private[http] final class Ownership[A](
    modelName: String,
    declared: Form[A],
    actions: Actions[A],
    store: Store[A],
    guarded: Guarded[A]
) {

  /** The declaration, if it says anything about ownership.
    *
    * Matched rather than summoned, because [[Owned]] **is** a [[Guarded]]: the route table's lookup
    * finds one declaration and hands it over, and this is where its extra half is read. The owner's
    * own type is gone by then, which is what [[Scoped]] exists to absorb; `Any` is as much as this
    * side can say and as much as it needs, since the only value that ever reaches `Scoped.by` is
    * the one `currentUser` produced.
    */
  private val owned: Option[Owned[A, Any]] = guarded match {
    case o: Owned[?, ?] => Some(o.asInstanceOf[Owned[A, Any]])
    case _              => None
  }

  private val scope: Option[Scoped[A]] = store match {
    case s: Scoped[?] => Some(s.asInstanceOf[Scoped[A]])
    case _            => None
  }

  /** An owned model on a store that cannot narrow would serve every covered route off the whole
    * table, which is the one failure ownership exists to prevent, and it would do it silently. It
    * is refused where the route table is built rather than per request, the same way a `TableDef`
    * with no key column is: a declaration and a store that do not match is a mistake in the table,
    * and the table is built once, at boot.
    */
  if (owned.isDefined && scope.isEmpty)
    throw new IllegalStateException(
      s"$modelName declares an Owned, so its routes need a store that can narrow to one " +
        "owner. The generated route table builds one; a hand-written mount has to pass " +
        "InMemoryStore.scoped, or JdbcStore's owner aware pair."
    )

  /** A mistyped owner name would otherwise leave the field it meant to hide sitting on the page as
    * an ordinary, browser-editable input: `Form.without` documents that a name naming no field
    * changes nothing, so `shape` below would be `declared` itself and `body` would write under a
    * key `parse` never reads, which is the forgery this whole mechanism exists to close. Refused
    * here, beside the `Scoped` refusal above and at the same boot, rather than discovered from a
    * hostile submission that a mistyped name would otherwise let through.
    */
  owned.foreach { o =>
    if (!declared.fields.exists(_.name == o.ownerOf.name))
      throw new IllegalStateException(
        s"""$modelName declares Owned naming "${o.ownerOf.name}" as its owner, but $modelName
           |has no field of that name for the form to hide. Name the field the declaration
           |actually owns.""".stripMargin.replace("\n", " ")
      )
  }

  /** Owned implies guarded, and a declaration that says otherwise is a table that cannot work
    * rather than one that works oddly. A covered handler reads who is signed in, and the only thing
    * that puts somebody there is the guard having refused everyone else first, so a covered route
    * the guard leaves open fails for every request that reaches it, with the mistake
    * [[requireCurrentUser]] throws: not a page anyone can use, and a 500 rather than a defect a
    * browser could report as its own.
    *
    * `Create` belongs beside `covers` here even when `covers` itself does not name it. [[body]]
    * fills the owner field from `currentUser` on every create, since there is no earlier row for an
    * uncovered write to keep the way [[keeping]] lets an uncovered `update` do, so a mounted create
    * needs a signed in user whatever the declaration says it covers. Left out of this check, a
    * declaration that guards only `Destroy` would mount a create it calls public that fails on
    * every request that reaches it, the same failure this check exists to catch.
    *
    * Read against the actions that are actually **mounted**, since covering a role the model
    * subtracted names no route, and refusing a declaration over a page that does not exist would be
    * this check inventing a mismatch. Refused at the same boot as the two above, for the same
    * reason: a declaration and what it is mounted over disagreeing is a mistake in the table, and
    * the table is built once.
    */
  owned.foreach { o =>
    val needsUser = (o.covers + Action.Create).filter(actions.has)
    val open      = (needsUser -- o.actions).toSeq.sortBy(_.ordinal)
    if (open.nonEmpty)
      throw new IllegalStateException(
        s"""$modelName declares Owned that needs a signed in user for ${open.mkString(", ")},
           |which its guard does not require one for. A covered route reads who is signed in,
           |and a create fills the owner field from who is signed in whether or not it is
           |covered, so those routes would refuse every request. Guard those actions as well,
           |or leave a covered one out of what ownership covers; a create ownership does not
           |cover can only be guarded or left unmounted.""".stripMargin
          .replace("\n", " ")
      )
  }

  /** The owner field is not a field of any page. It is filled from who is signed in, so a browser
    * must not be shown an input for it, an index must not head a column with it, and a show page
    * must not print a raw key. It is still parsed, out of the value `body` puts there.
    */
  val shape: Form[A] = owned.fold(declared)(o => declared.without(o.ownerOf.name))

  /** The declaration, when ownership covers this action. The one place that question is asked, so
    * every handler reads the same answer.
    */
  private def covering(action: Action): Option[Owned[A, Any]] =
    owned.filter(_.covers.contains(action))

  /** Who is signed in, for the two places that cannot go on without somebody: narrowing a store to
    * its owner's rows, and filling the owner field of a brand new row.
    *
    * Nobody there is a mistake in the route table rather than a request to answer, and the Owned
    * implies guarded check above refuses the shape that produces it, so reaching here means the
    * table was built another way. It throws instead of guessing, because both alternatives are the
    * ownership bypass this whole mechanism exists to close: a store narrowed to nobody reads rows
    * that are not the requester's, and a row written with no owner belongs to nobody who can be
    * refused.
    */
  private def requireCurrentUser(o: Owned[A, Any], request: Request): Id[Any] =
    o.currentUser(request)
      .getOrElse(
        throw new IllegalStateException(
          "no user is signed in for this request, so there is nobody for an owned route to " +
            "scope to; the declaration's guard let through a request it should have refused"
        )
      )

  /** The store one action reads and writes through: the current user's own rows when ownership
    * covers it, and the whole table when it does not. A blog's index and show are the whole table,
    * which is what makes two users read each other's posts.
    */
  def storeFor(request: Request, action: Action): Store[A] =
    covering(action).zip(scope).fold(store) { case (o, s) =>
      s.by(requireCurrentUser(o, request))
    }

  /** What a covered action answers when the narrowed store has no such row.
    *
    * Two readings, and which one is right is settled by the declaration rather than by taste. A
    * model that does not cover `Show` is readable by anyone, so saying "that row is somebody
    * else's" reveals nothing a `GET` would not, and 403 is the honest answer. A model that covers
    * `Show` is invisible outside its owner's scope, and a 403 there would turn the key space into a
    * list of which rows exist, which is precisely what scoping `Show` was for.
    *
    * "Readable by anyone" also needs `Show` mounted at all: a model whose `Actions` has subtracted
    * it has no route that would answer a plain `GET` with the row, so the same 403 would let an id
    * be probed through a model nothing can read, which is the second reading's leak wearing the
    * first reading's justification.
    *
    * The whole table is read only here, and only in the first reading. A probe on the success path
    * would be a second query on every covered write, and a probe under the second reading would be
    * the leak itself.
    */
  def missing(request: Request, key: Id[A], action: Action): Nothing =
    covering(action) match {
      case Some(o)
          if !o.covers.contains(Action.Show) && actions.has(Action.Show) &&
            store.find(key).isDefined =>
        throw Forbidden(s"this $modelName belongs to another user")
      case _ => throw NotFound(request.path)
    }

  /** The submission, with the owner field overwritten by who is signed in, but only for an action
    * `covers` actually names.
    *
    * Overwritten rather than read on a covered action, which is the difference between an owner and
    * a form field: a body carrying `author=<somebody else>` is not refused, it is ignored, so a
    * hand-crafted POST creates a row attributed to the person who sent it. A covered `update`
    * supplies the same value, which is also what preserves the owner there: only the owner reaches
    * a covered `update` at all, so the value the row had and the value being written are the same
    * one, and no read is needed to keep it.
    *
    * An action `covers` does not name is a different question, because nobody has vouched for the
    * requester as this row's owner: filling the field from them the same way would silently hand an
    * uncovered write the power to reassign a row it was never scoped to touch. `existing` is what
    * stands in for that missing vouching instead: the row the action already read on the store it
    * reads and writes through, so a title changed on an uncovered `update` leaves the owner exactly
    * where it was. A `create` has no earlier row to read, covered or not, and an uncovered one
    * falls back to the requester for the same reason a covered one always does: a brand new row
    * needs some first owner, and nothing else on the request names one.
    *
    * So the action itself is not asked about here: `existing` is only ever handed over by an
    * uncovered `update`, and every other write, covered or not, takes the requester.
    */
  def body(request: Request, existing: Option[A]): Map[String, Seq[String]] =
    owned.fold(request.form) { o =>
      val who = existing.map(o.ownerOf.get).getOrElse(requireCurrentUser(o, request))
      request.form.updated(o.ownerOf.name, Seq(who.show))
    }

  /** The row whose owner an uncovered `update` has to keep, and nothing at all when there is no
    * such row to read: an unowned model, or one whose ownership covers `Update`.
    *
    * Asked here rather than in the handler, so that `update` is the handler it always was and the
    * question "is this model owned" is answered once, beside [[covering]] and [[body]], the way
    * every other half of the declaration is.
    *
    * A covered write is already narrowed to the requester's own rows, so `submit`'s `persist` is
    * the only lookup it needs and this would be the second query on every covered write issue 171
    * ruled out. An uncovered write has no such narrowing, and the row on the store it is about to
    * replace is the one place its current owner can come from without letting the request choose
    * it.
    *
    * A key naming no row ends here as the 404 a missing row is everywhere else, before [[body]] is
    * built. The alternative is worse than a wrong status: an uncovered action may also be an
    * unguarded one, and `body`'s fallback would then ask who is signed in about an anonymous
    * request and fail as a route table mistake, which blames the table for a row that was never
    * there.
    */
  def keeping(request: Request, key: Id[A], target: Store[A]): Option[A] =
    if (covering(Action.Update).isDefined) None
    else owned.map(_ => target.find(key).getOrElse(missing(request, key, Action.Update)))

  /** The current user, or nobody when the request is anonymous.
    *
    * The page that asks is not necessarily guarded itself: a blog's `Show` is open to anyone, and
    * `Edit` being covered says who may reach `Edit`, not who may reach `Show`. So
    * `o.currentUser(request)` is read speculatively here, for a request nothing has vouched for,
    * and it answers nobody for exactly that request. That is not a mistake in the route table the
    * way it is inside a covered handler, which is why this answers `None` where
    * [[requireCurrentUser]] throws; it is an anonymous browser looking at a public page, and the
    * honest reading of "who is the current user" for them is nobody.
    */
  def currentUserOf(request: Request): Option[Any] =
    owned.flatMap(_.currentUser(request))

  /** Whether the current user may take `action` on `record`: always when ownership does not cover
    * the action, and otherwise only when they own the row. Nobody signed in owns nothing, so a
    * covered control on a public page is simply absent for an anonymous browser, the same as on a
    * foreign row.
    *
    * `currentUser` is by name so that a page resolves it at most once for all its controls, and not
    * at all when no control is covered.
    */
  def owns(action: Action, record: A, currentUser: => Option[Any]): Boolean =
    covering(action) match {
      case Some(o) => currentUser.contains(o.ownerOf.get(record))
      case None    => true
    }
}
