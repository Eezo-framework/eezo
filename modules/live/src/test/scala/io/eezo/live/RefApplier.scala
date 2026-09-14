package io.eezo.live

import io.eezo.core.html.Html

/** The applier's twin in Scala, over the tree instead of a DOM.
  *
  * It exists so that thousands of generated cases run in milliseconds inside `sbt test`; the jsdom
  * harness, running the real `applier.js`, is the authority for the invariant and this is
  * deliberately not it (design/live.md §2.7). It is loud where the client is careful: a path that
  * resolves to nothing or an integrity mismatch throws, because in a test either one is the bug.
  */
object RefApplier {

  /** The anchor's children after the patches. `anchor` is the mount's child list, so an empty path
    * targets the anchor itself, exactly as the client resolves it by page id.
    */
  def apply(anchor: Vector[Html], patches: List[Patch]): Vector[Html] =
    patches.foldLeft(anchor) { (siblings, patch) =>
      patch match {
        case Patch.SetChildren(Nil, _, children)       => children
        case Patch.SetChildren(path, expect, children) =>
          at(siblings, path) { el =>
            expect.foreach { tag =>
              if (el.name != tag)
                throw new AssertionError(s"expected <$tag> at $path, found <${el.name}>")
            }
            Html.Element(el.name, el.attrs, el.key, children)
          }
      }
    }

  private def at(siblings: Vector[Html], path: List[Int])(
      f: Html.Element => Html.Element
  ): Vector[Html] =
    path match {
      case Nil           => throw new AssertionError("an empty path is the caller's case")
      case index :: rest =>
        siblings(index) match {
          case el: Html.Element =>
            val edited =
              if (rest.isEmpty) f(el)
              else Html.Element(el.name, el.attrs, el.key, at(el.children, rest)(f))
            siblings.updated(index, edited)
          case other => throw new AssertionError(s"path step $index landed on $other")
        }
    }
}
