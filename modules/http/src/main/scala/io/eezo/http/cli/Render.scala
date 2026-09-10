package io.eezo.http.cli

/** The text front end's rendering: result values in, `String` out, printing left to the caller.
  *
  * A separate object from [[Commands]] because it is one front end among several ([[RenderJson]] is
  * the same shape over the same values), and because layer 1's rule is that nothing in `Commands`
  * decides what a user sees.
  */
object Render {

  def routes(r: RouteListing): String = {
    val warnings =
      r.overridden.map(route =>
        s"⚠ ${route.describe} is written by hand and also derived; the derived one is not mounted"
      ) ++
        r.shadowed.map { case (earlier, later) =>
          s"⚠ ${earlier.describe} shadows ${later.describe}, which can never match"
        } ++
        r.orphans.map(o =>
          s"⚠ ${o.pageRoute} is mounted without ${o.targetRoute}: submitting the form answers 405"
        )

    val table =
      if (r.routes.isEmpty) "no routes mounted"
      else {
        val heading = if (r.routes.sizeIs == 1) "1 route:" else s"${r.routes.size} routes:"
        r.routes.map(route => s"  ${route.describe}").mkString(s"$heading\n", "\n", "")
      }

    (warnings :+ table).mkString("\n")
  }
}
