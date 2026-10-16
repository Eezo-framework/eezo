package site

import io.eezo.core.html.*

/** PROTOTYPE, throwaway: three variants of the front page with the launch video on it, switchable
  * via `?variant=` on the existing `/` route. Not for main. See the prototype skill's rules.
  *
  *   - A, "Hero video": the video takes the code block's place in the hero; the model and its
  *     routes move to a strip of their own under the hero.
  *   - B, "Red band": the hero is untouched; a full bleed Scala red band between the hero and the
  *     docs pillars carries the video with a line of copy.
  *   - C, "Video first": the hero stacks, headline on top, the video wide under it in its red
  *     frame, the calls to action under the video; the model strip follows as in A.
  */
object LandingVideoPrototype {

  val YouTubeId: String = "IOsSpHu3eKE"

  val Variants: Vector[(String, String)] = Vector(
    "A" -> "Hero video",
    "B" -> "Red band",
    "C" -> "Video first",
    "D" -> "Grape band",
    "E" -> "Ink band"
  )

  val Iframe: Tag = Tag("iframe")

  def variant(key: Option[String]): String =
    key.map(_.toUpperCase).filter(k => Variants.exists(_._1 == k)).getOrElse("A")

  /** The embed, in its Scala red box: a thick red frame, a hard red shadow like the site's
    * stickers, a small red tab naming it. `tilt` turns it a touch, like the hero's code block.
    */
  def redBox(tilt: Boolean = false, label: String = "Watch the intro"): Html =
    Tags.figure(
      Attrs.cls := (if (tilt) "video-box video-box-tilt" else "video-box"),
      Tags.figcaption(
        Attrs.cls := "video-tab",
        Tags.span(Attrs.cls := "video-play", Attrs.attr("aria-hidden") := "true", "▶"),
        label
      ),
      div(
        Attrs.cls := "video-frame",
        Iframe(
          Attrs.src             := s"https://www.youtube-nocookie.com/embed/$YouTubeId",
          Attrs.attr("title")   := "Eezo, a Scala 3 web framework: the intro",
          Attrs.attr("loading") := "lazy",
          Attrs.attr("allow")   := "accelerometer; encrypted-media; picture-in-picture; web-share",
          Attrs.attr("allowfullscreen") := "",
          Attrs.attr("referrerpolicy")  := "strict-origin-when-cross-origin"
        )
      )
    )

  /** The floating bottom bar: previous, the variant and its name, next. Arrow keys cycle too
    * (keys.js is the site's own; this one line of script is the prototype's). Development only.
    */
  def switcher(current: String): Option[Html] =
    Option.when(sys.props.contains("site.root")) {
      val keys = Variants.map(_._1)
      val at   = keys.indexOf(current)
      val prev = keys((at - 1 + keys.size) % keys.size)
      val next = keys((at + 1) % keys.size)
      val name = Variants.find(_._1 == current).map(_._2).getOrElse("")
      div(
        Attrs.cls                := "proto-bar",
        Attrs.attr("aria-label") := "Prototype variant switcher",
        a(
          Attrs.href               := s"/?variant=$prev",
          Attrs.id                 := "proto-prev",
          Attrs.attr("aria-label") := "Previous variant",
          "←"
        ),
        Tags.span(Attrs.cls := "proto-label", strong(current), s" ($name)"),
        a(
          Attrs.href               := s"/?variant=$next",
          Attrs.id                 := "proto-next",
          Attrs.attr("aria-label") := "Next variant",
          "→"
        ),
        script(
          Html.raw(
            """document.addEventListener("keydown", function (e) {
              |  var t = e.target, tag = t && t.tagName;
              |  if (tag === "INPUT" || tag === "TEXTAREA" || (t && t.isContentEditable)) return;
              |  if (e.key === "ArrowLeft") document.getElementById("proto-prev").click();
              |  if (e.key === "ArrowRight") document.getElementById("proto-next").click();
              |});""".stripMargin
          )
        )
      )
    }
}
