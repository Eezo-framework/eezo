package io.eezo.http

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, constValueTuple}
import scala.deriving.Mirror

import io.eezo.core.{Id, Store}
import io.eezo.core.html.Url
import io.eezo.core.internal.util.snake

/** The five JSON routes a model mounts for a program: index, show, create, update and destroy.
  *
  * A sibling of [[Resource]] rather than a part of it, because a model may want either without the
  * other: a back office with no program calling it has no use for JSON, and a model only a mobile
  * client reads has no use for pages. What the two share they share by construction, not by
  * agreement: the same plural off the class name, the same [[Actions]], the same declaration and
  * the same store, so the HTML seven and the JSON five of one model read and write the same rows
  * under the same rules.
  *
  * `derives Api` needs a `Mirror`, an [[api.Serde]] and an [[Actions]], and no [[Form]]: JSON is
  * read and written by the Serde, and nothing here renders a page. The Serde is summoned where the
  * model derives, which is what keeps the JSON library out of `http`: the application chose an
  * adapter, imported its given, and that given is the only code that sees the library.
  *
  * There is no JSON route for `New` or `Edit`. Both serve a blank or filled form for a browser to
  * submit, and a program builds its own body, so subtracting either changes nothing here.
  */
@implicitNotFound(
  "No Api instance for ${A}.\n" +
    "Add `derives Form, Resource, Api` to its declaration:\n" +
    "  case class ${A}(id: Id[${A}], ...) derives Form, Resource, Api\n" +
    "Api reads and writes JSON through a Serde, so one has to be in scope where it derives:\n" +
    "  import io.eezo.json.upickle.given"
)
trait Api[A] {

  /** The routes, in dispatch order.
    *
    * The same signature as [[Resource.routes]], so the generated route table hands both the same
    * store and the same declaration, and the two cannot disagree about either.
    */
  def routes(store: Store[A], guarded: Guarded[A] = Guarded.public): Seq[Route]
}

object Api {

  def apply[A](using a: Api[A]): Api[A] = a

  /** The routes of `A` if it has an `Api`, and nothing if it does not, which is what the sbt plugin
    * emits beside [[Resource.routesOf]] for every candidate model.
    *
    * Unlike `Resource.routesOf`, nothing the declaration carries comes out in front. A guard's
    * login page is where a refused browser is sent, and a program is never sent to a page: it is
    * answered 401. So an application whose only guarded routes are API routes mounts no login page,
    * which is the rule [[Guarded.mounting]] keeps for a handwritten API route.
    */
  inline def routesOf[A](
      store: Store[A],
      guarded: Guarded[A] = Guarded.public[A]
  ): Seq[Route] = scala.compiletime.summonFrom {
    case a: Api[A] => a.routes(store, guarded)
    case _         => Seq.empty
  }

