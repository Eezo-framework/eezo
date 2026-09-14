package io.eezo.live

import io.eezo.core.html.Html

/** One DOM edit, as the differ emits it and the client applies it.
  *
  * A patch names its target by a path of `childNodes` indices from the page's mount anchor, which
  * is design/live.md's Option A: the tree the server diffs is canonical ([[Canonical]]), so tree
  * child index equals DOM child index and the two sides count the same list. The payload carries
  * [[Html]] rather than a rendered string, so the reference applier in this module's tests can
  * apply it structurally; [[Wire]] renders at the boundary, once, where bytes are the point.
  *
  * Every case that resolves a non empty path carries the tag name it expects to find there, so the
  * client can refuse a patch that resolved to the wrong node instead of corrupting the page. The
  * check is `expect`, an `Option`, because the empty path has nothing to check: the anchor is found
  * by the page id, not by walking, and a walk of zero steps cannot go wrong.
  */
enum Patch {

  /** Replaces every child of the element at `path` with `children`. The differ's sledgehammer and
    * the resync message's whole vocabulary: setting the anchor's children is a full re-render.
    */
  case SetChildren(path: List[Int], expect: Option[String], children: Vector[Html])
}
