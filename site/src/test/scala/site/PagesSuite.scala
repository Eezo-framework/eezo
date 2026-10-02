package site

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

import io.eezo.generated.Routes
import io.eezo.http.Body
import io.eezo.http.Method
import io.eezo.http.NotFound
import io.eezo.http.Request
import munit.FunSuite

/** The site over the repository it documents: every Markdown file is a page, every page renders,
  * and every link on every page lands somewhere.
  */
class PagesSuite extends FunSuite {

  private val root: Path =
    Path.of(sys.props.getOrElse("site.root", fail("site.root is not set; run through sbt")))

  private val content = Content.disk(root)
  private val pages   = Pages.load(content)

  /** The files the build copies into the jar, spelled here a second time on purpose: a directory
    * added to one list and not the other fails here.
    */
  private def markdownFiles: Set[String] = {
    def under(directory: String, recursive: Boolean): Set[String] = {
      val dir    = root.resolve(directory)
      val stream = if (recursive) Files.walk(dir) else Files.list(dir)
      try
        stream.iterator.asScala
          .filter(f => Files.isRegularFile(f) && f.toString.endsWith(".md"))
          .map(f => root.relativize(f).toString)
          .toSet
      finally stream.close()
    }
    val examples = Files
      .list(root.resolve("examples"))
      .iterator
      .asScala
      .filter(d => Files.isRegularFile(d.resolve("README.md")))
      .map(d => s"examples/${d.getFileName}/README.md")
      .toSet
    under("docs", recursive = true).filterNot(f =>
      f.startsWith("docs/adr/") || f.startsWith("docs/research/")
    ) ++ examples ++ Set("README.md", "CONTEXT.md")
  }

  test("every Markdown file the build ships is a page, and every page has a file") {
    assertEquals(pages.all.map(_.source).toSet, markdownFiles)
  }

  test("slugs are unique and titles are set") {
    val slugs = pages.all.map(_.slug)
    assertEquals(slugs.distinct, slugs)
    pages.all.foreach(page => assert(page.title.trim.nonEmpty, page.source))
  }

  test("the overview is the README, and the four pillars each have an index") {
    assertEquals(pages.bySlug("overview").source, "README.md")
    assertEquals(
      pages.pillars.map(_.slug),
      Vector(Some("tutorials"), Some("how-to"), Some("explanation"), Some("reference"))
    )
    assert(pages.knows("/docs"))
    assert(pages.knows("/docs/how-to"))
    assert(pages.knows("/docs/tutorials/getting-started"))
    assert(!pages.knows("/docs/nothing"))
  }

  test("a tutorial's order is the listed one, and a placeholder is a draft") {
    val tutorials = pages.pillar("tutorials").get.pages
    assertEquals(tutorials.head.slug, "tutorials/getting-started")
    assert(pages.bySlug("explanation/edges").draft)
    assert(!pages.bySlug("tutorials/deploy-to-fly").draft)
    assertEquals(
      tutorials.map(_.group).distinct,
      Vector(None, Some("The example applications"))
    )
  }

  test("an ADR is not a page, and a link to one goes to GitHub") {
    assert(!pages.all.exists(_.source.startsWith("docs/adr/")))
    assert(!pages.all.exists(_.source.startsWith("research/")))
    val failures = pages.bySource("docs/explanation/failures.md")
    assert(
      pages
        .resolve(failures, "../adr/0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md")
        .startsWith(s"${Pages.Repository}/blob/main/docs/adr/0006")
    )
  }

  test("a link to another Markdown file resolves to its page, a repository file to GitHub") {
    val readme = pages.bySource("README.md")
    assertEquals(
      pages.resolve(readme, "docs/explanation/failures.md"),
      "/docs/explanation/failures"
    )
    assertEquals(pages.resolve(readme, "LICENSE"), s"${Pages.Repository}/blob/main/LICENSE")
    val live = pages.bySource("docs/tutorials/a-live-page.md")
    assertEquals(
      pages.resolve(live, "deploy-to-fly.md#0-prerequisites"),
      "/docs/tutorials/deploy-to-fly#0-prerequisites"
    )
    assertEquals(pages.resolve(live, "../x.md#why"), s"${Pages.Repository}/blob/main/docs/x.md#why")
    assertEquals(pages.resolve(live, "#consequences"), "#consequences")
    assertEquals(pages.resolve(live, "https://fly.io"), "https://fly.io")
  }

