package site

import io.eezo.core.html.*

/** A roadmap: what is planned, in priority order, as a numbered list of cards.
  *
  * The entries are a list of [[Roadmap.Item]]s with a [[Roadmap.Milestone]] wherever a release
  * falls between them. A milestone is drawn as a line across the list with its label on it, and the
  * items keep counting past it, so the number on a card is its place in the whole list.
  */
object Roadmap {

  enum Entry {
    case Item(title: String, explanation: String)
    case Milestone(label: String)
  }

  export Entry.{Item, Milestone}

  def render(entries: Vector[Entry]): Html =
    ol(
      Attrs.cls := "roadmap",
      entries.map {
        case Item(title, explanation) =>
          li(Attrs.cls := "roadmap-item", h3(title), p(explanation))
        case Milestone(label) =>
          li(
            Attrs.cls := "roadmap-milestone",
            Tags.span(Attrs.cls := "roadmap-milestone-label", label)
          )
      }
    )
}
