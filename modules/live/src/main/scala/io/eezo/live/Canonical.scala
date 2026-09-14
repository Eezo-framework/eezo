package io.eezo.live

import io.eezo.core.html.Html

/** The tree form both sides of the wire agree on, checked loudly rather than repaired silently.
  *
  * Two families of rule, one purpose: a patch names its target by child indices, so the DOM the
  * browser parses must hold exactly the children the tree does.
  *
  * The first family is `Tag.apply`'s own guarantees, re-checked: fragments spliced, adjacent text
  * merged, empty text dropped, void elements childless. What arrives here breaking them was built
  * by hand around the guard, and normalising it in the differ would hide the divergence until the
  * DOM and the tree disagree in production.
  *
  * The second family is [[stable]]: nesting the HTML parser would *restructure*. The DSL cannot
  * prevent `p(div(...))`, and the browser answers it by closing the `p` early and hoisting the
  * `div` out — the page still looks right, so nothing warns, and every child index after the hoist
  * is fiction. The round trip harness found this family (a generated `h2` inside `h2`), which is
  * why the check exists. The rules below are the parser's restructuring moves from the in-body
  * insertion mode, applied conservatively: a construction refused here that some barrier element
  * would in fact have kept stable is a loud false positive with a clear message, which is the
  * failure mode this module prefers.
  */
