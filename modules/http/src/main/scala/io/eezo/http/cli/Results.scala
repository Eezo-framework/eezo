package io.eezo.http.cli

import io.eezo.http.{Action, Route}

/** What the http edge's commands return. Layer 1's contract (design/cli.md §4): a command computes
  * one of these values and never prints, so a front-end can render it as text, as JSON, or as a
  * page. The database edge's results are its own, in `io.eezo.db.cli`, in the same shape.
  */

/** [[io.eezo.http.Resource]]'s `Orphan`, re-stated with cli-owned visibility.
  *
  * `Orphan` itself is `private[eezo]`, so a public result type cannot carry it; the fields are
  * copied instead of the type being widened, because "a derived form page whose submit target is
  * not mounted" is a warning eezo emits, not a vocabulary users build on.
  */
final case class OrphanedPage(page: Action, pageRoute: String, target: Action, targetRoute: String)

/** The assembled table, with everything boot warns about: the derived routes handwritten ones
  * replaced, the pairs where an earlier route swallows a later one, and the form pages whose submit
  * target is not mounted.
  */
final case class RouteListing(
    routes: Seq[Route],
    overridden: Seq[Route],
    shadowed: Seq[(Route, Route)],
    orphans: Seq[OrphanedPage]
)
