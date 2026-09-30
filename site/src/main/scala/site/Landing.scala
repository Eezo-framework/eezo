package site

import io.eezo.core.html.*
import io.eezo.http.Request
import io.eezo.live.Live

/** The front page: what eezo is, in one screen, and the way into the docs. */
object Landing {

  private val Model: String =
    """case class Book(
      |    id: Id[Book],
      |    title: String,
      |    author: String,
      |    pages: Int
      |) derives Table, Form, Resource
      |""".stripMargin

  private val Routes: Vector[(String, String)] = Vector(
    "GET"    -> "/books",
    "GET"    -> "/books/new",
    "POST"   -> "/books",
    "GET"    -> "/books/:id",
    "GET"    -> "/books/:id/edit",
    "PUT"    -> "/books/:id",
    "DELETE" -> "/books/:id"
  )

  private val Steps: Vector[(String, String, String)] = Vector(
    ("eezo new bookshelf", "Scaffold", "A Main, one route, the build files. Nothing to configure."),
    ("eezo dev", "Serve", "Restarts on every save. A broken compile keeps the old code serving."),
    ("eezo deploy", "Ship", "Stages the jars, builds the image, migrates, waits for health.")
  )

  private val Features: Vector[(String, String, String, String)] = Vector(
    (
      "sun",
      "One declaration",
      "derives Table, Form, Resource",
      "A model declares what it is. eezo derives its table, its HTML form and its seven CRUD routes from that one case class."
    ),
    (
      "coral",
      "The filename is the route",
      "app/Health.scala mounts GET /health",
      "Handwritten routes come from the file layout. A handwritten route beats a derived one on the same path, so taking over one page keeps the other six."
    ),
    (
      "mint",
      "Direct style",
      "Request => Response",
      "A handler is a plain function on a virtual thread. Block on the database, call an API, return a page. No effect system, no callbacks."
    ),
    (
      "sky",
      "Migrations from the model",
      "eezo freeze, then eezo migrate",
      "The schema diff becomes committed SQL with a fingerprint. On deploy it runs before the new code takes traffic, and a failure keeps the old version serving."
    ),
    (
      "grape",
      "Live pages",
      "Component[State]",
      "State lives on the server, the browser is a thin renderer, and what travels is events up and DOM patches down over one socket. No JavaScript of yours."
    ),
    (
      "sun",
      "Guarded by declaration",
      "given Owned[Post, User] = ...",
      "A model says which of its routes need a signed in user and which rows are the owner's. In an application with a guard, a route that says nothing is a compile error."
    )
  )

  def page(request: Request): Html =
    Layout.page(
      request,
      Layout.Meta(
        title = "eezo",
        description =
          "A Scala 3 web framework. Direct-style. The case class is the source of truth. Deploy with one command.",
        path = "/"
      ),
      "landing"
    )(
      main(
        Attrs.id  := "content",
        Attrs.cls := "landing-main",
        hero(request),
        pillars,
        steps,
        features,
        edges,
        closing
      )
    )

  private def hero(request: Request): Html =
    section(
      Attrs.cls := "hero",
      div(
        Attrs.cls := "hero-copy",
        p(Attrs.cls := "eyebrow", "A Scala 3 web framework"),
        h1("The ", Tags.span(Attrs.cls := "hl", "case class"), " is the source of truth."),
        p(
          Attrs.cls := "lede",
          "Declare a model once. eezo derives its table, its form and its routes, ",
          "restarts on every save, and deploys with one command."
        ),
        div(
          Attrs.cls := "cta",
          a(Attrs.cls := "btn btn-primary", Attrs.href := "/docs", "Read the docs"),
          a(Attrs.cls := "btn btn-ghost", Attrs.href   := "/docs/examples/hello", "See an example")
        ),
        ul(
          Attrs.cls := "facts",
          li("Scala 3"),
          li("JDK 25"),
          li("Jetty on virtual threads"),
          li("Postgres"),
          li("MIT")
        )
      ),
      div(
        Attrs.cls := "hero-code",
        Highlight.block("scala", Model),
        ul(
          Attrs.cls                := "routes-pop",
          Attrs.attr("aria-label") := "The seven routes the model mounts",
          Routes.zipWithIndex.map { case ((method, path), i) =>
            li(
              Attrs.cls           := s"sticker tilt-${i % 4}",
              Attrs.data("delay") := i,
              Tags.span(Attrs.cls := s"method method-${method.toLowerCase}", method),
              code(path)
            )
          }
        ),
        Live.mount(request, new Clicks)
      )
    )

