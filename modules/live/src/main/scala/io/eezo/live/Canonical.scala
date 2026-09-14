package io.eezo.live

import io.eezo.core.html.Html

/** The tree form both sides of the wire agree on, checked loudly rather than repaired silently.
  *
  * `Tag.apply` already produces this form: fragments spliced, adjacent text merged, empty text
  * dropped. What arrives here non canonical was built by hand around that guard, and normalising it
  * in the differ would hide the divergence until the DOM and the tree disagree in production, which
  * is the one failure mode design/live.md exists to prevent. So the differ validates and refuses,
  * and the message names the rule so the fix is the call site's, not the differ's.
  */
private[live] object Canonical {

  /** The root of a component render: exactly one element. Text, raw markup or a fragment at the
    * root leaves "the component's root node" meaning nothing, and a mount with no single node to
    * anchor patches on.
    */
  def root(tree: Html): Html.Element = tree match {
    case element: Html.Element =>
      validate(element)
      element
    case Html.Fragment(_) =>
      throw NotCanonical(
        "a component renders exactly one root element; this render is a fragment. " +
          "Wrap the pieces in a container element."
      )
    case Html.Text(_) | Html.Raw(_) =>
      throw NotCanonical(
        "a component renders exactly one root element; this render is bare text or raw markup. " +
          "Wrap it in a container element."
      )
  }

  private def validate(element: Html.Element): Unit = {
    if (Html.VoidTags.contains(element.name) && element.children.nonEmpty)
      throw NotCanonical(
        s"<${element.name}> is a void element with ${element.children.size} children. " +
          "A browser renders none of them, so the tree and the DOM would disagree."
      )

    element.children.zipWithIndex.foreach { case (child, index) =>
      child match {
        case Html.Fragment(_) =>
          throw NotCanonical(
            s"child $index of <${element.name}> is a fragment. `Tag.apply` splices these; " +
              "an element holding one was built by hand around that guard."
          )
        case Html.Text(escaped) =>
          if (escaped.isEmpty)
            throw NotCanonical(
              s"child $index of <${element.name}> is empty text, which the DOM will not hold."
            )
          if (index > 0 && element.children(index - 1).isInstanceOf[Html.Text])
            throw NotCanonical(
              s"children ${index - 1} and $index of <${element.name}> are adjacent text nodes, " +
                "which the HTML parser merges into one."
            )
        case nested: Html.Element => validate(nested)
        case Html.Raw(_)          => ()
      }
    }
  }
}

/** A tree the differ refuses: built by hand, outside `Tag.apply`'s canonicalisation. */
final case class NotCanonical(message: String) extends IllegalArgumentException(message)
