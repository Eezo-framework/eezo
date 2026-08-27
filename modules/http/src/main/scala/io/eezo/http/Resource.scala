package io.eezo.http

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, error, summonFrom}
import scala.deriving.Mirror

import io.eezo.core.Id
import io.eezo.core.html.{Attrs, Html, Mod}
import io.eezo.core.html.Tags.*
import io.eezo.core.internal.util.snake

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

    def create(store: Store): Handler = request => {
      val key = Id.gen[A]()
      shape.parse(request.form, Some(key.show)) match {
        case Left(errors) =>
          rejected(
            s"New $modelName",
            shape.render(collection, Method.POST, None, errors, request.form)
          )
        case Right(record) =>
          store.insert(plural, key, record)
          Response.Redirect(afterWrite(key))
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
      shape.parse(request.form, Some(key.show)) match {
        case Left(errors) =>
          rejected(
            s"Edit $modelName",
            shape.render(member(key), Method.PUT, None, errors, request.form)
          )
        case Right(record) =>
          if (!store.update(plural, key, record)) throw NotFound(request.path)
          Response.Redirect(afterWrite(key))
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

      def routes(store: Store): Seq[Route] =
        Action.values.toSeq.filter(actions.has).map {
          case Action.Index => Route.Http(Method.GET, PathPattern.parse(collection), index(store))
          case Action.New   =>
            Route.Http(Method.GET, PathPattern.parse(s"$collection/new"), blank)
          case Action.Show =>
            Route.Http(Method.GET, PathPattern.parse(s"$collection/:id"), show(store))
          case Action.Edit =>
            Route.Http(Method.GET, PathPattern.parse(s"$collection/:id/edit"), edit(store))
          case Action.Create =>
            Route.Http(Method.POST, PathPattern.parse(collection), create(store))
          case Action.Update =>
            Route.Http(Method.PUT, PathPattern.parse(s"$collection/:id"), update(store))
          case Action.Destroy =>
            Route.Http(Method.DELETE, PathPattern.parse(s"$collection/:id"), destroy(store))
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
