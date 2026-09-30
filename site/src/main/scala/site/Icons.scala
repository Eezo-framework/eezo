package site

import io.eezo.core.html.*

/** The three icons, as inline SVG built with the DSL so they can sit inside a live tree like any
  * other element.
  */
object Icons {

  private val svg  = new Tag("svg")
  private val path = new Tag("path")

  private def icon(size: Int, extra: Attr*)(body: Html*): Html =
    svg(
      Attrs.attr("viewBox")        := "0 0 24 24",
      Attrs.width                  := size,
      Attrs.height                 := size,
      Attrs.attr("aria-hidden")    := "true",
      Attrs.attr("fill")           := "none",
      Attrs.attr("stroke")         := "currentColor",
      Attrs.attr("stroke-width")   := "2.2",
      Attrs.attr("stroke-linecap") := "round",
      extra,
      body
    )

  val menu: Html =
    icon(22, Attrs.attr("stroke-width") := "2.5")(
      path(Attrs.attr("d") := "M4 7h16M4 12h16M4 17h10")
    )

  val close: Html =
    icon(22, Attrs.attr("stroke-width") := "2.5")(path(Attrs.attr("d") := "M6 6l12 12M18 6L6 18"))

  val sun: Html =
    icon(20)(
      new Tag("circle")(
        Attrs.attr("cx") := "12",
        Attrs.attr("cy") := "12",
        Attrs.attr("r")  := "4.5"
      ),
      path(
        Attrs.attr("d") :=
          "M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"
      )
    )

  val moon: Html =
    icon(20, Attrs.attr("stroke-linejoin") := "round")(
      path(Attrs.attr("d") := "M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z")
    )
}
