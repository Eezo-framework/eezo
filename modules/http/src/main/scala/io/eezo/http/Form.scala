package io.eezo.http

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, constValueTuple, summonAll}
import scala.deriving.Mirror

import io.eezo.core.html.{Attrs, Html, Url}
import io.eezo.core.html.Tags.*

/** One field, as the view side names it.
  *
  * `name` is the exact Scala label, because that is what the input carries and what `parse` reads
  * back; `label` is humanised for people. This is published rather than internal on purpose: it
  * makes `Form[A]` the single source of a model's presentation vocabulary, so a derived `show` or
  * `index` reads its column headings here rather than from `Table[A].columns`, whose names are
  * snake case and belong to the database side.
  */
final case class FormField(name: String, label: String, inputType: String)

/** One thing wrong with one field. */
final case class FieldError(name: String, message: String)

/** Everything wrong with a submission.
  *
  * List shaped from the first day even though nothing yet produces more than a decode failure and
  * `"is required"`, because validation rules arrive later and a `Map[String, String]` would have to
  * change signature to hold two problems with one field.
  */
final case class FormErrors(errors: Seq[FieldError]) {
  def isEmpty: Boolean              = errors.isEmpty
  def of(name: String): Seq[String] = errors.collect { case e if e.name == name => e.message }
}

object FormErrors {
  val empty: FormErrors = FormErrors(Nil)
}

/** Renders a case class as an HTML form, and reads one back.
  *
  * `Form` emits the **whole** `<form>` element rather than a bag of inputs, because it is the one
  * place that knows both the target verb and the encoding, and so it is the only place that can own
  * the `_method` override. A `Resource` hands it `Method.PUT` without knowing the override exists.
  *
  * The key is never rendered. A form that carried its own key would either need a placeholder value
  * meaning "not set", which is a real value the server cannot tell from a real one, or a hidden
  * input, which the browser lets the user edit. Instead `parse` takes the key as raw text from the
  * caller: `Id.gen().show` on create, the path parameter on update, decoded through the key field's
  * own `Field` so there is one decoder rather than two.
  */
@implicitNotFound(
  "No Form instance for ${A}.\n" +
    "Add `derives Form` to its declaration:\n" +
    "  case class ${A}(...) derives Form"
)
trait Form[A] {

  /** Declaration order, with the key absent. */
  def fields: Seq[FormField]

  /** One record's fields paired with their values, as a `show` page renders them.
    *
    * The values come from the same [[Field.show]] the inputs use, so a show page and the edit form
    * for the same record cannot disagree about one field's text. The alternative, reading
    * `productElement(i).toString`, prints `Some(3)` for an `Option` field and disagrees on the
    * spot. The key is absent, exactly as it is from [[fields]].
    */
  def show(value: A): Seq[(FormField, String)]

  /** The whole `<form>`, including its submit button and, when the verb needs one, `_method`.
    *
    * `raw` is submitted text, winning field by field over `value`. A rejected submission has no `A`
    * to render from, which is why it was rejected, so without `raw` the user gets an empty form
    * back with error messages beside it and their typing gone.
    *
    * `action` is a union rather than two overloads because only one alternative of an overloaded
    * method may carry default arguments, and `errors` and `raw` have them. A `String` is a finished
    * address; a [[Url.Mounted]] is one that still travels with `Route.under`.
    */
  def render(
      action: Url | String,
      method: Method,
      value: Option[A],
      errors: FormErrors = FormErrors.empty,
      raw: Map[String, Seq[String]] = Map.empty
  ): Html

  /** Reads a submission.
    *
    * `key` is the caller's, and is required exactly when the model has a key field. Extra keys in
    * `data` are ignored, which is what lets `_method` and a later CSRF token travel in the same
    * body without either being declared here.
    *
    * @throws BadRequest
    *   if the key is present but will not decode.
    * @throws IllegalArgumentException
    *   if the model has a key and the caller supplied none. That is a bug in the caller rather than
    *   bad input, so it is not a `FormErrors`.
    */
  def parse(data: Map[String, Seq[String]], key: Option[String]): Either[FormErrors, A]
}

object Form {

  def apply[A](using f: Form[A]): Form[A] = f

  /** The hidden input a form needs when its verb is one a browser cannot issue, and nothing at all
    * when it is not.
    *
    * Two places emit it — the `<form>` below, and the delete button a derived `show` page renders,
    * since a browser cannot issue `DELETE` from a link either — and one place reads it,
    * [[Request.withMethodOverride]]. Written out twice, the field name would live in three files
    * and each copy would be pinned by its own test, which is how two of them agree and the third
    * drifts.
    */
  private[http] def methodOverride(method: Method): Seq[Html] = method match {
    case Method.GET | Method.POST => Nil
    case other                    =>
      Seq(
        input(
          Attrs.tpe   := "hidden",
          Attrs.name  := Request.MethodField,
          Attrs.value := other.toString
        )
      )
  }

