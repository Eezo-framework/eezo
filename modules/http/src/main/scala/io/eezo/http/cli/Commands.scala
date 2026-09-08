package io.eezo.http.cli

import io.eezo.http.{Resource, RouteTable}

/** The http edge's commands, as library functions. The two rules of layer 1 (design/cli.md §4)
  * apply: return values, never print; take what the command needs, not the process.
  *
  * One command today. The database edge's are in `io.eezo.db.cli.Commands`, in the same shape, and
  * the entry trait of each edge is the only front-end that prints.
  */
object Commands {

  /** The assembled table and everything boot warns about, as one value. Needs no server. */
  def routes(table: RouteTable): RouteListing =
    RouteListing(
      routes = table.routes,
      overridden = table.overridden,
      shadowed = table.shadowed,
      orphans = Resource
        .orphaned(table)
        .map(o => OrphanedPage(o.page, o.pageRoute, o.target, o.targetRoute))
    )
}
