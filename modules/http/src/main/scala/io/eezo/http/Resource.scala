package io.eezo.http

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, error, summonFrom}
import scala.deriving.Mirror

import io.eezo.core.{Id, Store}
import io.eezo.core.html.{Attrs, Html, Mod, Url}
import io.eezo.core.html.Tags.*
import io.eezo.core.internal.util.snake

/** A mounted form page whose submit target is not mounted, named both ways.
  *
  * The role words are what the user edits, in their model's `Actions`; the route strings are what
  * they see in a browser. Both are in the warning because the defect is read one way and fixed the
  * other. The model's own name is deliberately absent: a route does not carry it, and putting it
  * there is #106's three field `Route` again.
  */
private[eezo] final case class Orphan(
    page: Action,
    pageRoute: String,
    target: Action,
    targetRoute: String
)

/** The seven HTML CRUD routes a model mounts.
  *
  * `derives Resource` needs a `Mirror`, a [[Form]] and an [[Actions]], and nothing else. `Table[A]`
  * is not in the chain: `http` depends on `core` alone, so the route path is computed here from the
  * class name rather than read off a table name. Storage names and route names now go wrong for
  * different reasons and take different overrides, and a model deriving `Form, Resource` and no
  * `Table` mounts seven working routes.
  *
  * The [[io.eezo.core.Store]] arrives as a parameter of [[routes]] rather than being summoned, so a
  * test can hand over one it made itself and an unresolved store reports at the call site. It is
  * `core`'s trait and one per model: which implementation a model gets is decided where both `db`
  * and `http` are visible, which is the generated route table and nowhere in here.
  */
@implicitNotFound(
  "No Resource instance for ${A}.\n" +
    "Add `derives Form, Resource` to its declaration:\n" +
    "  case class ${A}(id: Id[${A}], ...) derives Form, Resource"
)
trait Resource[A] {

  /** The routes, in dispatch order.
    *
    * `guarded` defaults to [[Guarded.public]] so that every call site written before guards existed
    * still compiles and still mounts seven open routes. It is a parameter rather than a summoned
    * given for the same reason the store is one: what a route table decides about a model is
    * decided where the table is written, and an unresolved declaration should report at that line
    * rather than inside a derivation.
    */
  def routes(store: Store[A], guarded: Guarded[A] = Guarded.public): Seq[Route]
}

object Resource {

  def apply[A](using r: Resource[A]): Resource[A] = r

  /** A derived page that exists only to submit somewhere else: the literal segment that names it,
    * and the action and verb of the route its form posts to.
    *
    * Stated once because [[orphaned]] reads it backwards. Emission goes from an [[Action]] to a
    * path, the check goes from a mounted path to the target it needs, and the two only meet at this
    * pairing, so a renamed segment or a changed verb written on one side alone would leave the
    * check hunting for a route nobody emits and warning about pages that are fine.
    *
    * What is not here is the rest of the path: which handler serves the page, and the captured key
    * an edit page sits behind. The handler is emission's alone, and the key is a shape [[orphaned]]
    * reads off the pattern itself, since a value naming one segment cannot say what has to stand in
    * front of it.
    */
  private final case class FormPage(
      action: Action,
      segment: String,
      target: Action,
      targetMethod: Method
  )

  private val NewPage  = FormPage(Action.New, "new", Action.Create, Method.POST)
  private val EditPage = FormPage(Action.Edit, "edit", Action.Update, Method.PUT)

  /** The routes of `A` if it has a `Resource`, and nothing if it does not.
    *
    * This is what the sbt plugin emits, one line per candidate case class, so that the **compiler**
    * decides which models mount rather than a text scan guessing from a `derives` clause. It sees
    * through a type alias and finds a hand-written `given Resource[A]`, neither of which a regex
    * can, and a model with no instance yields `Nil` instead of failing to compile.
    *
    * What the declaration carries comes out in front of the model's own routes, so that guarding a
    * model is also what mounts the page its refusals redirect to. `Guarded.public` carries nothing,
    * which is why an application with no guard emits exactly the table it always did. A guard hands
    * the same route instances to every declaration it makes, so a table with two guarded models
    * holds the login page twice and `distinct` takes one; the generator emits that `distinct`,
    * because `RouteTable` refuses the same method and path twice rather than picking a winner.
    */
  inline def routesOf[A](
      store: Store[A],
      guarded: Guarded[A] = Guarded.public[A]
  ): Seq[Route] = summonFrom {
    case r: Resource[A] => guarded.carries ++ r.routes(store, guarded)
    case _              => Seq.empty
  }