  /** The key is found the way [[Resource.derived]] finds it, with the same two refusals, because
    * `create` mints one with `Id.gen()` here too and update reads one out of the path.
    */
  inline def derived[A](using
      m: Mirror.ProductOf[A],
      serde: api.Serde[A],
      actions: Actions[A]
  ): Api[A] =
    make[A](
      constValue[m.MirroredLabel],
      constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String]),
      Resource.keyIndex[A, m.MirroredElemLabels, m.MirroredElemTypes](0),
      values => m.fromProduct(Tuple.fromArray(values)),
      serde,
      actions
    )

  /** Compiled once, rather than at every `derives` site, for the reason [[Resource]]'s is: an
    * anonymous class returned straight out of an `inline def` is duplicated into every model's
    * companion.
    */
  private def make[A](
      modelName: String,
      labels: List[String],
      keyIndex: Int,
      build: Array[Any] => A,
      serde: api.Serde[A],
      actions: Actions[A]
  ): Api[A] = {

    /** A [[Url.Mounted]] for the reason [[Resource]]'s collection is one: a mount moves the five
      * with the seven, and the `Location` a create sets moves with them.
      */
    val collection: Url = Url.Mounted(s"/api/${Resource.pluralise(snake(modelName))}")

    /** What this model mounts, read as JSON: the form pages are not among the five. Handed to
      * [[Ownership]] as well, so its check that a covered route is guarded reads the routes that
      * are actually here.
      */
    val mounted = Actions[A](actions.allowed -- Set(Action.New, Action.Edit))

    /** The names an owner may be: every field but the key, the set [[Resource]] hands the same
      * check through `Form.fields`. A create sets the minted key and then the owner, so an owner
      * named `id` would overwrite the key and leave a row stored under one key and carrying
      * another.
      */
    val ownable = labels.patch(keyIndex, Nil, 1)

    new Api[A] {
      def routes(store: Store[A], guarded: Guarded[A]): Seq[Route] =
        new Calls(
          labels,
          keyIndex,
          build,
          serde,
          mounted,
          collection,
          guarded,
          new Ownership(modelName, ownable, mounted, store, guarded)
        ).routes
    }
  }

  /** The five calls of one mount and the routes that serve them.
    *
    * Every store a handler reads and writes through, and every answer for a missing row, is
    * [[Ownership]]'s, the class [[Resource]]'s pages ask too, so an owned model's JSON index lists
    * the caller's rows and a foreign row is the same 404 or 403 it is on a page.
    */
  private final class Calls[A](
      labels: List[String],
      keyIndex: Int,
      build: Array[Any] => A,
      serde: api.Serde[A],
      actions: Actions[A],
      collection: Url,
      guarded: Guarded[A],
      ownership: Ownership[A]
  ) {

    private given api.Serde[A] = serde

    private def member(key: Id[A]): Url = collection / key.show

    /** The body, read as a row under the key and the owner eezo already decided.
      *
      * The owner is decided by the rule [[Ownership.body]] applies to a form: a body naming another
      * owner is not refused, it is overwritten, with the caller on a covered write and with the
      * row's own owner on an uncovered update, which is what `existing` carries. A form is
      * rewritten before it is parsed; a JSON body is handed to the Serde with both, so a body need
      * name neither.
      *
      * Both are set again after the Serde has read the body, rather than trusted to have been: a
      * hand written Serde that kept what the body said would otherwise let a client choose the key
      * of the row it creates, move a row it updates to another key, or write a row as somebody
      * else's.
      */
    private def read(request: Request, key: Id[A], existing: Option[A]): A = {
      val owner                         = ownership.owner(request, existing)
      val decided: Map[String, Id[Any]] = Map("id" -> Id[Any](key.value)) ++ owner
      val row                           = serde.deserialize(request.body, decided) match {
        case Right(row)                                => row
        case Left(api.Serde.Failure.Malformed(reason)) => throw BadRequest(reason)
        case Left(api.Serde.Failure.Unfit(reason))     => throw UnprocessableContent(reason)
      }
      val values = row.asInstanceOf[Product].productIterator.toArray[Any]
      values(keyIndex) = key
      owner.foreach((name, who) => values(labels.indexOf(name)) = who)
      build(values)
    }

    /** One array of what the Serde writes for each row. The Serde is asked for one value at a time
      * because that is all it promises, so the brackets and commas are written here.
      */
    private def index: Handler = request => {
      val rows = ownership.storeFor(request, Action.Index).all()
      Response(200, Seq.empty, Body.Json(rows.map(serde.serialize).mkString("[", ",", "]")))
    }

    private def show: Handler = request => {
      val key = request.param[Id[A]]("id")
      Response.Ok(
        ownership
          .storeFor(request, Action.Show)
          .find(key)
          .getOrElse(ownership.missing(request, key, Action.Show))
      )
    }

    private def create: Handler = request => {
      val key = Id.gen[A]()
      val row = read(request, key, None)
      ownership.storeFor(request, Action.Create).insert(key, row)
      Response.Created(row).withHeader("Location", member(key))
    }

    /** The row is read before it is written and never after, so a body that fails leaves the stored
      * row as it was, and a key naming no row is the store's own answer to the write rather than a
      * lookup in front of it.
      */
    private def update: Handler = request => {
      val key    = request.param[Id[A]]("id")
      val target = ownership.storeFor(request, Action.Update)
      val row    = read(request, key, ownership.keeping(request, key, target))
      if (!target.update(key, row)) ownership.missing(request, key, Action.Update)
      Response.Ok(row)
    }

    /** A 204 carries no body, so the server names no content type for it. */
    private def destroy: Handler = request => {
      val key = request.param[Id[A]]("id")
      if (!ownership.storeFor(request, Action.Destroy).delete(key))
        ownership.missing(request, key, Action.Destroy)
      Response.status(204)
    }

    /** Every route here is derived, so a handwritten file at the same method and path takes it
      * over, and of the API kind, so no CSRF token is asked for and no session is read: a program
      * carries neither. The declaration reaches a route only when it names that route's role, as on
      * the seven, and a guard wrapping an API route reads its Bearer token.
      */
    val routes: Seq[Route] = {
      def route(method: Method, path: String, handler: Handler): Route =
        Route.derived(method, path, handler, RouteKind.Api)

      Resource.derivedRoutes(actions, guarded) {
        case Action.Index   => Some(route(Method.GET, collection.path, index))
        case Action.Show    => Some(route(Method.GET, s"${collection.path}/:id", show))
        case Action.Create  => Some(route(Method.POST, collection.path, create))
        case Action.Update  => Some(route(Method.PUT, s"${collection.path}/:id", update))
        case Action.Destroy => Some(route(Method.DELETE, s"${collection.path}/:id", destroy))
        case Action.New | Action.Edit => None
      }
    }
  }
}
