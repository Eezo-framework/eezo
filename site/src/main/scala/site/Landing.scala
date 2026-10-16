package site

import io.eezo.core.html.*
import io.eezo.http.Request

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
    (
      "eezo new myUnicorn",
      "Scaffold",
      "A Main, one route, plus build files. Nothing to configure."
    ),
    ("eezo dev", "Serve", "Restarts on save. A broken compile keeps the old code serving."),
    ("eezo deploy", "Ship", "Stages the jars, builds the image, migrates, waits for health.")
  )

  private val Features: Vector[(String, String, String, String)] = Vector(
    (
      "sun",
      "One declaration",
      "derives Table, Form, Resource",
      "Declare your model, and Eezo derives its tables & database API, its HTML form and all CRUD HTTP routes from one case class."
    ),
    (
      "coral",
      "The filename is the route",
      "app/Health.scala mounts GET /health",
      "Your own routes come from the file layout. You can override a derived route on the same path."
    ),
    (
      "mint",
      "Direct style",
      "Request => Response",
      "A handler is a function. Eezo runs on virtual threads, so feel free to block on the database, call an API, or return a page."
    ),
    (
      "sky",
      "Migrations from the model",
      "eezo db freeze, then eezo db migrate",
      "Eezo detects schema diffs as you change your data model. Commit them to fingerprinted SQL migrations. On deploy they run before the new code takes traffic."
    ),
    (
      "grape",
      "Live pages",
      "Component[State]",
      "Write UIs with a Scala functional DSL. State lives on the server, and the browser gets a thin client. UI sends events, server renders UI. No need for JavaScript."
    ),
    (
      "sun",
      "Guarded by declaration",
      "given Owned[Post, User] = ...",
      "Control route protection, and get your owner type if successful (e.g. a user). In an application with a guard, a route that says nothing is a compile error."
    )
  )

  /** The one command the front page shows, spelled here so a test can hold the page to it. */
  val InstallCommand: String = "curl -fsSL https://eezo.io/install | bash"

  /** The Scala Days talk the front page embeds, spelled here so a test can hold the page to it. */
  val VideoId: String = "IOsSpHu3eKE"

  private val Iframe: Tag = Tag("iframe")

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
    )(state =>
      main(
        Attrs.id  := "content",
        Attrs.cls := "landing-main",
        hero(state.clicks),
        talk,
        pillars,
        steps,
        features,
        layers,
        closing
      )
    )

  private def hero(clicks: Int): Html =
    section(
      Attrs.cls := "hero",
      div(
        Attrs.cls := "hero-copy",
        p(Attrs.cls := "eyebrow", "A Scala 3 web framework"),
        h1("Your ", Tags.span(Attrs.cls := "hl", "data model"), " is the source of truth."),
        p(
          Attrs.cls := "lede",
          "Declare a model, and Eezo derives tables, forms, and HTTP routes. ",
          "Server-side rendering with functional, direct-style Scala. ",
          "Fast dev feedback, deploys with one command."
        ),
        div(
          Attrs.cls := "cta",
          a(Attrs.cls := "btn btn-primary", Attrs.href := "/docs", "Read the docs"),
          a(Attrs.cls := "btn btn-ghost", Attrs.href   := "/docs/examples/hello", "See an example")
        ),
        div(
          Attrs.cls := "install",
          p(Attrs.cls   := "install-label", "Install the command line"),
          pre(Attrs.cls := "install-cmd", code(Landing.InstallCommand)),
          p(
            Attrs.cls := "install-note",
            "Needs JDK 25 and sbt. ",
            a(Attrs.href := "/install", "Read the script"),
            " first if you like."
          )
        ),
        ul(
          Attrs.cls := "facts",
          li("Scala 3"),
          li("JDK 25"),
          li("Postgres"),
          li("MIT")
        )
      ),
      div(
        Attrs.cls := "hero-code",
        CodeBlock.render("scala", Model),
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
        Clicks.render(clicks)
      )
    )

  /** The talk, in a band under the hero: a shop built with Eezo on stage at Scala Days. The embed
    * is YouTube's cookieless host and loads lazily, so the page does not pay for it until the band
    * is near the viewport.
    */
  private def talk: Html =
    section(
      Attrs.cls := "talk",
      div(
        Attrs.cls := "talk-inner",
        div(
          Attrs.cls := "talk-copy",
          p(Attrs.cls := "eyebrow", "From Scala Days"),
          h2("Shopping for a Scala framework"),
          p(
            "Daniel Ciocîrlan and Riccardo Cardin build a shop with Eezo on stage, ",
            "from the first case class to a working checkout."
          ),
          a(Attrs.cls := "btn btn-primary", Attrs.href := "/docs/examples/shop", "Read the shop")
        ),
        figure(
          Attrs.cls := "talk-video",
          figcaption(
            Attrs.cls := "talk-tab",
            Tags.span(Attrs.cls := "talk-play", Attrs.attr("aria-hidden") := "true", "▶"),
            "Watch the talk, 27 minutes"
          ),
          div(
            Attrs.cls := "talk-frame",
            Iframe(
              Attrs.src                     := s"https://www.youtube-nocookie.com/embed/$VideoId",
              Attrs.attr("title")           := "Shopping for a Scala Framework, Scala Days",
              Attrs.attr("loading")         := "lazy",
              Attrs.attr("allow")           := "encrypted-media; picture-in-picture; web-share",
              Attrs.attr("allowfullscreen") := "",
              Attrs.attr("referrerpolicy")  := "strict-origin-when-cross-origin"
            )
          )
        )
      )
    )

  private val Pillars: Vector[(String, String, String, String)] = Vector(
    ("tutorials", "Tutorials", "/docs/tutorials", "Build something step by step"),
    ("how-to", "How-to guides", "/docs/how-to", "Get one thing done"),
    (
      "explanation",
      "Explanations",
      "/docs/explanation",
      "How Eezo's parts work and why"
    ),
    (
      "reference",
      "Reference",
      "/docs/reference",
      "Full API docs"
    )
  )

  private def pillars: Html =
    section(
      Attrs.cls := "pillars",
      h2("Four kinds of docs"),
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
      h2("How it works"),
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
        "Check out a full dev cycle in ",
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

  private def layers: Html =
    section(
      Attrs.cls := "layers",
      div(
        Attrs.cls := "layers-copy",
        h2("Take the layers you need"),
        p(
          "An application has a database layer, an http layer, or both. Import the right artifact for your app."
        )
      ),
      ul(
        Attrs.cls := "layer-cards",
        layerCard("eezo-http", "HttpApp", "http only", "/docs/examples/hello", "hello"),
        layerCard("eezo-db", "DbApp", "database only", "/docs/examples/reminders", "reminders"),
        layerCard("eezo", "EezoApp", "all", "/docs/examples/blog", "blog")
      )
    )

  private def layerCard(
      artifact: String,
      entry: String,
      layers: String,
      href: String,
      example: String
  ): Html =
    li(
      Attrs.cls := "layer-card",
      code(Attrs.cls := "artifact", s""""io.eezo" %% "$artifact""""),
      p(strong(entry), " · ", layers),
      a(Attrs.href := href, s"See $example →")
    )

  private def closing: Html =
    section(
      Attrs.cls := "closing",
      h2("Ready when you are."),
      p(
        "Eezo is pre-release: dev is ongoing, and the source is open."
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