  /** `inline` only long enough to read the `Mirror`, then straight into [[make]].
    *
    * Returning an anonymous class from an `inline def` duplicates its definition at every call
    * site, which `research/derivation-design.md` §6.3 rules out for exactly this reason. The
    * anonymous class lives in `make`, which is compiled once.
    */
  inline def derived[A](using m: Mirror.ProductOf[A]): Form[A] =
    make[A](
      constValue[m.MirroredLabel],
      constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String]),
      summonAll[Tuple.Map[m.MirroredElemTypes, Field]].toList.asInstanceOf[List[Field[Any]]],
      values => m.fromProduct(Tuple.fromArray(values))
    )

  /** The key is the field literally named `id`, matching what `db`'s `TableMacro` enforces. Located
    * by name and not by type, and optional: `Login(email, password)` has none, forms, and parses.
    */
  private val KeyName = "id"

  private def make[A](
      modelName: String,
      labels: List[String],
      instances: List[Field[Any]],
      build: Array[Any] => A
  ): Form[A] = {
    val keyIndex = labels.indexOf(KeyName)

    val visible: Seq[(FormField, Int)] =
      labels.zip(instances).zipWithIndex.collect {
        case ((name, field), i) if i != keyIndex =>
          FormField(name, humanise(name), field.inputType) -> i
      }

    new Form[A] {

      val fields: Seq[FormField] = visible.map(_._1)

      def show(value: A): Seq[(FormField, String)] =
        visible.map { case (f, i) => f -> text(value, i) }

      private def text(value: A, i: Int): String =
        instances(i).show(value.asInstanceOf[Product].productElement(i))

      def render(
          action: Url | String,
          method: Method,
          value: Option[A],
          errors: FormErrors,
          raw: Map[String, Seq[String]]
      ): Html = {
        val over = Form.methodOverride(method)

        val rows = visible.map { case (f, i) =>
          val current  = raw.get(f.name).flatMap(_.headOption).orElse(value.map(text(_, i)))
          val messages = errors.of(f.name)

          val control =
            if (f.inputType == "checkbox")
              input(
                Attrs.tpe     := "checkbox",
                Attrs.id      := f.name,
                Attrs.name    := f.name,
                Attrs.checked := current.exists(_.nonEmpty)
              )
            else
              input(
                Attrs.tpe   := f.inputType,
                Attrs.id    := f.name,
                Attrs.name  := f.name,
                Attrs.value := current.getOrElse("")
              )

          div(
            label(Attrs.htmlFor := f.name, f.label),
            control,
            messages.map(m => p(Attrs.cls := "error", m))
          )
        }

        form(
          // `Response.asUrl` rather than a match on the union here: a `String` action is a finished
          // address, which is what `Url.Absolute` means, and one place in the package decides that.
          Attrs.action := Response.asUrl(action),
          Attrs.method := (if (method == Method.GET) "get" else "post"),
          over,
          rows,
          button(Attrs.tpe := "submit", "Save")
        )
      }

      def parse(data: Map[String, Seq[String]], key: Option[String]): Either[FormErrors, A] = {
        val values = new Array[Any](labels.size)
        val errors = Seq.newBuilder[FieldError]

        labels.zip(instances).zipWithIndex.foreach { case ((name, field), i) =>
          if (i == keyIndex) {
            val raw = key.getOrElse(
              throw new IllegalArgumentException(
                s"$modelName has a key field `$name`, so parse needs the caller's key: " +
                  "Id.gen().show on create, the path parameter on update."
              )
            )
            field.read(raw) match {
              case Right(v)  => values(i) = v
              case Left(why) => throw BadRequest(s"$name $why")
            }
          } else
            data.get(name).flatMap(_.headOption) match {
              case Some(text) =>
                field.read(text) match {
                  case Right(v)  => values(i) = v
                  case Left(why) => errors += FieldError(name, why)
                }
              case None =>
                field.absent match {
                  case Some(v) => values(i) = v
                  case None    => errors += FieldError(name, "is required")
                }
            }
        }

        val found = errors.result()
        if (found.nonEmpty) Left(FormErrors(found)) else Right(build(values))
      }
    }
  }

  /** `publishedOn` becomes `Published on`. Deliberately dumb, in the same spirit as #110's
    * inflector: no dictionary, no acronym table, and a companion override is the escape hatch when
    * it reads badly.
    */
  private def humanise(name: String): String = {
    val spaced = name
      .foldLeft(new StringBuilder) { (b, ch) =>
        if (ch.isUpper && b.nonEmpty) { val _ = b += ' ' }
        b += ch.toLower
      }
      .toString
    if (spaced.isEmpty) spaced else spaced.head.toUpper +: spaced.tail
  }
}

/** Decoding a request body into a model.
  *
  * A thin extension over [[Form.parse]] and nothing else, so the form's decoder and the request's
  * decoder cannot disagree about what a submission means.
  */
extension (req: Request) {

  /** For a model with no key field. */
  def as[A](using f: Form[A]): Either[FormErrors, A] = f.parse(req.form, None)

  /** For a model with one, where the caller owns the key. */
  def as[A](key: String)(using f: Form[A]): Either[FormErrors, A] = f.parse(req.form, Some(key))
}
