package io.eezo.live

import io.eezo.core.html.Html

/** `(before, after) → patches`, under the one contract that matters (design/live.md §1.1): applying
  * the patches to a DOM holding `before` produces the DOM that rendering `after` would. The round
  * trip harness in `research/harnesses/live-roundtrip` holds it against the real client applier;
  * `RoundTripSuite` holds it against the reference applier on every generated case.
  *
  * This is milestone M0's differ: it validates both trees and knows one move, replacing the mount
  * anchor's children with the new render. Correct for every pair by construction, minimal for none.
  * M1 grows it case by case, each case entering through the harness.
  */
object Differ {

  /** The patches that carry a page from `before` to `after`. Both arguments are component renders:
    * exactly one root element, in canonical form, or [[NotCanonical]] says which rule was broken.
    */
  def diff(before: Html, after: Html): List[Patch] = {
    val a = Canonical.root(before)
    val b = Canonical.root(after)
    if (a == b) Nil
    else List(Patch.SetChildren(path = Nil, expect = None, children = Vector(b)))
  }
}
