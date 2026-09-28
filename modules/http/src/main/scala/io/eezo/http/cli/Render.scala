package io.eezo.http.cli

import io.eezo.http.{Orphan, RouteReport}

/** The text front end's rendering: result values in, `String` out, printing left to the caller.
  *
  * A separate object from [[Commands]] because it is one front end among several ([[RenderJson]] is
  * the same shape over the same values), and because layer 1's rule is that nothing in `Commands`
  * decides what a user sees.
  */
object Render {

  /** Boot's sentences, not a terminal's shorter cut of them: a warning read here and a warning read
    * in the boot log are one warning, and a user who meets both should recognise it. The mark is
    * the only thing this front end adds, since a log line already carries its level.
    */
  def routes(r: RouteListing): String = {
    val orphans  = r.orphans.map(o => Orphan(o.page, o.pageRoute, o.target, o.targetRoute))
    val warnings = RouteReport.warnings(r.overridden, r.shadowed, orphans).map(w => s"⚠ $w")
    (warnings :+ RouteReport.listing(r.routes)).mkString("\n")
  }
}
