package io.eezo.core.html

/** The attribute names. Import them qualified, as `Attrs.cls`, because they share names with the
  * tags: `title`, `style`, `label`, `form`, `value`, `name`, `data` and `span`.
  *
  * The three Scala keywords are spelled `cls`, `tpe` and `htmlFor`.
  */
object Attrs {

  val id: AttrName     = AttrName("id")
  val cls: AttrName    = AttrName("class")
  val style: AttrName  = AttrName("style")
  val title: AttrName  = AttrName("title")
  val lang: AttrName   = AttrName("lang")
  val role: AttrName   = AttrName("role")
  val hidden: AttrName = AttrName("hidden")

  val href: UrlAttrName = UrlAttrName("href")
  val src: UrlAttrName  = UrlAttrName("src")

  private[eezo] val eezoBase: UrlAttrName = UrlAttrName("data-eezo-base")
  val alt: AttrName                       = AttrName("alt")
  val rel: AttrName                       = AttrName("rel")
  val target: AttrName                    = AttrName("target")
  val width: AttrName                     = AttrName("width")
  val height: AttrName                    = AttrName("height")
  val charset: AttrName                   = AttrName("charset")
  val content: AttrName                   = AttrName("content")

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

  val colspan: AttrName = AttrName("colspan")
  val rowspan: AttrName = AttrName("rowspan")
  val scope: AttrName   = AttrName("scope")

  /** An arbitrary attribute, for the ones this list does not carry.
    *
    * It is a plain [[AttrName]], also when the name is `href`, so a mount never rewrites its value.
    * Only the [[UrlAttrName]] values of this list make an attribute that a mount rewrites.
    */
  def attr(name: String): AttrName = AttrName(name)

  /** A `data-*` attribute. */
  def data(suffix: String): AttrName = AttrName(s"data-$suffix")
}