  inline def derived[A](using
      m: Mirror.ProductOf[A],
      form: Form[A],
      actions: Actions[A]
  ): Resource[A] =
    make[A](
      constValue[m.MirroredLabel],
      keyIndex[A, m.MirroredElemLabels, m.MirroredElemTypes](0),
      form,
      actions
    )

  /** The index of the field named `id`, which has to be typed `Id[A]`.
    *
    * A separate check with its own message rather than an `@implicitNotFound` on the trait, because
    * "no `Form` instance for `Widget`" and "`Widget`'s `id` is a `Long`" are different mistakes and
    * one message cannot say both well.
    *
    * The key type is fixed rather than summoned, and that is `create`'s doing: it has to mint a key
    * before there is anything to insert, and `Id.gen()` is the only generator eezo has. A
    * `Keygen[K]` typeclass would be the same constraint with a name on it, designed against one
    * implementation. A keyless model such as `Login(email, password)` still forms and parses; it
    * does not mount, because a `create` whose success has nowhere to redirect and an `index` that
    * cannot link a row are not half a resource.
    */
  private inline def keyIndex[A, Labels <: Tuple, Types <: Tuple](inline soFar: Int): Int =
    inline erasedValue[Labels] match {
      case _: EmptyTuple =>
        error(
          "A model deriving Resource needs a field named `id`, typed Id[Model]: the seven routes " +
            "read it out of the path, and `create` mints one with Id.gen()."
        )
      case _: (label *: labels) =>
        inline erasedValue[Types] match {
          case _: (tpe *: types) =>
            inline erasedValue[label] match {
              case _: "id" =>
                inline erasedValue[tpe] match {
                  case _: Id[A] => soFar
                  case _        =>
                    error(
                      "A model deriving Resource needs its `id` field typed Id[Model]: `create` " +
                        "mints a key with Id.gen(), which no other key type has."
                    )
                }
              case _ => keyIndex[A, labels, types](soFar + 1)
            }
        }
    }

  /** Compiled once, rather than at every `derives` site: an anonymous class returned straight out
    * of an `inline def` is duplicated into every model's companion.
    */
  private def make[A](
      modelName: String,
      keyIndex: Int,
      declared: Form[A],
      actions: Actions[A]
  ): Resource[A] = {

    /** The route name, inflected off the class name.
      *
      * It names paths and nothing else. A store no longer takes a bucket, so this string cannot
      * reach storage even by accident, and a legacy table name renames no URL.
      */
    val plural = pluralise(snake(modelName))

    /** A [[Url.Mounted]] rather than a `String`, so that every link a page renders and every
      * `Location` a write sets travels with `Route.under`. Derivation has no prefix to bake in;
      * mounting is what puts one on.
      */
    val collection: Url = Url.Mounted(s"/$plural")

    def keyOf(row: A): Id[A] =
      row.asInstanceOf[Product].productElement(keyIndex).asInstanceOf[Id[A]]

    new Resource[A] {
      def routes(store: Store[A], guarded: Guarded[A]): Seq[Route] =
        new Pages(
          modelName,
          keyOf,
          actions,
          plural,
          collection,
          guarded,
          new Ownership(modelName, declared, actions, store, guarded)
        ).routes
    }
  }

