package io.eezo.live

import io.eezo.core.html.Html

/** One DOM edit, as the differ emits it and the client applies it.
  *
  * A patch names its target by a path of `childNodes` indices from the page's mount anchor, which
  * is design/live.md's Option A: the tree the server diffs is canonical ([[Canonical]]), so tree
  * child index equals DOM child index and the two sides count the same list. The anchor's children
  * hold the component's root element at index 0, so every path into the component starts with `0`.
  * Payloads carry [[Html]] rather than rendered strings, so the reference applier in this module's
  * tests can apply them structurally; [[Wire]] renders at the boundary, once.
  *
  * `expect` is the integrity check (design/live.md §1.1, fail loud): the node name the patch
  * believes sits at its path, `"#text"` for a text node. The client refuses a patch whose target
  * disagrees, records it, and never applies it — the check exists to catch the exact bug this
  * design historically breeds, both sides counting children differently. [[SetText]] carries none
  * because its op admits only one node kind, which the applier checks by node type; the two
  * `Option[String]` cases are `None` only at the anchor, which is found by page id, not by walking,
  * so a walk of zero steps has nothing to verify.
  *
  * **Application order is part of the contract.** The applier applies a frame's patches in list
  * order, and the differ guarantees each patch's path is valid *at its turn*: within one child list
  * it emits in-place updates first (counts unchanged), then removals from the highest index down
  * (so earlier indices stay true), then a single append. `DiffSuite` pins that order.
  */
enum Patch {

  /** Sets a text node's content. `text` is the *unescaped* value: the client assigns `data`, which
    * is literal, so shipping the escaped form would print entities at the user.
    */
  case SetText(path: List[Int], text: String)

  /** Sets one attribute. `value` is the raw text (the DOM's `setAttribute` does not parse
    * entities); a bare boolean attribute travels as the empty string, because the DOM cannot tell
    * `disabled` from `disabled=""` and the differ does not pretend to.
    */
  case SetAttr(path: List[Int], expect: String, name: String, value: String)

  case RemoveAttr(path: List[Int], expect: String, name: String)

  /** Replaces the node at `path` outright: the move for a changed tag name or a change of node
    * kind, where no finer patch has a target that survives.
    */
  case ReplaceNode(path: List[Int], expect: String, node: Html)

  /** Removes the node at `path`. For trailing removals the differ emits highest index first. */
  case RemoveNode(path: List[Int], expect: String)

  /** Appends `children` after the last child of the element at `path`. */
  case AppendChildren(path: List[Int], expect: String, children: Vector[Html])

  /** Replaces every child of the element at `path` (`None` = the anchor). The differ's fallback for
    * a child list holding raw markup — one `Raw` tree child can parse into any number of DOM nodes,
    * so no index past it can be trusted — and the resync message's whole vocabulary.
    */
  case SetChildren(path: List[Int], expect: Option[String], children: Vector[Html])
}
