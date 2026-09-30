package site

import scala.annotation.tailrec

import io.eezo.core.html.*

/** Syntax colouring for the code blocks, done on the server so the page ships no highlighter.
  *
  * One tokenizer, driven by a small description per language: what a comment looks like, what a
  * string looks like, which words are keywords, and a few switches for the things one family of
  * languages has and another does not, a shell's command position, TOML's keys, Scala's types by
  * case. It is deliberately shallow. A docs page wants its keywords, strings and comments told
  * apart and nothing more, and a grammar that tried harder would be wrong more often.
  */
object Highlight {

  enum Kind {
    case Plain, Comment, Str, Num, Kw, Type, Punct, Ann, Cmd, Var, Flag, Prompt, Key, Section
  }

  final case class Token(kind: Kind, text: String)

  /** What the tokenizer knows about a language. */
  final case class Lang(
      name: String,
      keywords: Set[String] = Set.empty,
      lineComments: Vector[String] = Vector.empty,
      blockComment: Option[(String, String)] = None,
      quotes: Set[Char] = Set.empty,
      tripleQuotes: Boolean = false,
      typesByCase: Boolean = false,
      dollarVars: Boolean = false,
      commandAfter: Option[Set[Char]] = None,
      flags: Boolean = false,
      annotations: Boolean = false,
      caseInsensitive: Boolean = false,
      keys: Boolean = false,
      sections: Boolean = false,
      prompt: Boolean = false
  ) {
    def isKeyword(word: String): Boolean =
      keywords.contains(if (caseInsensitive) word.toLowerCase else word)
  }