  /** The seven pages of one mount and the routes that serve them.
    *
    * Everything a page needs to know about ownership it asks [[Ownership]], built for the same
    * mount before this is, so the refusals have already fired by the time a handler exists and
    * every handler here reads the same answers whether or not the model is owned.
    */
  private final class Pages[A](
      modelName: String,
      keyOf: A => Id[A],
      actions: Actions[A],
      plural: String,
      collection: Url,
      guarded: Guarded[A],
      ownership: Ownership[A]
  ) {

    private def member(key: Id[A]): Url = collection / key.show

    /** Where a successful write goes. `show` when it is mounted, and the index when it is not,
      * because redirecting to a route nobody mounted is a 404 at the end of a successful save.
      */
    private def afterWrite(key: Id[A]): Url =
      if (actions.has(Action.Show)) member(key) else collection

    /** A control is rendered only when the route it points at is mounted. Subtracting `Destroy`
      * removes the delete button as well as the route, so a page never offers a 405.
      */
    private def when(action: Action)(content: => Html): Seq[Html] =
      if (actions.has(action)) Seq(content) else Nil

    /** A control on a show page that only the owner of the row may take, so a foreign row shows its
      * fields and offers nothing. Who owns what is [[Ownership.owns]]'s question and mounting is
      * still [[when]]'s, so a control whose route was subtracted is absent whoever owns the row.
      */
    private def ownerOnly(action: Action, record: A, currentUser: => Option[Any])(
        content: => Html
    ): Seq[Html] =
      if (ownership.owns(action, record, currentUser)) when(action)(content) else Nil

    private def backToIndex: Seq[Html] =
      when(Action.Index)(p(a(Attrs.href := collection, s"All $plural")))

    /** A well-formed submission that failed a field is a 422, and it is **returned** rather than
      * thrown: it carries the re-rendered form, so it must not reach `Boundary`'s problem page. A
      * 400 would be the wrong fact — that is what a request eezo cannot decode gets.
      */
    private def rejected(heading: String, rendered: Html): Response =
      Response(
        422,
        Seq(Response.HtmlContentType),
        // The whole page the form was already on, heading and all. Coming back to a bare form
        // reads as a different screen, which is the opposite of what returning the typing is for.
        Body.Html(page(heading, h1(heading), rendered, backToIndex))
      )

    private def row(request: Request, action: Action): (Id[A], A) = {
      val key = request.param[Id[A]]("id")
      (
        key,
        ownership
          .storeFor(request, action)
          .find(key)
          .getOrElse(ownership.missing(request, key, action))
      )
    }

    private def index: Handler = request => {
      val rows = ownership.storeFor(request, Action.Index).all()

      val listing =
        if (rows.isEmpty) p(s"No $plural yet")
        else
          table(
            thead(tr(ownership.shape.fields.map(field => th(field.label)))),
            tbody(
              rows.map { record =>
                val cells = ownership.shape.show(record).map(_._2)
                tr(
                  td(
                    if (actions.has(Action.Show))
                      a(Attrs.href := member(keyOf(record)), cells.headOption.getOrElse(""))
                    else Html.text(cells.headOption.getOrElse(""))
                  ),
                  cells.drop(1).map(cell => td(cell))
                )
              }
            )
          )

      Response.Ok(
        page(
          plural,
          h1(s"All $plural"),
          listing,
          when(Action.New)(p(a(Attrs.href := collection / NewPage.segment, s"New $modelName")))
        )
      )
    }

    private def blank: Handler = request =>
      Response.Ok(
        page(
          s"New $modelName",
          h1(s"New $modelName"),
          ownership.shape.render(collection, Method.POST, None, request.csrf),
          backToIndex
        )
      )

    /** The half of `create` and `update` that is the same handler twice: parse the submission under
      * a key that is already decided, come back with the whole page when a field fails, and
      * redirect where a successful write goes. Only the heading, the form's target and verb, and
      * the one line that writes the row ever differed, so those are the parameters and nothing
      * else.
      *
      * `persist` answers whether the row was written, which lets `update` report a missing key
      * without reading it back first: the store already had to look for the row to replace it, and
      * a read before the write would be that same lookup twice with a race in the gap. `create`
      * always writes, so it answers `true`.
      *
      * `existing` is unrelated to that question and answers a different one: what the owner field
      * of an **uncovered** write should hold, since nothing about that write vouches for the
      * requester as the row's owner the way a covered one does. `create` never has an earlier row
      * and leaves it at the default; `update` takes [[Ownership.keeping]]'s answer, which reads a
      * row only when ownership does not cover `Update`, which is not the read `persist` above is
      * about and not one a covered write ever pays for.
      *
      * What is parsed is [[Ownership.body]] and not the submission itself, so an owned model's
      * owner field holds who is signed in on a covered write, or what the row already had on one
      * that is not, whatever the browser sent. What is rendered back on a failure is the
      * submission, because that is the typing to return, and the owner field is not on the page to
      * be returned.
      */
    private def submit(
        request: Request,
        key: Id[A],
        action: Action,
        heading: String,
        target: Url,
        verb: Method,
        existing: Option[A] = None
    )(persist: A => Boolean): Response =
      ownership.shape.parse(ownership.body(request, existing), Some(key.show)) match {
        case Left(errors) =>
          rejected(
            heading,
            ownership.shape.render(target, verb, None, request.csrf, errors, request.form)
          )
        case Right(record) =>
          if (!persist(record)) ownership.missing(request, key, action)
          Response.Redirect(afterWrite(key))
      }

    private def create: Handler = request => {
      val key = Id.gen[A]()
      submit(request, key, Action.Create, s"New $modelName", collection, Method.POST) { record =>
        ownership.storeFor(request, Action.Create).insert(key, record)
        true
      }
    }

    private def show: Handler = request => {
      val (key, record)    = row(request, Action.Show)
      lazy val currentUser = ownership.currentUserOf(request)

      Response.Ok(
        page(
          modelName,
          h1(modelName),
          dl(
            ownership.shape.show(record).flatMap { case (field, value) =>
              Seq(dt(field.label), dd(value))
            }
          ),
          ownerOnly(Action.Edit, record, currentUser)(
            p(a(Attrs.href := member(key) / EditPage.segment, "Edit"))
          ),
          ownerOnly(Action.Destroy, record, currentUser)(
            form(
              Attrs.action := member(key),
              Attrs.method := "post",
              Form.reserved(Method.DELETE, request.csrf),
              button(Attrs.tpe := "submit", "Delete")
            )
          ),
          backToIndex
        )
      )
    }

    private def edit: Handler = request => {
      val (key, record) = row(request, Action.Edit)

      Response.Ok(
        page(
          s"Edit $modelName",
          h1(s"Edit $modelName"),
          ownership.shape.render(member(key), Method.PUT, Some(record), request.csrf),
          backToIndex
        )
      )
    }

    private def update: Handler = request => {
      val key    = request.param[Id[A]]("id")
      val target = ownership.storeFor(request, Action.Update)

      submit(
        request,
        key,
        Action.Update,
        s"Edit $modelName",
        member(key),
        Method.PUT,
        ownership.keeping(request, key, target)
      ) { record =>
        target.update(key, record)
      }
    }

    private def destroy: Handler = request => {
      val key = request.param[Id[A]]("id")
      if (!ownership.storeFor(request, Action.Destroy).delete(key))
        ownership.missing(request, key, Action.Destroy)
      Response.Redirect(collection)
    }

    /** Every route here is [[Route.derived]]. That is what lets a user mount `GET /$plural` by hand
      * and keep the other six pages: the table drops the derived twin rather than refusing to boot.
      */
    val routes: Seq[Route] = {
      import Route.derived as route

      Action.values.toSeq.filter(actions.has).map { action =>
        val built = action match {
          case Action.Index => route(Method.GET, collection.path, index)
          case Action.New   =>
            route(Method.GET, s"${collection.path}/${NewPage.segment}", blank)
          case Action.Show => route(Method.GET, s"${collection.path}/:id", show)
          case Action.Edit =>
            route(Method.GET, s"${collection.path}/:id/${EditPage.segment}", edit)
          case Action.Create => route(NewPage.targetMethod, collection.path, create)
          case Action.Update =>
            route(EditPage.targetMethod, s"${collection.path}/:id", update)
          case Action.Destroy => route(Method.DELETE, s"${collection.path}/:id", destroy)
        }

        // The declaration reaches a route only when it names that route's role, so a model can
        // guard its writes and leave its index open. Applied once, here, where the [[Action]]
        // that earned it is still in hand: one step later, a `Seq[Route]` has only methods and
        // paths, and the mapping back would be this table written a second time.
        if (guarded.actions.contains(action)) guarded.through(built) else built
      }
    }
  }

