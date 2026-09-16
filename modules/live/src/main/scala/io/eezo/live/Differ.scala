package io.eezo.live

import io.eezo.core.html.Html

/** `(before, after) → patches`, under the one contract that matters (design/live.md §1.1): applying
  * the patches to a DOM holding `before` produces the DOM that rendering `after` would. The round
  * trip harness in `research/harnesses/live-roundtrip` holds it against the real client applier;
  * `RoundTripSuite` holds it against the reference applier on every generated case.
  *
  * The recursion prefers the finest patch that is still correct — a text edit over a subtree
  * replace, an attribute set over either — because replacing a node destroys focus, caret, scroll
  * and playback state the user can see (design/live.md §1.1 on §4.6). It bails to a coarser move at
  * exactly two seams, each for a stated reason: a changed tag name or node kind is
  * [[Patch.ReplaceNode]], because no finer target survives the change; and a child list holding raw
  * markup diffs as one [[Patch.SetChildren]], because one `Raw` tree child can parse into any
  * number of DOM nodes, so no sibling index past it can be trusted (design/live.md §2.2).
  *
  * Child lists are diffed positionally: index against index, trailing surplus removed or appended.
  * "Insert one row at the top" therefore patches every row, which is the cost design/live.md
  * accepts until M5's keyed reconciliation; the keys are already carried, loudly validated, and
  * diffed as ordinary attributes until then.
  *
  * Attributes are compared as name → value maps, not as ordered lists: attribute order has no DOM
  * meaning, `setAttribute` cannot reorder what exists, and a differ that chased order would replace
  * nodes for a difference no browser can observe. For the same reason a bare attribute and an
  * empty-valued one (`disabled` vs `disabled=""`) are the same attribute here, as they are in the
  * DOM.
  *
  * Every function returns its patches; nothing accumulates by mutation. Concatenation order *is*
  * the application-order contract `Patch` documents: within one child list, in-place updates first,
  * then removals highest index first, then the append.
  */
object Differ {

  /** The patches that carry a page from `before` to `after`. Both arguments are component renders:
    * exactly one root element, in canonical form, or [[NotCanonical]] says which rule was broken.
    */
  def diff(before: Html, after: Html): List[Patch] = {
    val a = Canonical.root(before)
    val b = Canonical.root(after)
    // The anchor holds the root at child 0, so the root's own path is List(0) and a root tag
    // change falls out of `node` as an ordinary ReplaceNode.
    node(a, b, List(0))
  }

  private def node(before: Html, after: Html, path: List[Int]): List[Patch] =
    if (before == after) Nil
    else
      (before, after) match {
        case (Html.Text(_), Html.Text(escaped)) =>
          List(Patch.SetText(path, Html.unescape(escaped)))

        case (a: Html.Element, b: Html.Element) if a.name == b.name =>
          attributes(a, b, path) ++ children(a, b, path)

        // A changed tag, or a change of node kind: no finer target survives.
        case _ =>
          List(Patch.ReplaceNode(path, nodeName(before), after))
      }

  private def attributes(a: Html.Element, b: Html.Element, path: List[Int]): List[Patch] = {
    val before = attrMap(a)
    val after  = attrMap(b)

    // Removals in the old tree's order and sets in the new tree's, so the emission is
    // deterministic without inventing an ordering of its own.
    val removed = attrNames(a).collect {
      case name if !after.contains(name) => Patch.RemoveAttr(path, a.name, name)
    }
    val set = attrNames(b).collect {
      case name if !before.get(name).contains(after(name)) =>
        Patch.SetAttr(path, a.name, name, after(name))
    }
    removed ++ set
  }

  /** The attributes the DOM will hold: values as raw text, a bare attribute as the empty string
    * (the DOM knows no difference), and the key under the name it renders as, so a key change is an
    * ordinary attribute patch until M5 makes keys identity.
    */
  private def attrMap(element: Html.Element): Map[String, String] =
    element.attrs.map(attr => attr.name -> attr.value.map(_.text).getOrElse("")).toMap ++
      element.key.map(KeyAttr -> _)

  private def attrNames(element: Html.Element): List[String] =
    element.attrs.map(_.name).toList ++ element.key.map(_ => KeyAttr)

  private def children(a: Html.Element, b: Html.Element, path: List[Int]): List[Patch] =
    if (a.children == b.children) Nil
    // An ignored subtree belongs to someone else (a chart library, an embedded editor): the
    // element's own attributes still patch, its children are never touched. The round-trip
    // invariant deliberately does not hold below this attribute.
    else if (a.attrs.exists(_.name == Differ.IgnoreAttr)) Nil
    else if (a.children.exists(isRaw) || b.children.exists(isRaw))
      List(Patch.SetChildren(path, Some(a.name), b.children))
    else if (allKeyed(a.children) && allKeyed(b.children)) keyed(a, b, path)
    else {
      val shared = math.min(a.children.length, b.children.length)

      val updated = (0 until shared).toList.flatMap { index =>
        node(a.children(index), b.children(index), path :+ index)
      }
      // Highest index first, so each removal's path is still true when its turn comes.
      val removed = ((a.children.length - 1) to shared by -1).toList.map { index =>
        Patch.RemoveNode(path :+ index, nodeName(a.children(index)))
      }
      val appended =
        if (b.children.length > shared)
          List(Patch.AppendChildren(path, a.name, b.children.drop(shared)))
        else Nil

      updated ++ removed ++ appended
    }

