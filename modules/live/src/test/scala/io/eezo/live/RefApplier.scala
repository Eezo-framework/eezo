package io.eezo.live

import io.eezo.core.html.{AttrName, Html}

/** The applier's twin in Scala, over the tree instead of a DOM.
  *
  * It exists so that thousands of generated cases run in milliseconds inside `sbt test`; the jsdom
  * harness, running the real `applier.js`, is the authority for the invariant and this is
  * deliberately not it (design/live.md §2.7). It is loud where the client is careful: a path that
  * resolves to nothing or an integrity mismatch throws, because in a test either one is the bug.
  *
  * One deliberate mirror of the client: `data-eezo-key` arrives as an ordinary attribute patch (the
  * differ ships key changes that way until M5), and this applier routes it back into `Element.key`,
  * exactly as the DOM stores it in the same attribute position the renderer emits.
  */
object RefApplier {

  private val KeyAttr = "data-eezo-key"

  /** The anchor's children after the patches. `anchor` is the mount's child list, so an empty path
    * targets the anchor itself, exactly as the client resolves it by page id.
    */
  def apply(anchor: Vector[Html], patches: List[Patch]): Vector[Html] =
    patches.foldLeft(anchor)(one)

  private def one(anchor: Vector[Html], patch: Patch): Vector[Html] = patch match {
    case Patch.SetText(path, text) =>
      at(anchor, path) {
        case Html.Text(_) => Vector(Html.text(text))
        case other        => refuse(s"setText at $path found $other")
      }

    case Patch.SetAttr(path, expect, name, value) =>
      onElement(anchor, path, expect) { el =>
        if (name == KeyAttr) Html.Element(el.name, el.attrs, Some(value), el.children)
        else {
          val fresh   = AttrName(name) := value
          val present = el.attrs.exists(_.name == name)
          val attrs   =
            if (present) el.attrs.map(attr => if (attr.name == name) fresh else attr)
            else el.attrs :+ fresh
          Html.Element(el.name, attrs, el.key, el.children)
        }
      }

    case Patch.RemoveAttr(path, expect, name) =>
      onElement(anchor, path, expect) { el =>
        if (name == KeyAttr) Html.Element(el.name, el.attrs, None, el.children)
        else Html.Element(el.name, el.attrs.filterNot(_.name == name), el.key, el.children)
      }

    case Patch.ReplaceNode(path, expect, node) =>
      at(anchor, path) { old =>
        checkName(old, expect, path)
        Vector(node)
      }

    case Patch.RemoveNode(path, expect) =>
      at(anchor, path) { old =>
        checkName(old, expect, path)
        Vector.empty
      }

    case Patch.AppendChildren(path, expect, children) =>
      onElement(anchor, path, expect) { el =>
        Html.Element(el.name, el.attrs, el.key, el.children ++ children)
      }

    case Patch.SetChildren(Nil, _, children) => children

    case Patch.SetChildren(path, expect, children) =>
      onElement(anchor, path, expect.getOrElse(refuse(s"no expect for non-anchor path $path"))) {
        el => Html.Element(el.name, el.attrs, el.key, children)
      }
  }

  /** Rebuilds the spine above `path`, replacing the addressed node with whatever `f` returns: one
    * node to update, none to remove.
    */
  private def at(siblings: Vector[Html], path: List[Int])(f: Html => Vector[Html]): Vector[Html] =
    path match {
      case Nil => refuse("an empty path only targets the anchor, and only for setChildren")
      case index :: rest =>
        if (!siblings.indices.contains(index))
          refuse(s"index $index walks off ${siblings.size} children")
        else if (rest.isEmpty) siblings.patch(index, f(siblings(index)), 1)
        else
          siblings(index) match {
            case el: Html.Element =>
              siblings.updated(
                index,
                Html.Element(el.name, el.attrs, el.key, at(el.children, rest)(f))
              )
            case other => refuse(s"path step $index landed on $other")
          }
    }

  private def onElement(anchor: Vector[Html], path: List[Int], expect: String)(
      f: Html.Element => Html.Element
  ): Vector[Html] =
    at(anchor, path) {
      case el: Html.Element if el.name == expect => Vector(f(el))
      case other => refuse(s"expected <$expect> at $path, found $other")
    }

  private def checkName(node: Html, expect: String, path: List[Int]): Unit = {
    val found = node match {
      case el: Html.Element => el.name
      case Html.Text(_)     => "#text"
      case other            => other.toString
    }
    if (found != expect) refuse(s"expected <$expect> at $path, found $found")
  }

  private def refuse(reason: String): Nothing = throw new AssertionError(reason)
}
