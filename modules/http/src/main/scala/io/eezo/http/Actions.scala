package io.eezo.http

/** One of the seven derived routes, named by its **role** rather than by its method and path.
  *
  * `Index` is "list them all", `New` is "give me a blank form", `Create` is "accept that form". The
  * method and the path fall out of the name. Roles are what make subtraction readable —
  * `Actions.except(Destroy)` says "no deleting", which is what a user means, rather than "no
  * `DELETE /widgets/:id`" — and they are what the orphan warning says back, since `Edit` without
  * `Update` is a form page whose submit target answers 405, and "mount `Update`, or subtract `Edit`
  * as well" names the line a user edits rather than the route that failed. #118 put the check
  * itself over the assembled route table rather than over an `Actions`, since the 405 is a property
  * of the application and not of one model.
  *
  * Declaration order is emission order, and it is a hard constraint rather than a preference:
  * `GET /widgets/new` has to be emitted before `GET /widgets/:id`, or first-match dispatch sends
  * `/widgets/new` to `show`, which then fails to read `new` as a key and answers 400 instead of
  * rendering the form.
  */
enum Action {
  case Index, New, Show, Edit, Create, Update, Destroy
}

/** The subset of the seven one model mounts.
  *
  * A `Set`, because every use is a membership test: emitting a route, and omitting a control that
  * would point at a route nobody mounted. Order never enters, since `Resource` fixes it.
  */
final case class Actions[A](allowed: Set[Action]) {

  def has(action: Action): Boolean = allowed.contains(action)
}

object Actions extends LowPriorityActions {

  /** Mounts exactly these. */
  def only[A](actions: Action*): Actions[A] = Actions(actions.toSet)

  /** Mounts everything but these. */
  def except[A](actions: Action*): Actions[A] = Actions(Action.values.toSet -- actions)
}

/** The all-seven default, at low priority so that a `given Actions[Widget]` in the model's
  * companion wins.
  *
  * It is a given rather than a public `Actions.all`, so that there is exactly one way to say
  * "everything": saying nothing.
  */
trait LowPriorityActions {

  given [A]: Actions[A] = Actions(Action.values.toSet)
}
