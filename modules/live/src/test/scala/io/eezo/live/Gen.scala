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
  *
  * Nesting follows the parser's rules the way a correct application does: flow containers hold
  * anything, phrasing containers hold phrasing, paragraphs and headings hold phrasing but never
  * each other, lists hold keyed rows. The generator's first draft nested freely and the harness
  * caught the parser restructuring an `h2` inside an `h2` — that is now `Canonical`'s job to
  * refuse, and this generator's job is to model the applications that pass it.
  */
final class Gen(seed: Long) {

  private val rnd = new scala.util.Random(seed)

  private val flowTags     = Vector(Tags.div, Tags.section, Tags.article)
  private val phrasingTags = Vector(Tags.span, Tags.em, Tags.strong, Tags.label)
  private val pblockTags   = Vector(Tags.p, Tags.h1, Tags.h2)

  /** Escaping's whole alphabet is on purpose: text that needs no escaping proves nothing. */
  private val texts =
    Vector("plain", "a<b", "x&y", "q\"uote", "it's", "café ✓", "0", " lead and trail ")

  private val classes = Vector("lead", "row", "hidden", "x-1", "wide tall")

  def tree(depth: Int): Html.Element = flow(depth)

  private def pick(tags: Vector[Tag]): Tag = tags(rnd.nextInt(tags.length))

  private def commonMods(key: Option[String]): Vector[Mod] = {
    val mods   = Vector.newBuilder[Mod]
    if (rnd.nextInt(3) == 0) mods += (Attrs.cls   := classes(rnd.nextInt(classes.length)))
    if (rnd.nextInt(4) == 0) mods += (Attrs.title := texts(rnd.nextInt(texts.length)))
    key.foreach(k => mods += Key(k))
    mods.result()
  }

  private def build(tag: Tag, mods: Vector[Mod]): Html.Element =
    tag(mods*) match {
      case el: Html.Element => el
      case other            => throw new IllegalStateException(s"a tag built $other")
    }

  /** A flow container: may hold anything. */
  private def flow(depth: Int, key: Option[String] = None): Html.Element = {
    val children   = Vector.newBuilder[Mod]
    val childCount = if (depth <= 0) rnd.nextInt(2) else rnd.nextInt(4)
    for (_ <- 0 until childCount) children += flowChild(depth)
    build(pick(flowTags), commonMods(key) ++ children.result())
  }

  private def flowChild(depth: Int): Html =
    rnd.nextInt(if (depth <= 0) 4 else 9) match {
      case 0 | 1 => Html.text(texts(rnd.nextInt(texts.length)))
      case 2     => Tags.br()
      case 3     =>
        if (rnd.nextBoolean()) Html.raw("<b>bold</b> and <i>italic</i>")
        else Tags.img(Attrs.src := "/x.png", Attrs.alt := "x")
      case 4 | 5 => flow(depth - 1)
      case 6     => phrasing(depth - 1, insideA = false)
      case 7     => pblock(depth - 1)
      case _     => list(depth - 1)
    }

  /** A phrasing container: phrasing content only, and never an `a` inside an `a`. */
  private def phrasing(depth: Int, insideA: Boolean): Html.Element = {
    val tag =
      if (!insideA && rnd.nextInt(4) == 0) Tags.a
      else pick(phrasingTags)
    val nowInsideA = insideA || (tag.name == "a")

    val children   = Vector.newBuilder[Mod]
    val childCount = if (depth <= 0) rnd.nextInt(2) else rnd.nextInt(3)
    for (_ <- 0 until childCount) children += phrasingChild(depth, nowInsideA)
    build(tag, commonMods(None) ++ children.result())
  }

  private def phrasingChild(depth: Int, insideA: Boolean): Html =
    rnd.nextInt(if (depth <= 0) 4 else 5) match {
      case 0 | 1 => Html.text(texts(rnd.nextInt(texts.length)))
      case 2     => Tags.br()
      case 3     => Tags.img(Attrs.src := "/x.png", Attrs.alt := "x")
      case _     => phrasing(depth - 1, insideA)
    }

  /** A paragraph or heading: phrasing content, never one of its own kind inside. */
  private def pblock(depth: Int): Html.Element = {
    val children   = Vector.newBuilder[Mod]
    val childCount = rnd.nextInt(3)
    for (_ <- 0 until childCount) children += phrasingChild(depth, insideA = false)
    build(pick(pblockTags), commonMods(None) ++ children.result())
  }

