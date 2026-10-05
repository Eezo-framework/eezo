package site

import io.eezo.core.html.*

/** A code block as the page carries it: the text, escaped, in a `<code>` that names its language
  * the way highlight.js reads it. The colouring is highlight.js's, run in the browser by the one
  * line of script the site has; the server only says which language each block is in.
  */
object CodeBlock {

  /** The languages the docs write, by the name a fence uses, to the name highlight.js knows. A
    * fence naming anything else is left plain rather than handed to a grammar that is not loaded.
    */
  private val Languages: Map[String, String] = Map(
    "scala"      -> "scala",
    "java"       -> "java",
    "bash"       -> "bash",
    "sh"         -> "bash",
    "shell"      -> "bash",
    "zsh"        -> "bash",
    "console"    -> "bash",
    "sql"        -> "sql",
    "postgresql" -> "sql",
    "json"       -> "json",
    "python"     -> "python",
    "py"         -> "python",
    "toml"       -> "ini",
    "ini"        -> "ini",
    "nginx"      -> "nginx",
    "yaml"       -> "yaml",
    "xml"        -> "xml",
    "html"       -> "xml",
    "javascript" -> "javascript",
    "js"         -> "javascript",
    "css"        -> "css",
    "diff"       -> "diff",
    "dockerfile" -> "dockerfile"
  )

  /** The fence's first word, lower case, which is what the label shows. */
  def name(info: String): String = info.trim.takeWhile(!_.isWhitespace).toLowerCase

  /** The class highlight.js dispatches on, or `nohighlight` for a block it should leave alone. */
  def cls(info: String): String =
    Languages.get(name(info)).map(l => s"language-$l").getOrElse("nohighlight")

  def render(info: String, code: String): Html = {
    val label = name(info) match {
      case ""    => "text"
      case other => other
    }
    div(
      Attrs.cls          := "codeblock",
      Attrs.data("lang") := label,
      pre(Tags.code(Attrs.cls := cls(info), code.stripSuffix("\n")))
    )
  }
}
