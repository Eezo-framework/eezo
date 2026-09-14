package io.eezo.live

import io.eezo.core.html.{Attr, Attrs, Html, Key, Mod, Tag, Tags}

/** Deterministic random component renders, built through the real DSL.
  *
  * Through `Tag.apply` and nothing else, because that is the door every application render walks
  * through, so every generated tree is canonical by the same guard, and a canonicalisation bug in
  * the DSL surfaces here as a `NotCanonical` from the differ rather than hiding behind a hand-built
  * fixture. A seeded `Random` rather than a property testing library: a failing seed is its own
  * reproduction, and the same generator feeds both `RoundTripSuite` and the corpus the jsdom
  * harness replays, so the two sides of the invariant see the same distribution.
  */
final class Gen(seed: Long) {

  private val rnd = new scala.util.Random(seed)

  private val containers =
    Vector(
      Tags.div,
      Tags.span,
      Tags.p,
      Tags.section,
      Tags.article,
      Tags.h1,
      Tags.h2,
      Tags.em,
      Tags.strong,
      Tags.ul,
      Tags.li,
      Tags.a,
      Tags.label
    )

  /** Escaping's whole alphabet is on purpose: text that needs no escaping proves nothing. */
  private val texts =
    Vector("plain", "a<b", "x&y", "q\"uote", "it's", "café ✓", "0", " lead and trail ")

  private val classes = Vector("lead", "row", "hidden", "x-1", "wide tall")

  def tree(depth: Int): Html.Element =
    element(containers(rnd.nextInt(containers.length)), depth)

  private def element(tag: Tag, depth: Int): Html.Element = {
    val mods = Vector.newBuilder[Mod]

    if (rnd.nextInt(3) == 0) mods += (Attrs.cls   := classes(rnd.nextInt(classes.length)))
    if (rnd.nextInt(4) == 0) mods += (Attrs.title := texts(rnd.nextInt(texts.length)))
    if (rnd.nextInt(6) == 0) mods += Key(s"k${rnd.nextInt(8)}")

    val childCount = if (depth <= 0) rnd.nextInt(2) else rnd.nextInt(4)
    for (_ <- 0 until childCount) mods += child(depth)

    tag(mods.result()*) match {
      case el: Html.Element => el
      case other            => throw new IllegalStateException(s"a tag built $other")
    }
  }

  private def child(depth: Int): Html =
    rnd.nextInt(if (depth <= 0) 4 else 6) match {
      case 0 | 1 => Html.text(texts(rnd.nextInt(texts.length)))
      case 2     => Tags.br()
      case 3     =>
        if (rnd.nextBoolean()) Html.raw("<b>bold</b> and <i>italic</i>")
        else Tags.img(Attrs.src := "/x.png", Attrs.alt := "x")
      case _ => element(containers(rnd.nextInt(containers.length)), depth - 1)
    }

  /** A random edit somewhere in `root`, made through the DSL so the result stays canonical. */
  def mutate(root: Html.Element): Html = {
    val target = rnd.nextInt(countElements(root))
    var seen   = -1

    def walk(el: Html.Element): Html = {
      seen += 1
      val self  = seen
      val after = Html.Element(
        el.name,
        el.attrs,
        el.key,
        el.children.map {
          case nested: Html.Element => walk(nested)
          case leaf                 => leaf
        }
      )
      if (self == target) edit(after) else after
    }

    walk(root)
  }

  /** Rebuilds the node through `Tag.apply` with one change, so text edits merge and empty text
    * drops exactly as an application's re-render would. Void elements only ever take attribute
    * edits: handing one a child would build the non canonical tree the differ exists to refuse.
    */
  private def edit(node: Html): Html = node match {
    case el: Html.Element =>
      val isVoid = el.children.isEmpty && Set("br", "img", "input", "hr")(el.name)

      def rebuilt(
          name: String = el.name,
          attrs: Vector[Attr] = el.attrs,
          children: Vector[Html] = el.children
      ): Html = {
        val mods = Vector.newBuilder[Mod]
        attrs.foreach(mods += _)
        el.key.foreach(k => mods += Key(k))
        children.foreach(mods += _)
        Tag(name)(mods.result()*)
      }

      rnd.nextInt(if (isVoid) 2 else 6) match {
        case 0 => rebuilt(attrs = el.attrs :+ (Attrs.cls := classes(rnd.nextInt(classes.length))))
        case 1 => rebuilt(attrs = el.attrs.dropRight(1))
        case 2 => rebuilt(children = el.children :+ Html.text(texts(rnd.nextInt(texts.length))))
        case 3 => rebuilt(children = el.children.dropRight(1))
        case 4 =>
          rebuilt(children = el.children :+ element(containers(rnd.nextInt(containers.length)), 1))
        case _ => rebuilt(name = containers(rnd.nextInt(containers.length)).name)
      }

    case other => other
  }

  private def countElements(el: Html.Element): Int =
    1 + el.children.collect { case nested: Html.Element => countElements(nested) }.sum
}
