package eezo.core.html

/** eezo's HTML node tree.
  *
  * Four cases and no more. There is no `Comment` case, because nothing eezo renders emits one and
  * [[Html.raw]] covers it if something ever does, and no `Doctype` case, because [[Html.doctype]]
  * is a `Raw` the caller puts first. A case that may legally appear at exactly one position in the
  * tree is a case every `match` in `modules/live` has to handle and then ignore.
  */
enum Html {

  /** Text that is **already escaped**. The constructor is package private, so [[Html.text]] is the
    * only way to build one from outside `eezo.core.html`, while the pattern match that the differ
    * in `modules/live` needs still compiles from anywhere.
    */
  case Text private[html] (escaped: String)

  /** The single unescaped path into the tree. */
  case Raw(html: String)

  /** An element. Void ness is a property of the tag *name*, looked up in an internal void tag table
    * at render time, so `Element("br", …, void = false)` is not a state that exists.
    *
    * `key` is a field rather than an ordinary attribute because `modules/live` addresses list
    * children by identity, and a requirement one module places on another belongs in a type rather
    * than in a string two modules agree on by convention. It renders as `data-eezo-key`.
    */
  case Element(
      name: String,
      attrs: Vector[Attr],
      key: Option[String],
      children: Vector[Html]
  )

  /** Several nodes with no element of their own.
    *
    * A `Fragment` renders its children and nothing of its own, so one tree child can become several
    * DOM nodes, and every index after it would run ahead of the tree if the fragment were left in
    * place. For example, `div(span("a"), text("x") ++ text("y"), span("z"))` holds four children,
    * not three, and renders `<div><span>a</span>xy<span>z</span></div>`; left unspliced, the two
    * text nodes would sit behind one tree child. [[Tag.apply]] is the guard: it splices a
    * `Fragment` child into its parent's children, so an `Element` built through a tag never
    * contains one and tree child index equals DOM child index. That guard is `Tag.apply`'s alone;
    * building an `Element` by hand with a `Fragment` among its children, or nesting one `Fragment`
    * inside another before it reaches a tag, sits outside it. The case survives as a root level
    * value, where there is no parent to index into.
    */
  case Fragment(children: Vector[Html])

  /** Concatenation, flattening adjacent fragments rather than nesting them. */
  def ++(that: Html): Html = (this, that) match {
    case (Fragment(a), Fragment(b)) => Fragment(a ++ b)
    case (Fragment(a), b)           => Fragment(a :+ b)
    case (a, Fragment(b))           => Fragment(a +: b)
    case (a, b)                     => Fragment(Vector(a, b))
  }

  /** The rendered markup, as a `String`. The HTTP boundary encodes it as UTF-8. */
  def render: String = {
    val sb = new StringBuilder
    renderTo(sb)
    sb.result()
  }

  /** Renders into an existing builder. Internal, so that a streaming writer can arrive later for
    * the reserved `Body.Stream` case without a second public renderer.
    */
  private[eezo] def renderTo(sb: StringBuilder): Unit = {
    def put(s: String): Unit = { val _ = sb.append(s) }

    this match {
      case Text(escaped) => put(escaped)
      case Raw(html)     => put(html)

      case Element(name, attrs, key, children) =>
        put("<")
        put(name)
        attrs.foreach { attr =>
          put(" ")
          put(attr.name)
          attr.value.foreach { v =>
            put("=\"")
            put(Html.escape(v))
            put("\"")
          }
        }
        key.foreach { k =>
          put(" data-eezo-key=\"")
          put(Html.escape(k))
          put("\"")
        }
        put(">")
        if (!Html.VoidTags.contains(name)) {
          children.foreach(_.renderTo(sb))
          put("</")
          put(name)
          put(">")
        }

      case Fragment(children) => children.foreach(_.renderTo(sb))
    }
  }
}

object Html {

  /** The only door from plain text into the tree. */
  def text(value: String): Html = Text(escape(value))

  /** Markup that is inserted verbatim. The caller owns its safety. */
  def raw(html: String): Html = Raw(html)

  /** Nothing. Renders to the empty string and disappears into any parent. */
  val empty: Html = Fragment(Vector.empty)

  /** The document type declaration, which a page puts first. */
  val doctype: Html = Raw("<!DOCTYPE html>")

  /** Renders `body` when `cond` holds, and nothing otherwise. There is no `unless`: `when(!cond)`
    * is the same length and one concept instead of two.
    */
  def when(cond: Boolean)(body: => Html): Html = if (cond) body else empty

  /** The tags a browser parses as self closing. They render `<br>`, not `<br/>`: the XHTML spelling
    * is not what an HTML parser is reading.
    *
    * `private[eezo]`, the same reach as [[Html.renderTo]], because void ness is a fact
    * `modules/live`'s differ will need too: a void `Element` with non empty `children` renders none
    * of them, so the differ has to know void ness to keep tree child index equal to DOM child
    * index. Nothing outside `renderTo` reads this table today, so a public `Set[String]` would be
    * an API commitment with no caller asking for it.
    */
  private[eezo] val VoidTags: Set[String] =
    Set(
      "area",
      "base",
      "br",
      "col",
      "embed",
      "hr",
      "img",
      "input",
      "link",
      "meta",
      "param",
      "source",
      "track",
      "wbr"
    )

  /** Escapes the five characters that can break out of a text or an attribute value.
    *
    * One function for both positions. Text strictly needs `<` and `&`, an attribute value `&` and
    * `"`, but over escaping is inert in a browser, one function is the thing a reviewer checks
    * once, and a context sensitive split is a rule that eventually gets applied to the wrong
    * context.
    */
  private[html] def escape(value: String): String = {
    val sb = new StringBuilder(value.length)
    value.foreach {
      case '<'  => sb.append("&lt;")
      case '>'  => sb.append("&gt;")
      case '&'  => sb.append("&amp;")
      case '"'  => sb.append("&quot;")
      case '\'' => sb.append("&#39;")
      case c    => sb.append(c)
    }
    sb.result()
  }
}

/** An attribute. The value is optional rather than a plain `String` so that `value := ""` and
  * `disabled` stay distinct: `None` renders the bare name, `Some("")` renders `value=""`.
  * Collapsing the two would make an empty text input indistinguishable from a boolean flag.
  */
final case class Attr(name: String, value: Option[String])
