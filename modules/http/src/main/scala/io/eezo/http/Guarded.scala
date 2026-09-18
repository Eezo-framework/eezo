package io.eezo.http

import scala.annotation.implicitNotFound

/** The declaration, made beside a model or a page, of which of its routes require a signed in user.
  *
  * A data record rather than a trait with methods, because it is the concept and not the mechanism:
  * `modules/auth` ships one way of signing in, and any other, a single sign on or an API key, has
  * to produce one of these and nothing else. `http` therefore has no guard in it at all, which is
  * what keeps a login page out of an application that has no users.
  *
  * Three fields, and each is one question a route table has to answer.
  *
  * [[actions]] is which of the seven the declaration covers, so a model can guard writing and leave
  * reading public. A page rather than a model is `Guarded[Index.type]`, whose routes are
  * handwritten and have no [[Action]] between them; for those the set is not read and the wrapper
  * reaches the route directly. [[through]] is the wrapper. It is a `Route => Route` rather than a
  * `Handler => Handler` because refusing a `Route.Ws` is not refusing a `Route.Http`: one answers a
  * redirect and the other cannot, and only something holding the whole route can tell them apart.
  *
  * Guarding `Create` while leaving `New` open, or `Update` while leaving `Edit` open, raises no
  * warning, and that is a decision rather than an omission. The `Orphan` warning next door exists
  * because a form page whose submit target is not mounted is a guaranteed 405 with no reading under
  * which the author meant it. This is not that: the target is mounted and answers correctly, with a
  * 303 to the login page, which is what a login redirect is for. A form anyone may fill in and only
  * a signed in user may submit is a shape somebody will want on purpose, and warning about it would
  * be eezo having an opinion about a page's design rather than reporting a route that cannot work.
  *
  * [[carries]] is the guard's own routes, the login page and the logout button, travelling inside
  * every declaration that guard produced. That is what makes them impossible to forget: an
  * application that guards one route has mounted the page that route redirects to, without writing
  * a line. It also means the same route instances appear in many declarations, so the route table
  * concatenates them and takes `distinct`; two equal but separate instances would mount the login
  * page twice and `RouteTable`'s duplicate check would refuse to boot.
  *
  * Not `final`, and that is the one concession this type makes to [[Owned]]. Ownership is a
  * refinement of being guarded rather than a second kind of declaration beside it, so an owned
  * model declares one thing and the route table's lookup, its `mounting` and every call site that
  * takes a `Guarded[A]` stay exactly as they were. The alternative, a separate type, would mean
  * every one of those places learning a second one and an owned model writing two lines that have
  * to agree.
  *
  * Being open is also why it is a plain class with three `val`s rather than a `case class`. A case
  * class that something extends lies about all three of the things `case` generates: an [[Owned]]
  * would equal a bare `Guarded` carrying the same three fields, two `Owned` differing only in what
  * they cover or whose field records the owner would equal each other, and `copy` on an `Owned`
  * would hand back a plain `Guarded` with the ownership silently gone. A declaration is read for
  * its fields and never copied or compared, so nothing here wants those; what it wants is that a
  * refinement of it stays one.
  *
  * There is deliberately no default given. `Actions` has one, because saying nothing about which
  * routes a model mounts means all seven, which is a safe silence. Saying nothing about who may
  * reach them is not: a default of `public` would make an unguarded route the thing a user gets by
  * forgetting, and a default of guarded would make every application need a guard. Silence is a
  * compile error instead, in an application whose build declares `eezo-auth`, and means public
  * anywhere else. A declaration that exists is used either way, which is the rule the sbt plugin's
  * generator enforces.
  */
@implicitNotFound(
  "No Guarded instance for ${A}, and this build declares eezo-auth, so every " +
    "route has to say who may reach it.\n" +
    "In its companion, one of:\n" +
    "  given Guarded[${A}] = User.guard.required          // every route needs a signed in user\n" +
    "  given Guarded[${A}] = User.guard.only(Action.Create, Action.Update, Action.Destroy)\n" +
    "  given Guarded[${A}] = Guarded.public               // anyone may reach it"
)
class Guarded[A](
    val actions: Set[Action],
    val through: Route => Route,
    val carries: Seq[Route]
) {

  /** What one handwritten route becomes in the table: the guard's own pages, and that route
    * wrapped.
    *
    * A handwritten route has no [[Action]], so there is nothing to test [[actions]] against and the
    * wrapper reaches it directly. A page is guarded or it is public, which is the same rule
    * `Guarded[Index.type] = Guarded.public` states in the other direction, and `public` wraps
    * nothing and carries nothing, so an unguarded application's table is exactly what it was.
    *
    * The login page comes out of every declaration rather than being mounted once somewhere else,
    * because the two have to arrive together: a route that redirects to a page nobody mounted is a
    * 404 at the end of every refusal. The table takes `distinct` afterwards, which is safe only
    * because one guard builds its routes once and hands the same instances to every declaration it
    * makes.
    */
  def mounting(route: Route): Seq[Route] = carries :+ through(route)
}

object Guarded {

  /** Anyone may reach it: no action is covered, nothing is wrapped, and no login page is carried.
    *
    * A `def` rather than a `val`, because `Guarded` is invariant in `A` and one shared instance
    * would need a cast at every use. The three fields it builds hold nothing, so the allocation is
    * the cheapest thing in a route table's construction.
    *
    * It is public and named, unlike `Actions`' all-seven default, which is a given precisely so
    * that there is one way to say it. Here the opposite is wanted: "anyone may reach this" is a
    * decision somebody made, and it should be legible in the companion of the thing it was made
    * about.
    */
  def public[A]: Guarded[A] = Guarded(Set.empty, identity, Seq.empty)
}