  private def isRaw(child: Html): Boolean = child.isInstanceOf[Html.Raw]

  /** Canonical already enforced all-or-nothing and uniqueness; this only asks which side of the
    * rule a non-empty list is on.
    */
  private def allKeyed(children: Vector[Html]): Boolean =
    children.nonEmpty && children.forall {
      case el: Html.Element => el.key.isDefined
      case _                => false
    }

  /** Keyed reconciliation: children matched by identity, not position, so a reorder moves nodes
    * (keeping their DOM state: focus, scroll, playback) and a prepend costs one insert.
    *
    * Four stages, emitted in application order (see `Patch`): removals of vanished keys, highest
    * index first; moves for surviving keys whose relative order changed — only the keys off a
    * longest increasing subsequence of old positions move, which is what makes the move count
    * minimal rather than one per shifted row; inserts of new keys at their final positions,
    * ascending; then content recursion on surviving pairs, addressed at final positions, valid
    * because all structure has already happened. The moves are computed right to left against a
    * simulated list with an anchor, the same walk the applier's remove-then-insert semantics
    * replays, and the round-trip harness holds the two in agreement.
    */
  private def keyed(a: Html.Element, b: Html.Element, path: List[Int]): List[Patch] = {
    def keyOf(child: Html): String = child match {
      case el: Html.Element => el.key.getOrElse(throw new IllegalStateException("allKeyed lied"))
      case other            => throw new IllegalStateException(s"keyed child $other")
    }

    val oldKeys  = a.children.map(keyOf)
    val newKeys  = b.children.map(keyOf)
    val oldByKey = oldKeys.zip(a.children).toMap
    val oldSet   = oldKeys.toSet
    val newSet   = newKeys.toSet

    val removals = oldKeys.zipWithIndex.reverse.collect {
      case (key, index) if !newSet.contains(key) =>
        Patch.RemoveNode(path :+ index, nodeName(a.children(index)))
    }.toList

    val kept   = oldKeys.filter(newSet.contains)
    val target = newKeys.filter(oldSet.contains)

    val oldPosition = kept.zipWithIndex.toMap
    val stableKeys  = {
      val positions = target.map(oldPosition)
      val lis       = lisValues(positions)
      target.zip(positions).collect { case (key, pos) if lis(pos) => key }.toSet
    }

    val (movesReversed, _, _) =
      target.indices.reverse.foldLeft((List.empty[Patch], kept, Option.empty[String])) {
        case ((acc, sim, anchor), t) =>
          val key = target(t)
          if (stableKeys(key)) (acc, sim, Some(key))
          else {
            val from    = sim.indexOf(key)
            val without = sim.patch(from, Nil, 1)
            val to      = anchor.fold(without.length)(without.indexOf)
            val placed  = without.patch(to, Vector(key), 0)
            val acc2    =
              if (from == to) acc else Patch.MoveChild(path, a.name, from, to) :: acc
            (acc2, placed, Some(key))
          }
      }
    // The fold walked right to left prepending, so reversing restores emission order.
    val moves = movesReversed.reverse

    val inserts = newKeys.zipWithIndex.collect {
      case (key, index) if !oldSet.contains(key) =>
        Patch.InsertChild(path, a.name, index, b.children(index))
    }.toList

    val content = newKeys.zipWithIndex.toList.flatMap { case (key, index) =>
      if (oldSet.contains(key)) node(oldByKey(key), b.children(index), path :+ index) else Nil
    }

    removals ++ moves ++ inserts ++ content
  }

  /** One longest increasing subsequence of `xs`, as the set of its values. Patience sorting with a
    * linear scan for the placement: O(n²) in the worst case, and n is one element's sibling count,
    * not a data set.
    */
  private def lisValues(xs: Vector[Int]): Set[Int] =
    if (xs.isEmpty) Set.empty
    else {
      val (tails, prev) =
        xs.zipWithIndex.foldLeft((Vector.empty[Int], Map.empty[Int, Int])) {
          case ((tails, prev), (x, i)) =>
            val pos = tails.indexWhere(t => xs(t) >= x) match {
              case -1 => tails.length
              case p  => p
            }
            val linked = if (pos > 0) prev.updated(i, tails(pos - 1)) else prev
            val placed = if (pos == tails.length) tails :+ i else tails.updated(pos, i)
            (placed, linked)
        }

      @scala.annotation.tailrec
      def unwind(i: Int, acc: Set[Int]): Set[Int] = prev.get(i) match {
        case Some(p) => unwind(p, acc + xs(i))
        case None    => acc + xs(i)
      }
      unwind(tails.last, Set.empty)
    }

  /** What the client will find at the path: the DOM's own spelling. Never called on `Raw` — the
    * children rule above keeps every raw-adjacent index out of the patch space.
    */
  private def nodeName(node: Html): String = node match {
    case element: Html.Element => element.name
    case Html.Text(_)          => "#text"
    case other                 => throw new IllegalStateException(s"$other is never addressed")
  }

  private val KeyAttr = "data-eezo-key"

  /** The opt-out: an element carrying it keeps its children out of the differ's hands entirely.
    * `Live.ignore` mints it; the name lives here because the differ is the one that obeys it.
    */
  private[live] val IgnoreAttr = "data-eezo-ignore"
}
