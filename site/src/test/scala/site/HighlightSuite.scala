package site

import munit.FunSuite
import site.Highlight.Kind

class HighlightSuite extends FunSuite {

  private val samples: Map[Highlight.Lang, String] = Map(
    Highlight.Scala -> "// hi\ncase class Book(id: Id[Book], title: String) derives Table\nval s = s\"x $y\"\n",
    Highlight.Java -> "public static void main(String[] args) { int x = 1; /* c */ }\n",
    Highlight.Shell -> "$ sbt \"blog/run sync --apply\"   # once\nexport FOO=bar\ncd examples && sbt hello/run\n",
    Highlight.Sql    -> "SELECT id FROM book WHERE title = 'x' -- c\n",
    Highlight.Toml   -> "[deploy]\n  release_command = 'migrate --apply' # note\n",
    Highlight.Nginx  -> "proxy_set_header Host $http_host;\n",
    Highlight.Python -> "@dataclass\nclass A:\n    x = 'y'  # c\n",
    Highlight.Json   -> "{\"kind\": \"event\", \"n\": 3, \"ok\": true}\n",
    Highlight.Plain  -> "9 routes:\n  GET /health\n"
  )

  test("tokens concatenate back to the code, for every language") {
    samples.foreach { case (lang, code) =>
      assertEquals(Highlight.tokens(lang, code).map(_.text).mkString, code, lang.name)
    }
  }

  private def kinds(lang: Highlight.Lang, code: String): Vector[(Kind, String)] =
    Highlight.tokens(lang, code).map(t => t.kind -> t.text)

  test("scala: keywords, types, strings and comments are told apart") {
    val ts = kinds(Highlight.Scala, samples(Highlight.Scala))
    assert(ts.contains(Kind.Comment -> "// hi"), ts)
    assert(ts.contains(Kind.Kw -> "case"), ts)
    assert(ts.contains(Kind.Type -> "Book"), ts)
    assert(ts.contains(Kind.Type -> "String"), ts)
    assert(ts.contains(Kind.Kw -> "derives"), ts)
    assert(ts.contains(Kind.Str -> "\"x $y\""), ts)
  }

  test("scala: a triple quoted string is one token") {
    val ts = kinds(Highlight.Scala, "val q = \"\"\"a \"b\" c\"\"\"\n")
    assert(ts.contains(Kind.Str -> "\"\"\"a \"b\" c\"\"\""), ts)
  }

  test("shell: the prompt, the command, the flag, the comment and the assignment") {
    val ts = kinds(Highlight.Shell, samples(Highlight.Shell))
    assert(ts.contains(Kind.Prompt -> "$"), ts)
    assert(ts.contains(Kind.Cmd -> "sbt"), ts)
    assert(ts.contains(Kind.Str -> "\"blog/run sync --apply\""), ts)
    assert(ts.contains(Kind.Comment -> "# once"), ts)
    assert(ts.contains(Kind.Kw -> "export"), ts)
    assert(ts.contains(Kind.Var -> "FOO"), ts)
    assert(ts.contains(Kind.Cmd -> "cd"), ts)
    // `sbt` after `&&` is a command again.
    assertEquals(ts.count(_ == (Kind.Cmd -> "sbt")), 2, ts)
  }

  test("sql: keywords regardless of case, strings, comments") {
    val ts = kinds(Highlight.Sql, "select 1 FROM t where a = 'x' -- c\n")
    assert(ts.contains(Kind.Kw -> "select"), ts)
    assert(ts.contains(Kind.Kw -> "FROM"), ts)
    assert(ts.contains(Kind.Str -> "'x'"), ts)
    assert(ts.contains(Kind.Comment -> "-- c"), ts)
  }

  test("toml: sections and keys") {
    val ts = kinds(Highlight.Toml, samples(Highlight.Toml))
    assert(ts.contains(Kind.Section -> "[deploy]"), ts)
    assert(ts.contains(Kind.Key -> "release_command"), ts)
    assert(ts.contains(Kind.Str -> "'migrate --apply'"), ts)
  }

  test("json: keys are told from string values") {
    val ts = kinds(Highlight.Json, samples(Highlight.Json))
    assert(ts.contains(Kind.Key -> "\"kind\""), ts)
    assert(ts.contains(Kind.Str -> "\"event\""), ts)
    assert(ts.contains(Kind.Kw -> "true"), ts)
    assert(ts.contains(Kind.Num -> "3"), ts)
  }

  test("nginx: the directive is the command, the variable is a variable") {
    val ts = kinds(Highlight.Nginx, samples(Highlight.Nginx))
    assert(ts.contains(Kind.Cmd -> "proxy_set_header"), ts)
    assert(ts.contains(Kind.Var -> "$http_host"), ts)
  }

  test("an unknown language is plain text in one token") {
    assertEquals(Highlight.language("weird").name, "text")
    assertEquals(Highlight.tokens(Highlight.Plain, "a b").size, 1)
  }

  test("the rendered block escapes markup in every kind of token") {
    val out = Highlight.block("scala", "val x = \"<script>\" // <b>\n").render
    assert(!out.contains("<script>"), out)
    assert(out.contains("&lt;script&gt;"), out)
    assert(out.contains("&lt;b&gt;"), out)
  }
}
