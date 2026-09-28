package io.eezo.http

/** The one wording of what eezo says about a route table, read by boot and by the `routes` command.
  *
  * Both used to write their own sentences, and the command's were shorter cuts that dropped the
  * advice, so a user saw two different warnings for one defect depending on where they looked. The
  * boot wording won because it says how to fix what it names. It lives here, beside the facts it
  * words, rather than in `cli`, because `cli` is a front end over `http` and the server must never
  * depend on one of its front ends. Each front end keeps only its own framing: the log carries a
  * level, the terminal a mark.
  *
  * The warnings for a table come overridden, then shadowed, then orphaned, the order boot has
  * always logged them in, so the command lists them the way the log does.
  */
private[eezo] object RouteReport {

  def warnings(table: RouteTable): Seq[String] =
    warnings(table.overridden, table.shadowed, Resource.orphaned(table))

  /** The `routes` command holds the three groups already computed, as a result value it must not
    * reshape, so it hands them over rather than a table.
    */
  def warnings(
      overriddenRoutes: Seq[Route],
      shadowedPairs: Seq[(Route, Route)],
      orphans: Seq[Orphan]
  ): Seq[String] =
    overriddenRoutes.map(overridden) ++ shadowedPairs.map(shadowed) ++ orphans.map(orphaned)

  def overridden(route: Route): String =
    s"${route.describe} is written by hand and also derived; the handwritten route is served and " +
      "the derived one is not mounted."

  def shadowed(earlier: Route, later: Route): String =
    s"${earlier.describe} shadows ${later.describe}, which can never match. Routes are tried in " +
      "table order; move the narrower route first."

  def orphaned(orphan: Orphan): String =
    s"${orphan.pageRoute} is mounted without ${orphan.targetRoute}: the page renders a form whose " +
      "submit target is not mounted, so submitting it answers 405. " +
      s"Mount ${orphan.target}, or subtract ${orphan.page} as well."

  /** A sentence rather than an empty heading when nothing is mounted, because a typo'd
    * `derives Resorce` mounts nothing in silence and this line is the only symptom a user sees.
    */
  def listing(routes: Seq[Route]): String =
    if (routes.isEmpty) "no routes mounted"
    else {
      val heading = if (routes.sizeIs == 1) "1 route:" else s"${routes.size} routes:"
      routes.map(route => s"  ${route.describe}").mkString(s"$heading\n", "\n", "")
    }
}