  /** A keyed list: `ul` or `ol`, every row an `li` with a key unique among its siblings, which is
    * the only shape `Canonical` admits once any child is keyed.
    */
  private def list(depth: Int): Html.Element = {
    val rows     = 1 + rnd.nextInt(4)
    val children = Vector.newBuilder[Mod]
    for (i <- 0 until rows) {
      val body = Vector.newBuilder[Mod]
      body += Key(s"k$i")
      val bodyCount = if (depth <= 0) 1 else 1 + rnd.nextInt(2)
      for (_ <- 0 until bodyCount) body += flowChild(math.max(depth - 1, 0))
      children += build(Tags.li, body.result())
    }
    build(if (rnd.nextBoolean()) Tags.ul else Tags.ol, commonMods(None) ++ children.result())
  }

  /** A random edit somewhere in `root`, made through the DSL so the result stays canonical.
    * Elements are numbered preorder and the walk threads the count through its results, so the edit
    * lands on exactly one node with nothing mutated along the way.
    */
  def mutate(root: Html.Element): Html = {
    val target = rnd.nextInt(countElements(root))

    def walk(el: Html.Element, seen: Int): (Html, Int) = {
      val (children, next) =
        el.children.foldLeft((Vector.empty[Html], seen + 1)) { (state, child) =>
          val (walked, count) = state
          child match {
            case nested: Html.Element =>
              val (result, after) = walk(nested, count)
              (walked :+ result, after)
            case leaf => (walked :+ leaf, count)
          }
        }
      val rebuilt = Html.Element(el.name, el.attrs, el.key, children)
      (if (seen == target) edit(rebuilt) else rebuilt, next)
    }

    walk(root, 0)._1
  }

  /** Rebuilds the node through `Tag.apply` with one change, so text edits merge and empty text
    * drops exactly as an application's re-render would. Every edit keeps the node inside its
    * nesting class: a rename stays in the element's own pool, an appended child is one the parent
    * may hold, and a keyed list stays all-keyed with unique keys.
    */
  private def edit(node: Html): Html = node match {
    case el: Html.Element =>
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

      def attrEdit(): Html =
        if (el.attrs.nonEmpty && rnd.nextBoolean()) rebuilt(attrs = el.attrs.dropRight(1))
        else rebuilt(attrs = el.attrs :+ (Attrs.cls := classes(rnd.nextInt(classes.length))))

      val renamePool: Vector[String] = el.name match {
        case "div" | "section" | "article"            => flowTags.map(_.name)
        case "span" | "em" | "strong" | "label" | "a" => phrasingTags.map(_.name)
        case "p" | "h1" | "h2"                        => pblockTags.map(_.name)
        case "ul" | "ol"                              => Vector("ul", "ol")
        case _                                        => Vector.empty
      }

      val keyed = el.children.nonEmpty && el.children.forall {
        case child: Html.Element => child.key.isDefined
        case _                   => false
      }

      if (keyed)
        rnd.nextInt(5) match {
          case 0 => attrEdit()
          case 1 => rebuilt(children = el.children.dropRight(1))
          case 2 => rebuilt(children = rnd.shuffle(el.children))
          case 3 =>
            val freshRow = build(Tags.li, Vector(Key(s"f${rnd.nextInt(1000)}"), Html.text("fresh")))
            rebuilt(children = el.children :+ freshRow)
          case _ =>
            if (renamePool.nonEmpty) rebuilt(name = renamePool(rnd.nextInt(renamePool.length)))
            else attrEdit()
        }
      else {
        val appended: Option[Html] = el.name match {
          case "div" | "section" | "article" | "li" =>
            Some(if (rnd.nextBoolean()) Html.text(texts(rnd.nextInt(texts.length))) else flow(0))
          case "span" | "em" | "strong" | "label" | "a" | "p" | "h1" | "h2" =>
            // insideA = true blankets out nested anchors without tracking real ancestry.
            Some(
              if (rnd.nextBoolean()) Html.text(texts(rnd.nextInt(texts.length)))
              else phrasing(0, insideA = true)
            )
          case _ => None
        }

        rnd.nextInt(6) match {
          case 0 | 1                     => attrEdit()
          case 2 if appended.isDefined   => rebuilt(children = el.children :+ appended.get)
          case 3 if el.children.nonEmpty => rebuilt(children = el.children.dropRight(1))
          case 4 if renamePool.nonEmpty  =>
            rebuilt(name = renamePool(rnd.nextInt(renamePool.length)))
          case _ => attrEdit()
        }
      }

    case other => other
  }

  private def countElements(el: Html.Element): Int =
    1 + el.children.collect { case nested: Html.Element => countElements(nested) }.sum
}