private[live] object Canonical {

  /** The root of a component render: exactly one element. Text, raw markup or a fragment at the
    * root leaves "the component's root node" meaning nothing, and a mount with no single node to
    * anchor patches on.
    */
  def root(tree: Html): Html.Element = tree match {
    case element: Html.Element =>
      // The mount anchor is a div, and the root is parsed in its context: a table-family root
      // would be dropped or foster-parented before the page's first patch.
      if (TableFamily.contains(element.name))
        throw NotCanonical(
          s"<${element.name}> cannot be a component's root: the mount anchor is a <div>, and " +
            "the parser drops table parts it finds there. Render the whole <table>."
        )
      validate(element, Scope.initial)
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

  private def validate(element: Html.Element, scope: Scope): Unit = {
    validateKeys(element)

    if (Html.VoidTags.contains(element.name) && element.children.nonEmpty)
      throw NotCanonical(
        s"<${element.name}> is a void element with ${element.children.size} children. " +
          "A browser renders none of them, so the tree and the DOM would disagree."
      )

    val inner = scope.entering(element.name)

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
          if (TableContext.contains(element.name))
            throw NotCanonical(
              s"text directly inside <${element.name}>: the parser foster-parents it in front " +
                "of the table, so the tree and the DOM would disagree. Put it in a cell."
            )
        case Html.Raw(_) =>
          if (TableContext.contains(element.name))
            throw NotCanonical(
              s"raw markup directly inside <${element.name}>: the parser foster-parents " +
                "non-table content in front of the table. Put it in a cell."
            )
        case nested: Html.Element =>
          stable(element, nested, inner)
          validate(nested, inner)
      }
    }
  }

  /** Refuses nesting the parser would restructure: content hoisted out, elements auto-closed, or
    * tags ignored outright. Each message names the parser's move, because "invalid HTML" is not
    * actionable and "the browser will silently rearrange this" is.
    */
  private def stable(parent: Html.Element, child: Html.Element, scope: Scope): Unit = {
    def refuse(what: String): Nothing =
      throw NotCanonical(s"<${child.name}> inside <${parent.name}>: $what")

    if (scope.inP && ClosesP.contains(child.name))
      refuse(
        s"a <${child.name}> start tag closes the open <p> and lands beside it, not in it. " +
          "Every sibling index after the hoist is wrong. Restructure without the <p>."
      )
    if (Headings.contains(child.name) && Headings.contains(parent.name))
      refuse("a heading start tag closes the heading already open, so they end up siblings.")
    if (child.name == "a" && scope.inA)
      refuse("the parser runs the adoption agency on nested <a> and restructures both.")
    if (child.name == "button" && scope.inButton)
      refuse("a <button> start tag closes the <button> already open.")
    if (child.name == "form" && scope.inForm)
      refuse("a nested <form> tag is ignored outright; its children spill into the outer form.")
    if (child.name == "li" && scope.inLi)
      refuse("an <li> start tag closes the <li> already open unless a <ul> or <ol> intervenes.")
    if ((child.name == "dt" || child.name == "dd") && scope.inDtDd)
      refuse(s"a <${child.name}> start tag closes the <dt> or <dd> already open.")
    if (child.name == "option" && parent.name == "option")
      refuse("an <option> start tag closes the <option> already open.")

    // The table family builds through a strict parent chain; anywhere else the parser
    // foster-parents the element in front of the table or drops it.
    child.name match {
      case "caption" | "colgroup" | "thead" | "tbody" | "tfoot" =>
        if (parent.name != "table")
          refuse(
            "table sections live directly under <table>; anywhere else the parser drops or moves them."
          )
      case "tr" =>
        if (!Set("thead", "tbody", "tfoot").contains(parent.name))
          refuse(
            "rows live under <thead>, <tbody> or <tfoot>. Even directly under <table> the " +
              "parser wraps them in a <tbody> the tree does not have, skewing every index."
          )
      case "td" | "th" =>
        if (parent.name != "tr")
          refuse("cells live under <tr>; anywhere else the parser moves them.")
      case "col" =>
        if (parent.name != "colgroup") refuse("<col> lives under <colgroup>.")
      case _ =>
        if (parent.name == "table" || TableContext.contains(parent.name))
          refuse(
            "only the table family may sit here; the parser foster-parents anything else in front of the table."
          )
        if (parent.name == "select" && child.name != "option" && child.name != "optgroup")
          refuse("a <select> holds <option> and <optgroup>; the parser ignores anything else.")
    }
  }

  /** Keys are all or nothing per parent, and unique. Design/live.md §1.2 narrows the mixed-list
    * question to this rule so the silent fallback it warns about cannot exist: a parent where one
    * child carries a key and a sibling (any sibling: an unkeyed element, bare text, raw markup)
    * does not is refused, and so is a repeated key, which would make "the same row" ambiguous the
    * moment the keyed diff in M5 reconciles by identity.
    */
  private def validateKeys(element: Html.Element): Unit = {
    val keyed = element.children.collect {
      case child: Html.Element if child.key.isDefined => child
    }

    if (keyed.nonEmpty) {
      if (keyed.size != element.children.size)
        throw NotCanonical(
          s"<${element.name}> mixes keyed and unkeyed children. Keys are identity for the whole " +
            "list: give every child a key, or none."
        )
      val keys = keyed.flatMap(_.key)
      keys.diff(keys.distinct).headOption.foreach { duplicate =>
        throw NotCanonical(
          s"<${element.name}> has two children with the key '$duplicate'. A key is a child's " +
            "identity across renders, and two nodes cannot be the same one."
        )
      }
    }
  }

  /** What is open on the way down, for the auto-close rules. Barriers are approximated: entering a
    * scope-establishing element (a cell, `object`, `template`; `button` for the `p` rule, a list
    * for `li`) clears the flag the spec's scope check would no longer see through.
    */
  private final case class Scope(
      inP: Boolean,
      inA: Boolean,
      inButton: Boolean,
      inForm: Boolean,
      inLi: Boolean,
      inDtDd: Boolean
  ) {
    def entering(name: String): Scope = {
      val barrier = Scope.Barriers.contains(name)
      Scope(
        inP = (inP || name == "p") && !barrier && name != "button",
        inA = (inA || name == "a") && !barrier,
        inButton = (inButton || name == "button") && !barrier,
        inForm = (inForm || name == "form") && name != "template",
        inLi = (inLi || name == "li") && !barrier && name != "ul" && name != "ol",
        inDtDd = (inDtDd || name == "dt" || name == "dd") && !barrier
      )
    }
  }

  private object Scope {
    val initial: Scope = Scope(false, false, false, false, false, false)

    /** The elements every scope variant treats as a wall. */
    val Barriers: Set[String] = Set("td", "th", "caption", "object", "template", "marquee")
  }

  private val Headings = Set("h1", "h2", "h3", "h4", "h5", "h6")

  /** The elements that only exist inside a table's parent chain. */
  private val TableFamily =
    Set("caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "td", "th")

  /** Elements whose children the parser reseats unless they are the table family. */
  private val TableContext = Set("table", "thead", "tbody", "tfoot", "tr", "colgroup")

  /** The start tags that close an open `<p>` (the in-body insertion mode's list). */
  private val ClosesP: Set[String] = Set(
    "address",
    "article",
    "aside",
    "blockquote",
    "center",
    "details",
    "dialog",
    "dir",
    "div",
    "dl",
    "dd",
    "dt",
    "fieldset",
    "figcaption",
    "figure",
    "footer",
    "form",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "header",
    "hgroup",
    "hr",
    "li",
    "listing",
    "main",
    "menu",
    "nav",
    "ol",
    "p",
    "plaintext",
    "pre",
    "section",
    "summary",
    "table",
    "ul",
    "xmp"
  )
}

/** A tree the differ refuses: built by hand outside `Tag.apply`'s canonicalisation, or nested in a
  * way the HTML parser would restructure.
  */
final case class NotCanonical(message: String) extends IllegalArgumentException(message)
