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
      |) derives Table, Form, Resource, Api
      |""".stripMargin

  private val Routes: Vector[(String, String)] = Vector(
    "GET"    -> "/books",
    "GET"    -> "/books/new",
    "POST"   -> "/books",
    "GET"    -> "/books/:id",
    "GET"    -> "/books/:id/edit",
    "PUT"    -> "/books/:id",
    "DELETE" -> "/books/:id",
    "GET"    -> "/api/books",
    "POST"   -> "/api/books",
    "GET"    -> "/api/books/:id",
    "PUT"    -> "/api/books/:id",
    "DELETE" -> "/api/books/:id"
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
      "derives Table, Form, Resource, Api",
      "Declare your model, and Eezo derives its tables & database API, its HTML form, all CRUD HTTP routes and a JSON API from one case class."
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
        roadmap,
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
          "Declare a model, and Eezo derives tables, forms, HTTP routes and a JSON API. ",
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
          Attrs.attr("aria-label") := "The twelve routes the model mounts",
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

  /** The talk, in a band under the hero: an application built with Eezo on stage at Scala Days. The
    * embed is YouTube's cookieless host and loads lazily, so the page does not pay for it until the
    * band is near the viewport.
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
            "Riccardo and Daniel demonstrate a non-trivial full-stack web application from scratch."
          )
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

  /** The label on the line between what 1.0 waits for and what can follow it, spelled here so a
    * test can hold the page to it.
    */
  val Release: String = "Potential 1.0 release"

  private val Planned: Vector[Roadmap.Entry] = Vector(
    Roadmap.Item(
      "Observability and OpenTelemetry",
      "Structured logs with request ids, an access log, a readiness check that pings the pool, metrics, and OTel spans around requests, queries and live events."
    ),
    Roadmap.Item(
      "Testkit and Testcontainers",
      "Request builders, signed-in sessions, CSRF handled for you, a rollback per test, and a Postgres that starts itself."
    ),
    Roadmap.Item(
      "Security policy and login throttling",
      "A SECURITY.md with a disclosure path, and sign-in attempts throttled so a password guard can't be brute forced."
    ),
    Roadmap.Item(
      "Database joins",
      "hasMany and belongsTo over Ref, joins with eager loading so a list page doesn't run N+1 queries, and typed aggregates."
    ),
    Roadmap.Item(
      "OAuth integration",
      "Sign in with GitHub, Google or any OIDC provider, onto the same user model the password guard uses."
    ),
    Roadmap.Item(
      "Uploads, multipart and storage",
      "Multipart forms with size limits, and files kept on disk or in S3."
    ),
    Roadmap.Item(
      "HTTP streaming and SSE",
      "Stream a response body as it's produced, and push Server-Sent Events from a plain handler."
    ),
    Roadmap.Item(
      "Validation rules",
      "Length, range, format and cross-field rules declared once on the model, enforced in the form, the JSON API and the database. Iron's refined types are the candidate."
    ),
    Roadmap.Item(
      "JSON outputs",
      "Every eezo command can answer in JSON, so scripts and agents read its results instead of scraping text."
    ),
    Roadmap.Item(
      "Agentic tools",
      "Skills and tools that let a coding agent read your routes, models, drift and logs from a running eezo dev."
    ),
    Roadmap.Item(
      "The admin",
      "An admin interface derived from every Table, mounted with one Route.under(\"/admin\")."
    ),
    Roadmap.Item(
      "i18n",
      "Translated messages and derived pages: form labels, validation errors and CRUD screens in the reader's language."
    ),
    Roadmap.Milestone(Release),
    Roadmap.Item(
      "Pagination",
      "Pages, sorting and filters on the derived index and the JSON API, so a table with 10k rows doesn't load all of them."
    ),
    Roadmap.Item(
      "OpenAPI from the data model",
      "An OpenAPI 3.1 document derived from derives Api at compile time, and checked in CI like schema drift."
    ),
    Roadmap.Item(
      "Email",
      "Mail written as Html values, with a dev inbox and preview pages while you build."
    ),
    Roadmap.Item(
      "Background jobs",
      "Jobs on the Postgres you already run, with retries and scheduling. No Redis needed."
    ),
    Roadmap.Item(
      "Local caching",
      "An in-process cache for the reads that rarely change."
    ),
    Roadmap.Item(
      "Distributed topics",
      "A Topic that fans out across machines over Postgres LISTEN/NOTIFY, so every live page sees every update."
    ),
    Roadmap.Item(
      "Horizontal scale",
      "Several machines behind a load balancer, sharing live state and surviving one going away."
    ),
    Roadmap.Item(
      "Magic link sign-in",
      "Sign in from a link in an email, with no password to remember."
    ),
    Roadmap.Item(
      "Dev Services",
      "eezo dev starts a throwaway Postgres when none is reachable, so a fresh clone runs with no setup."
    ),
    Roadmap.Item(
      "A test tab in the TUI",
      "A tab in the eezo dev console that reruns the affected tests on save."
    ),
    Roadmap.Item(
      "The dev console in the browser",
      "The same routes, models, drift and logs as the TUI, in a browser tab."
    ),
    Roadmap.Item(
      "Attachments on the model",
      "A file field declared on the case class, with storage, signed URLs and cleanup derived for it."
    ),
    Roadmap.Item(
      "Deploy check and built-in CSP",
      "eezo check before a deploy: secret set, debug off, headers on. Plus a Content Security Policy with nonces in the layout."
    )
  )

  private def roadmap: Html =
    section(
      Attrs.cls := "roadmap-section",
      p(Attrs.cls := "eyebrow", "Roadmap"),
      h2("What's coming"),
      p(
        Attrs.cls := "roadmap-lede",
        "In priority order. Everything above the line is what 1.0 waits for."
      ),
      Roadmap.render(Planned)
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
