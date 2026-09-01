package io.eezo.http

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, error, summonFrom}
import scala.deriving.Mirror

import io.eezo.core.Id
import io.eezo.core.html.{Attrs, Html, Mod}
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
  * The `Store` arrives as a parameter of [[routes]] rather than being captured, so one store serves
  * every model in an application and a test that builds its own table gets its own empty world.
  */
@implicitNotFound(
  "No Resource instance for ${A}.\n" +
    "Add `derives Form, Resource` to its declaration:\n" +
    "  case class ${A}(id: Id[${A}], ...) derives Form, Resource"
)
trait Resource[A] {

  /** The routes, in dispatch order. */
  def routes(store: Store): Seq[Route]
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
    */
  inline def routesOf[A](store: Store): Seq[Route] = summonFrom {
    case r: Resource[A] => r.routes(store)
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
      shape: Form[A],
      actions: Actions[A]
  ): Resource[A] = {

    /** The route name, and the store's bucket key, so the two cannot disagree about which rows
      * belong to which model.
      */
    val plural     = pluralise(snake(modelName))
    val collection = s"/$plural"

    def member(key: Id[A]): String = s"$collection/${key.show}"

    def keyOf(row: A): Id[A] =
      row.asInstanceOf[Product].productElement(keyIndex).asInstanceOf[Id[A]]

    /** Where a successful write goes. `show` when it is mounted, and the index when it is not,
      * because redirecting to a route nobody mounted is a 404 at the end of a successful save.
      */
    def afterWrite(key: Id[A]): String =
      if (actions.has(Action.Show)) member(key) else collection

    def row(store: Store, request: Request): (Id[A], A) = {
      val key = request.param[Id[A]]("id")
      (key, store.get[A](plural, key).getOrElse(throw NotFound(request.path)))
    }

    def index(store: Store): Handler = _ => {
      val rows = store.list[A](plural)

      val listing =
        if (rows.isEmpty) p(s"No $plural yet")
        else
          table(
            thead(tr(shape.fields.map(field => th(field.label)))),
            tbody(
              rows.map { record =>
                val cells = shape.show(record).map(_._2)
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
          when(Action.New)(p(a(Attrs.href := s"$collection/new", s"New $modelName")))
        )
      )
    }

    def blank: Handler = _ =>
      Response.Ok(
        page(
          s"New $modelName",
          h1(s"New $modelName"),
          shape.render(collection, Method.POST, None),
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
      */
    def submit(request: Request, key: Id[A], heading: String, target: String, verb: Method)(
        persist: A => Boolean
    ): Response =
      shape.parse(request.form, Some(key.show)) match {
        case Left(errors) =>
          rejected(heading, shape.render(target, verb, None, errors, request.form))
        case Right(record) =>
          if (!persist(record)) throw NotFound(request.path)
          Response.Redirect(afterWrite(key))
      }

    def create(store: Store): Handler = request => {
      val key = Id.gen[A]()
      submit(request, key, s"New $modelName", collection, Method.POST) { record =>
        store.insert(plural, key, record)
        true
      }
    }

    def show(store: Store): Handler = request => {
      val (key, record) = row(store, request)

      Response.Ok(
        page(
          modelName,
          h1(modelName),
          dl(shape.show(record).flatMap { case (field, value) => Seq(dt(field.label), dd(value)) }),
          when(Action.Edit)(p(a(Attrs.href := s"${member(key)}/edit", "Edit"))),
          when(Action.Destroy)(
            form(
              Attrs.action := member(key),
              Attrs.method := "post",
              Form.methodOverride(Method.DELETE),
              button(Attrs.tpe := "submit", "Delete")
            )
          ),
          backToIndex
        )
      )
    }

    def edit(store: Store): Handler = request => {
      val (key, record) = row(store, request)

      Response.Ok(
        page(
          s"Edit $modelName",
          h1(s"Edit $modelName"),
          shape.render(member(key), Method.PUT, Some(record)),
          backToIndex
        )
      )
    }

    def update(store: Store): Handler = request => {
      val key = request.param[Id[A]]("id")
      submit(request, key, s"Edit $modelName", member(key), Method.PUT) { record =>
        store.update(plural, key, record)
      }
    }

    def destroy(store: Store): Handler = request => {
      val key = request.param[Id[A]]("id")
      if (!store.delete[A](plural, key)) throw NotFound(request.path)
      Response.Redirect(collection)
    }

    /** A control is rendered only when the route it points at is mounted. Subtracting `Destroy`
      * removes the delete button as well as the route, so a page never offers a 405.
      */
    def when(action: Action)(content: => Html): Seq[Html] =
      if (actions.has(action)) Seq(content) else Nil

    def backToIndex: Seq[Html] =
      when(Action.Index)(p(a(Attrs.href := collection, s"All $plural")))

    /** A well-formed submission that failed a field is a 422, and it is **returned** rather than
      * thrown: it carries the re-rendered form, so it must not reach `Boundary`'s problem page. A
      * 400 would be the wrong fact — that is what a request eezo cannot decode gets.
      */
    def rejected(heading: String, rendered: Html): Response =
      Response(
        422,
        Seq(Response.HtmlContentType),
        // The whole page the form was already on, heading and all. Coming back to a bare form
        // reads as a different screen, which is the opposite of what returning the typing is for.
        Body.Html(page(heading, h1(heading), rendered, backToIndex))
      )

    new Resource[A] {

      /** Every route here is marked [[Provenance.Derived]], and this is the only place in eezo that
        * marks one. That is what lets a user mount `GET /$plural` by hand and keep the other six
        * pages: the table drops the derived twin rather than refusing to boot.
        */
      def routes(store: Store): Seq[Route] = {
        def route(method: Method, path: String, handler: Handler): Route =
          Route.Http(method, PathPattern.parse(path), handler, Provenance.Derived)

        Action.values.toSeq.filter(actions.has).map {
          case Action.Index => route(Method.GET, collection, index(store))
          case Action.New   => route(Method.GET, s"$collection/${NewPage.segment}", blank)
          case Action.Show  => route(Method.GET, s"$collection/:id", show(store))
          case Action.Edit => route(Method.GET, s"$collection/:id/${EditPage.segment}", edit(store))
          case Action.Create  => route(NewPage.targetMethod, collection, create(store))
          case Action.Update  => route(EditPage.targetMethod, s"$collection/:id", update(store))
          case Action.Destroy => route(Method.DELETE, s"$collection/:id", destroy(store))
        }
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
    * the whole picture, logged in one place, `Eezo.start`'s `announce`. The value is the assembled
    * [[RouteTable]] rather than an [[Actions]], because `Actions[A]` describes one model in
    * isolation and the 405 is a property of the application. A user who subtracts `Update` and
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