  /** The document every derived page comes back in, matching the envelope `Boundary` already
    * renders errors into. Deliberately plain and deliberately not a `Layout`: whether an
    * application can replace eezo's own pages is an open question, and shipping a seam before it is
    * answered risks shipping the wrong one and then having two.
    */
  private def page(heading: String, content: Mod*): Html =
    Html.doctype ++ html(
      head(meta(Attrs.charset := "utf-8"), title(heading)),
      body(content*)
    )

  /** Every mounted page whose submit target is not mounted.
    *
    * #116 settled the defect and #118 settled the shape: a pure method over the value that holds
    * the whole picture, logged in one place, `HttpServer.start`'s `announce`. The value is the
    * assembled [[RouteTable]] rather than an [[Actions]], because `Actions[A]` describes one model
    * in isolation and the 405 is a property of the application. A user who subtracts `Update` and
    * writes `PUT /posts/:id` by hand under `app/` has mounted the target; decision 18 makes that
    * the ordinary way to take over one page and keep the rest, and a check reading `Actions[A]`
    * would warn them to mount what they mounted.
    *
    * It lives here rather than beside `overridden` and `shadowed` because `/new` and `/:id/edit`
    * are conventions this object invented, and [[RouteTable]] knows only methods, patterns and
    * order.
    *
    * Only a **derived** `GET` can be an orphaned page, and any route at all can be its target. A
    * handwritten `/posts/new` is free to submit anywhere, so reading its intent off its path would
    * be a guess; a handwritten target is not a guess, because the page's submit URL is fixed by the
    * same convention that emitted the page. The pair is one way, so a `create` with no `new` is
    * silent: that is a POST target with no derived form, which a handwritten form may post to.
    *
    * Whether the target is mounted is decided by [[PathPattern.subsumes]], not by matching the name
    * [[Route.describe]] renders. The user writing the target picks their own parameter name, and
    * `PUT /posts/:postId` serves every request `PUT /posts/:id` would have served, so a string key
    * answers "unmounted" and warns them about the route they just wrote, which is the one failure
    * this check exists to avoid. It is the predicate [[RouteTable.shadowed]] asks, in that same
    * direction: the mounted pattern has to swallow the target, so a handwritten `/posts/new` never
    * answers for `/posts/:id`. Relocation under a prefix stays free either way, since
    * [[Route.under]] moves a page and its target by the same prefix.
    */
  private[eezo] def orphaned(table: RouteTable): Seq[Orphan] = {
    import PathPattern.Segment

    def isMounted(method: Method, target: PathPattern): Boolean =
      table.routes.exists {
        case Route.Http(other, pattern, _, _) => other == method && pattern.subsumes(target)
        case _                                => false
      }

    table.httpRoutes
      .filter(route => route.method == Method.GET && route.provenance == Provenance.Derived)
      .flatMap { route =>
        val segments = route.pattern.segments

        def keyed: Boolean = segments.dropRight(1).lastOption.exists {
          case Segment.Param(_) => true
          case _                => false
        }

        // Segments rather than a suffix on the rendered path, because the shape is the contract:
        // an edit page is a captured key followed by the literal `edit`, and a static `edit`
        // sitting anywhere else is a page this object never emitted and cannot read the intent of.
        val formPage = segments.lastOption match {
          case Some(Segment.Static(NewPage.segment))           => Some(NewPage)
          case Some(Segment.Static(EditPage.segment)) if keyed => Some(EditPage)
          case _                                               => None
        }

        formPage.flatMap { page =>
          // The page's own segment dropped, which is where its form submits.
          val target = route.pattern.dropLast
          Option.when(!isMounted(page.targetMethod, target))(
            Orphan(
              page.action,
              route.describe,
              page.target,
              s"${page.targetMethod} ${target.render}"
            )
          )
        }
      }
  }

  /** #110's deliberately dumb inflector: `+s`, a consonant before `y` becoming `ies`, and a
    * sibilant taking `es`. No dictionary and no irregular list, because a plural that reads badly
    * is a companion override away, while a dictionary is a dependency and a surprise.
    *
    * Underscores survive, so `blog_post` mounts at `/blog_posts`.
    */
  private def pluralise(word: String): String =
    if (word.isEmpty) word
    else if (
      word.endsWith("y") && word.length > 1 && !"aeiou".contains(word.charAt(word.length - 2))
    )
      word.dropRight(1) + "ies"
    else if (
      word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
      word.endsWith("ch") || word.endsWith("sh")
    ) word + "es"
    else word + "s"
}