  private val ScalaKeywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "using",
    "derives",
    "inline",
    "opaque",
    "transparent",
    "extension",
    "end",
    "as"
  )

  val Scala: Lang = Lang(
    name = "scala",
    keywords = ScalaKeywords,
    lineComments = Vector("//"),
    blockComment = Some("/*" -> "*/"),
    quotes = Set('"', '\''),
    tripleQuotes = true,
    typesByCase = true,
    annotations = true
  )

  val Java: Lang = Scala.copy(
    name = "java",
    keywords = ScalaKeywords ++ Set(
      "int",
      "long",
      "boolean",
      "void",
      "static",
      "public",
      "interface",
      "char",
      "byte",
      "short",
      "double",
      "float",
      "synchronized",
      "instanceof",
      "switch",
      "default",
      "break",
      "continue",
      "assert",
      "record",
      "var"
    ),
    tripleQuotes = true
  )

  val Shell: Lang = Lang(
    name = "bash",
    keywords = Set(
      "if",
      "then",
      "else",
      "elif",
      "fi",
      "for",
      "while",
      "until",
      "do",
      "done",
      "in",
      "case",
      "esac",
      "function",
      "export",
      "alias",
      "local",
      "return",
      "exit",
      "set",
      "unset",
      "source",
      "sudo",
      "exec"
    ),
    lineComments = Vector("#"),
    quotes = Set('"', '\''),
    dollarVars = true,
    commandAfter = Some(Set('|', ';', '&')),
    flags = true,
    prompt = true
  )

  val Sql: Lang = Lang(
    name = "sql",
    keywords = Set(
      "select",
      "from",
      "where",
      "insert",
      "into",
      "values",
      "update",
      "set",
      "delete",
      "create",
      "table",
      "index",
      "unique",
      "on",
      "drop",
      "alter",
      "add",
      "column",
      "not",
      "null",
      "primary",
      "key",
      "references",
      "default",
      "constraint",
      "check",
      "if",
      "exists",
      "schema",
      "begin",
      "commit",
      "rollback",
      "and",
      "or",
      "as",
      "order",
      "by",
      "limit",
      "offset",
      "join",
      "left",
      "right",
      "inner",
      "outer",
      "group",
      "having",
      "distinct",
      "returning",
      "integer",
      "text",
      "boolean",
      "uuid",
      "timestamp",
      "timestamptz",
      "serial",
      "varchar",
      "bigint",
      "numeric",
      "with",
      "recursive",
      "union",
      "all",
      "between",
      "like",
      "is",
      "in",
      "case",
      "when",
      "then",
      "else",
      "end",
      "true",
      "false",
      "cascade",
      "using",
      "type",
      "date",
      "jsonb",
      "bytea"
    ),
    lineComments = Vector("--"),
    blockComment = Some("/*" -> "*/"),
    quotes = Set('\''),
    caseInsensitive = true
  )

  val Toml: Lang = Lang(
    name = "toml",
    keywords = Set("true", "false"),
    lineComments = Vector("#"),
    quotes = Set('"', '\''),
    keys = true,
    sections = true
  )

  val Nginx: Lang = Lang(
    name = "nginx",
    lineComments = Vector("#"),
    quotes = Set('"', '\''),
    dollarVars = true,
    commandAfter = Some(Set(';', '{', '}'))
  )

  val Python: Lang = Lang(
    name = "python",
    keywords = Set(
      "def",
      "class",
      "return",
      "if",
      "elif",
      "else",
      "for",
      "while",
      "in",
      "not",
      "and",
      "or",
      "import",
      "from",
      "as",
      "with",
      "pass",
      "break",
      "continue",
      "lambda",
      "yield",
      "try",
      "except",
      "finally",
      "raise",
      "None",
      "True",
      "False",
      "is",
      "del",
      "global",
      "nonlocal",
      "assert",
      "async",
      "await"
    ),
    lineComments = Vector("#"),
    quotes = Set('"', '\''),
    tripleQuotes = true,
    typesByCase = true,
    annotations = true
  )

  val Json: Lang = Lang(
    name = "json",
    keywords = Set("true", "false", "null"),
    quotes = Set('"'),
    keys = true
  )

  val Plain: Lang = Lang(name = "text")

  /** The language a fence's info string names, and plain text for one it does not. */
  def language(info: String): Lang =
    info.trim.takeWhile(!_.isWhitespace).toLowerCase match {
      case "scala"                                     => Scala
      case "java"                                      => Java
      case "sh" | "bash" | "shell" | "zsh" | "console" => Shell
      case "sql" | "postgresql" | "postgres"           => Sql
      case "toml"                                      => Toml
      case "nginx"                                     => Nginx
      case "python" | "py"                             => Python
      case "json"                                      => Json
      case _                                           => Plain
    }

  /** A code block as the page shows it: the language named on the block for the label, and one
    * `span` per token that is not plain text.
    */
  def block(info: String, code: String): Html = {
    val lang    = language(info)
    val trimmed = code.stripSuffix("\n")
    val body    = tokens(lang, trimmed).map {
      case Token(Kind.Plain, text) => Html.text(text)
      case Token(kind, text)       => span(Attrs.cls := s"tk-${kind.toString.toLowerCase}", text)
    }
    div(
      Attrs.cls          := "codeblock",
      Attrs.data("lang") := lang.name,
      pre(Tags.code(Attrs.cls := s"language-${lang.name}", body))
    )
  }

  /** The tokens of `code` under `lang`, in order, concatenating back to `code` exactly. */
  def tokens(lang: Lang, code: String): Vector[Token] =
    if (lang == Plain) (if (code.isEmpty) Vector.empty else Vector(Token(Kind.Plain, code)))
    else scan(lang, code)

  private def scan(lang: Lang, code: String): Vector[Token] = {
    val n = code.length

    def at(i: Int): Char = if (i < n) code.charAt(i) else '\u0000'

    def startsWith(i: Int, s: String): Boolean = code.startsWith(s, i)

    def spanWhile(i: Int, p: Char => Boolean): Int = {
      @tailrec def go(j: Int): Int = if (j < n && p(code.charAt(j))) go(j + 1) else j
      go(i)
    }

    def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'
    def isIdentPart(c: Char): Boolean  = c.isLetterOrDigit || c == '_'
    def lineStart(i: Int): Boolean     = i == 0 || at(i - 1) == '\n'

    def nextNonSpace(i: Int): Char = at(spanWhile(i, c => c == ' ' || c == '\t'))

    /** The end of a string opened at `i` with `quote`, its closing quote included. */
    def stringEnd(i: Int, quote: Char): Int = {
      val triple = lang.tripleQuotes && startsWith(i, s"$quote$quote$quote")
      if (triple) {
        val close = code.indexOf(s"$quote$quote$quote", i + 3)
        if (close < 0) n else close + 3
      } else {
        val escapes                  = !(lang.commandAfter.isDefined && quote == '\'')
        @tailrec def go(j: Int): Int =
          if (j >= n) n
          else {
            val c = code.charAt(j)
            if (c == '\n') j
            else if (escapes && c == '\\') go(j + 2)
            else if (c == quote) j + 1
            else go(j + 1)
          }
        go(i + 1)
      }
    }

    /** One token from `i`, and whether the next word sits in command position. */
    def next(i: Int, atCommand: Boolean): (Token, Int, Boolean) = {
      val c = at(i)

      if (lang.prompt && lineStart(i) && startsWith(i, "$ "))
        (Token(Kind.Prompt, "$"), i + 1, true)
      else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        val end       = spanWhile(i, ch => ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
        val text      = code.substring(i, end)
        val newline   = text.contains('\n')
        val continued = newline && i > 0 && at(i - 1) == '\\'
        (Token(Kind.Plain, text), end, if (newline && !continued) true else atCommand)
      } else if (lang.lineComments.exists(startsWith(i, _))) {
        val end = spanWhile(i, _ != '\n')
        (Token(Kind.Comment, code.substring(i, end)), end, atCommand)
      } else if (lang.blockComment.exists { case (open, _) => startsWith(i, open) }) {
        val (open, close) = lang.blockComment.get
        val found         = code.indexOf(close, i + open.length)
        val end           = if (found < 0) n else found + close.length
        (Token(Kind.Comment, code.substring(i, end)), end, atCommand)
      } else if (lang.quotes.contains(c)) {
        val end  = stringEnd(i, c)
        val kind = if (lang.keys && nextNonSpace(end) == ':') Kind.Key else Kind.Str
        (Token(kind, code.substring(i, end)), end, false)
      } else if (c == '`' && lang.typesByCase) {
        val close = code.indexOf('`', i + 1)
        val end   = if (close < 0) n else close + 1
        (Token(Kind.Plain, code.substring(i, end)), end, atCommand)
      } else if (lang.sections && lineStart(i) && c == '[') {
        val end = spanWhile(i, _ != '\n')
        (Token(Kind.Section, code.substring(i, end)), end, atCommand)
      } else if (c.isDigit) {
        val end = spanWhile(i, ch => ch.isLetterOrDigit || ch == '.' || ch == '_')
        (Token(Kind.Num, code.substring(i, end)), end, false)
      } else if (isIdentStart(c)) {
        val end  = spanWhile(i, isIdentPart)
        val word = code.substring(i, end)
        val kind =
          if (lang.isKeyword(word)) Kind.Kw
          else if (lang.keys && nextNonSpace(end) == '=') Kind.Key
          else if (lang.commandAfter.isDefined && atCommand && at(end) == '=') Kind.Var
          else if (lang.commandAfter.isDefined && atCommand) Kind.Cmd
          else if (lang.typesByCase && c.isUpper) Kind.Type
          else Kind.Plain
        // A shell keyword such as `sudo` or `then` is followed by a command; an assignment
        // before a command keeps the command position for the word after it.
        val stillCommand = kind == Kind.Kw || kind == Kind.Var
        (Token(kind, word), end, if (lang.commandAfter.isDefined) stillCommand else false)
      } else if (lang.dollarVars && c == '$' && at(i + 1) == '{') {
        val close = code.indexOf('}', i)
        val end   = if (close < 0) n else close + 1
        (Token(Kind.Var, code.substring(i, end)), end, false)
      } else if (lang.dollarVars && c == '$' && isIdentPart(at(i + 1))) {
        val end = spanWhile(i + 1, isIdentPart)
        (Token(Kind.Var, code.substring(i, end)), end, false)
      } else if (lang.annotations && c == '@' && isIdentStart(at(i + 1))) {
        val end = spanWhile(i + 1, isIdentPart)
        (Token(Kind.Ann, code.substring(i, end)), end, false)
      } else if (
        lang.flags && c == '-' && (i == 0 || at(i - 1).isWhitespace) &&
        (at(i + 1) == '-' || at(i + 1).isLetter)
      ) {
        val end = spanWhile(i, ch => ch.isLetterOrDigit || ch == '-' || ch == '_')
        (Token(Kind.Flag, code.substring(i, end)), end, false)
      } else {
        val command = lang.commandAfter.exists(_.contains(c))
        val kind    = if ("{}()[]<>=+-*/%!&|^~?:;,.".contains(c)) Kind.Punct else Kind.Plain
        (Token(kind, c.toString), i + 1, if (command) true else atCommand)
      }
    }

    @tailrec def loop(i: Int, atCommand: Boolean, acc: Vector[Token]): Vector[Token] =
      if (i >= n) acc
      else {
        val (token, end, command) = next(i, atCommand)
        loop(end, command, merge(acc, token))
      }

    loop(0, atCommand = true, Vector.empty)
  }

  /** Adjacent tokens of one kind become one, so a run of punctuation is one span. */
  private def merge(acc: Vector[Token], token: Token): Vector[Token] = acc.lastOption match {
    case Some(Token(kind, text)) if kind == token.kind && kind != Kind.Prompt =>
      acc.init :+ Token(kind, text + token.text)
    case _ => acc :+ token
  }
}
