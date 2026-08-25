package io.eezo.core.html

/** The tags. Wildcard imported, because a view file mentions far more distinct tags than distinct
  * attributes; [[Attrs]] stays qualified, because the two collide on `title`, `style`, `label`,
  * `form`, `value`, `name`, `data` and `span`.
  *
  * Coverage is the floor the seven derived routes need: document, structure, text, lists, forms,
  * media and tables, which is everything they render.
  */
object Tags {

  // Document
  val html: Tag   = Tag("html")
  val head: Tag   = Tag("head")
  val body: Tag   = Tag("body")
  val title: Tag  = Tag("title")
  val meta: Tag   = Tag("meta")
  val link: Tag   = Tag("link")
  val style: Tag  = Tag("style")
  val script: Tag = Tag("script")
  val base: Tag   = Tag("base")

  // Structure
  val div: Tag     = Tag("div")
  val span: Tag    = Tag("span")
  val header: Tag  = Tag("header")
  val footer: Tag  = Tag("footer")
  val main: Tag    = Tag("main")
  val nav: Tag     = Tag("nav")
  val section: Tag = Tag("section")
  val article: Tag = Tag("article")
  val aside: Tag   = Tag("aside")
  val hr: Tag      = Tag("hr")

  // Text
  val h1: Tag         = Tag("h1")
  val h2: Tag         = Tag("h2")
  val h3: Tag         = Tag("h3")
  val h4: Tag         = Tag("h4")
  val h5: Tag         = Tag("h5")
  val h6: Tag         = Tag("h6")
  val p: Tag          = Tag("p")
  val a: Tag          = Tag("a")
  val strong: Tag     = Tag("strong")
  val em: Tag         = Tag("em")
  val small: Tag      = Tag("small")
  val code: Tag       = Tag("code")
  val pre: Tag        = Tag("pre")
  val br: Tag         = Tag("br")
  val blockquote: Tag = Tag("blockquote")

  // Lists
  val ul: Tag = Tag("ul")
  val ol: Tag = Tag("ol")
  val li: Tag = Tag("li")
  val dl: Tag = Tag("dl")
  val dt: Tag = Tag("dt")
  val dd: Tag = Tag("dd")

  // Forms
  val form: Tag     = Tag("form")
  val fieldset: Tag = Tag("fieldset")
  val legend: Tag   = Tag("legend")
  val label: Tag    = Tag("label")
  val input: Tag    = Tag("input")
  val textarea: Tag = Tag("textarea")
  val select: Tag   = Tag("select")
  val option: Tag   = Tag("option")
  val button: Tag   = Tag("button")

  // Media
  val img: Tag        = Tag("img")
  val figure: Tag     = Tag("figure")
  val figcaption: Tag = Tag("figcaption")
  val video: Tag      = Tag("video")
  val audio: Tag      = Tag("audio")
  val source: Tag     = Tag("source")

  // Tables
  val table: Tag   = Tag("table")
  val thead: Tag   = Tag("thead")
  val tbody: Tag   = Tag("tbody")
  val tfoot: Tag   = Tag("tfoot")
  val tr: Tag      = Tag("tr")
  val th: Tag      = Tag("th")
  val td: Tag      = Tag("td")
  val caption: Tag = Tag("caption")
}

export Tags.*
