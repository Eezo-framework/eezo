package io.eezo.core.html

/** eezo's HTML node tree.
  *
  * Four cases and no more. There is no `Comment` case, because nothing eezo renders emits one and
  * [[Html.raw]] covers it if something ever does, and no `Doctype` case, because [[Html.doctype]]
  * is a `Raw` the caller puts first. A case that may legally appear at exactly one position in the
  * tree is a case every `match` in `modules/live` has to handle and then ignore.
  */
enum Html {

  /** Text that is **already escaped**. The constructor is package private, so [[Html.text]] is the
    * only way to build one from outside `io.eezo.core.html`, while the pattern match that the
    * differ in `modules/live` needs still compiles from anywhere.
    */
  case Text private[html] (escaped: String)

  /** The single unescaped path into the tree.
    *
    * Mounting stops at its edge. `Route.under` moves a [[Url.Mounted]] wherever one sits in an
    * attribute, and raw content is a string eezo never parses, so a link written inside it keeps
    * whatever it says and stays where the author put it.
    */
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

  /** This tree as seen from under `prefix`: every [[Url.Mounted]] sitting in an attribute takes the
    * prefix, and stays mounted so that a second layer can move it again.
    *
    * `private[eezo]` because `Response.under` is the one caller, walking the page of a response a
    * mounted route is about to return, and a mount is a property of where routes are served rather
    * than something a view decides for itself.
    */
  private[eezo] def under(prefix: String): Html = transform {
    case Element(name, attrs, key, children) =>
      Element(name, attrs.map(_.under(prefix)), key, children)
    // Text carries no address, and raw markup is a string eezo never parses.
    case leaf => leaf
  }

  /** This tree rebuilt top down: `f` sees a node before its children, and the walk descends into
    * whatever `f` returned, through `Element` and `Fragment` alike. `Text` and `Raw` are leaves.
    *
    * The one walk both [[under]] and the dev server's reload injection are written over, so a fifth
    * case would be added here once rather than in every caller's match.
    */
  private[eezo] def transform(f: Html => Html): Html = f(this) match {
    case Element(name, attrs, key, children) =>
      Element(name, attrs, key, children.map(_.transform(f)))
    case Fragment(children) => Fragment(children.map(_.transform(f)))
    case leaf               => leaf
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
            put(Html.escape(v.text))
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

/** An attribute. The value is optional rather than a plain [[AttrValue]] so that `value := ""` and
  * `disabled` stay distinct: `None` renders the bare name, `Some(Literal(""))` renders `value=""`.
  * Collapsing the two would make an empty text input indistinguishable from a boolean flag.
  *
  * The constructor is package private, the same shape as [[Html.Text]], so `:=` on an [[AttrName]]
  * is the only way to build one from outside `io.eezo.core.html`, while the pattern match that the
  * differ in `modules/live` needs still compiles from anywhere. Pairing a name of a call site's own
  * choosing with an [[AttrValue.Link]] is what would otherwise reopen the set of url bearing names
  * that [[Attrs]] closes.
  */
final case class Attr private[html] (name: String, value: Option[AttrValue]) {

  /** Unchanged unless this attribute carries a [[Url]], which is what keeps a handwritten `String`
    * link exactly where its author wrote it.
    */
  private[eezo] def under(prefix: String): Attr = value match {
    case Some(AttrValue.Link(url)) => copy(value = Some(AttrValue.Link(url.under(prefix))))
    case _                         => this
  }
}

/** What an attribute carries.
  *
  * Two cases because a mount has to tell them apart: text is finished, and a [[Url]] is a value
  * `Route.under` can still move. Only the url bearing names build a [[Link]], so the arm exists
  * exactly where prefixing an address is meaningful.
  */
enum AttrValue {

  case Literal(value: String)

  /** An address a mount may still move. The constructor is package private, so `:=` on a
    * [[UrlAttrName]] is the only way to build one from outside `io.eezo.core.html`, while the
    * pattern match [[Attr.under]] and the differ in `modules/live` need still compiles from
    * anywhere.
    */
  case Link private[html] (url: Url)

  /** The one spelling of an attribute value, so that a [[Link]] cannot reach the output down a path
    * that skips [[Html.escape]]. An unresolved [[Url.Mounted]] flattens to its bare payload: a page
    * rendered outside any mount is a page at no prefix.
    */
  private[eezo] def text: String = this match {
    case Literal(value) => value
    case Link(url)      => url.path
  }
}