  private val Pillars: Vector[(String, String, String, String)] = Vector(
    ("tutorials", "Tutorials", "/docs/tutorials", "Build something, step by step, from nothing."),
    ("how-to", "How-to guides", "/docs/how-to", "Get one thing done: the steps and the code."),
    (
      "explanation",
      "Explanation",
      "/docs/explanation",
      "How the parts work, and why they are shaped so."
    ),
    (
      "reference",
      "Reference",
      "/docs/reference",
      "The raw APIs, every type and command, stated once."
    )
  )

  private def pillars: Html =
    section(
      Attrs.cls := "pillars",
      h2("Four ways into the docs"),
      ul(
        Attrs.cls := "pillar-cards",
        Pillars.map { case (slug, name, href, blurb) =>
          li(Attrs.cls := s"pillar pillar-$slug", a(Attrs.href := href, h3(name), p(blurb)))
        }
      )
    )

  private def steps: Html =
    section(
      Attrs.cls := "steps",
      h2("Three commands, start to production"),
      ol(
        Steps.zipWithIndex.map { case ((command, name, blurb), i) =>
          li(
            div(Attrs.cls := s"step-badge badge-$i", (i + 1).toString),
            div(
              Attrs.cls := "step-body",
              code(Attrs.cls := "step-command", command),
              h3(name),
              p(blurb)
            )
          )
        }
      ),
      p(
        Attrs.cls := "steps-note",
        "The whole arc, with real output, is in ",
        a(Attrs.href := "/docs/deploying", "the deploy walkthrough"),
        "."
      )
    )

  private def features: Html =
    section(
      Attrs.cls := "features",
      h2("What you get"),
      ul(
        Features.map { case (colour, title, snippet, blurb) =>
          li(
            Attrs.cls := s"tile tile-$colour",
            Tags.span(Attrs.cls := "tile-blob", Attrs.attr("aria-hidden") := "true"),
            h3(title),
            code(snippet),
            p(blurb)
          )
        }
      )
    )

  private def edges: Html =
    section(
      Attrs.cls := "edges",
      div(
        Attrs.cls := "edges-copy",
        h2("Two edges. Take one, or both."),
        p(
          "An application has a database edge, an http edge, or both, and opts in by artifact. ",
          "Deriving for an edge you do not have is a compile error, not a runtime surprise."
        )
      ),
      ul(
        Attrs.cls := "edge-cards",
        edgeCard("eezo-http", "HttpApp", "http only", "/docs/examples/hello", "hello"),
        edgeCard("eezo-db", "DbApp", "database only", "/docs/examples/reminders", "reminders"),
        edgeCard("eezo", "EezoApp", "both edges", "/docs/examples/blog", "blog")
      )
    )

  private def edgeCard(
      artifact: String,
      entry: String,
      edges: String,
      href: String,
      example: String
  ): Html =
    li(
      Attrs.cls := "edge-card",
      code(Attrs.cls := "artifact", s""""io.eezo" %% "$artifact""""),
      p(strong(entry), " · ", edges),
      a(Attrs.href := href, s"See $example →")
    )

  private def closing: Html =
    section(
      Attrs.cls := "closing",
      h2("Ready when you are."),
      p(
        "eezo is pre-release: the shape is settled, the polish is ongoing, and the source is open."
      ),
      div(
        Attrs.cls := "cta",
        a(Attrs.cls := "btn btn-primary", Attrs.href := "/docs", "Start reading"),
        a(
          Attrs.cls  := "btn btn-ghost",
          Attrs.href := Pages.Repository,
          Attrs.rel  := "noopener",
          "Star on GitHub"
        )
      )
    )
}
