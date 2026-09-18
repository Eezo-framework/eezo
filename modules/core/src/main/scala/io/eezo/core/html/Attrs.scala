package io.eezo.core.html

/** The attribute names, qualified rather than wildcard imported, because they collide with the tags
  * on `title`, `style`, `label`, `form`, `value`, `name`, `data` and `span`.
  *
  * The three Scala keywords are spelled `cls`, `tpe` and `htmlFor` rather than backticked, because
  * a backticked `` `for` `` is a papercut on every label in every form, and forms are the
  * framework's most written markup.
  */
object Attrs {

  // Global
  val id: AttrName     = AttrName("id")
  val cls: AttrName    = AttrName("class")
  val style: AttrName  = AttrName("style")
  val title: AttrName  = AttrName("title")
  val lang: AttrName   = AttrName("lang")
  val role: AttrName   = AttrName("role")
  val hidden: AttrName = AttrName("hidden")

  // Links and media
  val href: UrlAttrName = UrlAttrName("href")
  val src: UrlAttrName  = UrlAttrName("src")

  /** The live mount's base marker, `private[eezo]` because it is the framework's own: it carries
    * `Url.Mounted("/")` so that `Route.under` moves it with the page, and the live client reports
    * the moved value at join — which is how re-renders on the server learn the prefix the DOM was
    * rewritten with (design/live.md §2.6). A fourth url bearing name, minted in the one place
    * allowed to mint them.
    */
  private[eezo] val eezoBase: UrlAttrName = UrlAttrName("data-eezo-base")
  val alt: AttrName                       = AttrName("alt")
  val rel: AttrName                       = AttrName("rel")
  val target: AttrName                    = AttrName("target")
  val width: AttrName                     = AttrName("width")
  val height: AttrName                    = AttrName("height")
  val charset: AttrName                   = AttrName("charset")
  val content: AttrName                   = AttrName("content")

  // Forms
  val action: UrlAttrName   = UrlAttrName("action")
  val method: AttrName      = AttrName("method")
  val name: AttrName        = AttrName("name")
  val value: AttrName       = AttrName("value")
  val tpe: AttrName         = AttrName("type")
  val htmlFor: AttrName     = AttrName("for")
  val placeholder: AttrName = AttrName("placeholder")
  val checked: AttrName     = AttrName("checked")
  val selected: AttrName    = AttrName("selected")
  val disabled: AttrName    = AttrName("disabled")
  val readonly: AttrName    = AttrName("readonly")
  val required: AttrName    = AttrName("required")
  val multiple: AttrName    = AttrName("multiple")
  val rows: AttrName        = AttrName("rows")
  val cols: AttrName        = AttrName("cols")
  val size: AttrName        = AttrName("size")
  val maxlength: AttrName   = AttrName("maxlength")
  val min: AttrName         = AttrName("min")
  val max: AttrName         = AttrName("max")
  val step: AttrName        = AttrName("step")
  val autofocus: AttrName   = AttrName("autofocus")

  // Tables
  val colspan: AttrName = AttrName("colspan")
  val rowspan: AttrName = AttrName("rowspan")
  val scope: AttrName   = AttrName("scope")

  /** An arbitrary attribute, for the ones this list does not carry.
    *
    * A plain [[AttrName]], even when the name spelled here is `href`. A [[Url]] moves under a
    * mount, and which names a mount is allowed to rewrite is a decision this list makes once rather
    * than one a string handed in at a call site can reopen. The package private constructors of
    * [[Attr]] and [[AttrValue.Link]] are what hold that shut: outside `io.eezo.core.html`, `:=` on
    * one of the [[UrlAttrName]] values above is the only expression that produces an attribute a
    * mount rewrites.
    */
  def attr(name: String): AttrName = AttrName(name)

  /** A `data-*` attribute. */
  def data(suffix: String): AttrName = AttrName(s"data-$suffix")
}
