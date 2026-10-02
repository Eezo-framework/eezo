package site

import io.eezo.core.html.*
import io.eezo.live.Component
import io.eezo.live.Event
import io.eezo.live.Init
import io.eezo.live.Live

/** Everything live on a page, as one component: the header with its search button, the search
  * dialog, the navigation drawer on a docs page, the counter on the front page.
  *
  * One component rather than one per concern, because the live layer drives one mount per page, and
  * the search button in the header has to sit in the same tree as the dialog it opens and the
  * drawer's button in the same tree as the drawer. The state is the union of the three small
  * states, and each event moves the slice that knows it; the page's own content arrives as a
  * function of the state, so a docs page can place the drawer and the front page its counter while
  * the rest of their content is rendered once and compared by value on every patch.
  */
final class Chrome(
    path: String,
    theme: Html,
    content: Chrome.State => Html,
    index: () => Search.Index,
    initial: Chrome.State
) extends Component[Chrome.State] {

  import Chrome.State

  def init(ctx: Init[State]): State = initial

  def handle(event: Event, state: State): State =
    State(
      drawer = Drawer.handle(event, state.drawer),
      search = event.name match {
        case "search-open"  => Some(state.search.getOrElse(""))
        case "search-close" => None
        case "search"       => Some(event.payload.getOrElse("value", "").take(Chrome.QueryLimit))
        case _              => state.search
      },
      clicks = Clicks.handle(event, state.clicks)
    )

  def render(state: State): Html =
    div(
      Attrs.cls := "chrome",
      a(Attrs.cls := "skip", Attrs.href := "#content", "Skip to content"),
      Layout.header(path, theme),
      content(state),
      Layout.footer,
      state.search.map(dialog)
    )

  private def dialog(query: String): Html =
    div(
      Attrs.cls                := "search",
      Attrs.role               := "dialog",
      Attrs.attr("aria-modal") := "true",
      Attrs.attr("aria-label") := "Search the documentation",
      div(Attrs.cls := "search-backdrop", Live.onClick("search-close")),
      div(
        Attrs.cls := "search-panel",
        div(
          Attrs.cls := "search-bar",
          Icons.search,
          input(
            // A text input, not `type="search"`: Chromium answers Escape in a search box by
            // clearing it and keeps the key from the document, so the dialog would stay open.
            Attrs.tpe                  := "text",
            Attrs.role                 := "searchbox",
            Attrs.cls                  := "search-input",
            Attrs.name                 := "q",
            Attrs.placeholder          := "Search the docs",
            Attrs.value                := query,
            Attrs.autofocus            := true,
            Attrs.attr("autocomplete") := "off",
            Attrs.attr("spellcheck")   := "false",
            Attrs.attr("aria-label")   := "Search query",
            Attrs.data("search-input") := "",
            Live.onInput("search", debounceMillis = 120)
          ),
          button(
            Attrs.cls                  := "search-close",
            Attrs.tpe                  := "button",
            Attrs.attr("aria-label")   := "Close search",
            Attrs.data("search-close") := "",
            Live.onClick("search-close"),
            Icons.close
          )
        ),
        results(query.trim)
      )
    )

  private def results(query: String): Html =
    if (query.isEmpty)
      p(
        Attrs.cls := "search-hint",
        "Type to search every page of the documentation. ",
        Chrome.Kbd("Esc"),
        " closes."
      )
    else {
      val hits = index().query(query)
      if (hits.isEmpty) p(Attrs.cls := "search-hint", "No page mentions ", strong(query), ".")
      else
        ol(
          Attrs.cls := "search-results",
          hits.map(hit =>
            li(
              a(
                Attrs.href := hit.href,
                div(
                  Attrs.cls := "search-where",
                  span(Attrs.cls := "search-title", hit.page.title),
                  hit.heading.map(h => span(Attrs.cls := "search-heading", h.text)),
                  span(Attrs.cls := "chip chip-quiet", hit.page.section)
                ),
                hit.excerpt
              )
            )
          )
        )
    }
}

object Chrome {

  /** The three slices: the drawer open or not, the search dialog with its query when it is open,
    * and the front page's count.
    */
  final case class State(drawer: Boolean, search: Option[String], clicks: Int)

  object State {
    val initial: State = State(drawer = false, search = None, clicks = 0)

    /** The page with the search dialog already open, which is what `/search` serves. */
    val searching: State = initial.copy(search = Some(""))
  }

  /** The longest query kept. A browser's input is untrusted, and a search is a few words. */
  val QueryLimit: Int = 100

  val Kbd: Tag = Tag("kbd")
}