  /** Through the table rather than `Docs.render` directly, because dispatch is what mints the CSRF
    * token the theme toggle in the header posts with.
    */
  private def rendered(page: Page): String =
    Routes
      .table()
      .dispatch(Request(Method.GET, page.path, Map.empty, Map.empty, Array.empty, Map.empty))
      .body match {
      case Body.Html(html) => html.render
      case other           => fail(s"${page.path} answered with $other")
    }

  private val Href = """href="([^"]*)"""".r

  private def renderedAt(path: String): String =
    Routes
      .table()
      .dispatch(Request(Method.GET, path, Map.empty, Map.empty, Array.empty, Map.empty))
      .body match {
      case Body.Html(html) => html.render
      case other           => fail(s"$path answered with $other")
    }

  test("the hub and every pillar index render and link only to known pages") {
    val hub = renderedAt("/docs")
    pages.pillars.foreach(p => assert(hub.contains(s"""href="${p.path.get}""""), p.name))
    (Vector("/docs") ++ pages.pillars.flatMap(_.path)).foreach { path =>
      Href.findAllMatchIn(renderedAt(path)).map(_.group(1)).filter(_.startsWith("/docs")).foreach {
        href => assert(pages.knows(href), s"$path links $href")
      }
      Href.findAllMatchIn(renderedAt(path)).map(_.group(1)).filter(_.startsWith("/api/")).foreach {
        href => assert(ApiDocs.linkOk(href), s"$path links $href, which the API docs do not hold")
      }
    }
    val reference = renderedAt("/docs/reference")
    assert(reference.contains("chip-draft"), "the reference index marks its drafts")
    assert(reference.contains("Vocabulary"), reference)
  }

  test("every page renders with its title and every internal link resolves") {
    val documents = pages.all.map(page => page -> rendered(page)).toMap
    val anchors   = pages.all.map { page =>
      page.path -> Markdown.parse(content.read(page.source).get).headings.map(_.id).toSet
    }.toMap

    documents.foreach { case (page, html) =>
      assert(html.contains("<h1"), s"${page.path} has no heading")
      assert(html.contains(s"<title>"), s"${page.path} has no title")

      Href.findAllMatchIn(html).map(_.group(1)).filter(_.startsWith("/")).foreach { href =>
        val (path, fragment) = href.indexOf('#') match {
          case -1 => (href, None)
          case at => (href.take(at), Some(href.drop(at + 1)))
        }
        val (bare, _) = path.indexOf('?') match {
          case -1 => (path, "")
          case at => (path.take(at), path.drop(at))
        }
        if (bare.startsWith("/assets/"))
          assert(Assets.read(bare.stripPrefix("/assets/")).isDefined, s"${page.path} links $href")
        else if (bare.startsWith("/api/") || bare == "/api") ()
        else if (bare == "/") ()
        else {
          assert(pages.knows(bare), s"${page.path} links $href")
          fragment.foreach { id =>
            assert(
              anchors.get(bare).forall(_.contains(id)),
              s"${page.path} links $href, and $bare has no #$id"
            )
          }
        }
      }
    }
  }

  test("a draft page carries the banner, a written one does not") {
    assert(rendered(pages.bySlug("explanation/edges")).contains("draft-banner"))
    assert(!rendered(pages.bySlug("tutorials/deploy-to-fly")).contains("draft-banner"))
  }

  test("a slug with no page is a 404") {
    intercept[NotFound](
      Docs.render(
        Request(Method.GET, "/docs/no/such/page", Map.empty, Map.empty, Array.empty, Map.empty),
        "no/such/page"
      )
    )
  }

  test("the jar's copy of the content matches the repository") {
    val jar = Content.classpath
    markdownFiles.foreach { path =>
      assertEquals(jar.read(path), content.read(path), path)
    }
    assertEquals(jar.list("docs/adr"), Vector.empty)
    assertEquals(jar.list("docs/how-to"), content.list("docs/how-to"))
  }
}
