import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.http.Provenance
import io.eezo.http.Route
import io.eezo.http.RouteTable

/** The whole entry point: name the generated table, and `EezoApp` does the rest.
  *
  * `Routes.table()` is a `def` rather than a `val` because it mints the store the derived routes
  * write to. Nothing in this file, and nothing in the model, names that store: it is a throwaway
  * standing in for a query runtime that is still being built.
  *
  * `main` is inherited and dispatches: `sbt run` serves on port 8080, `sbt "run dev"` (or
  * `sbt eezoDev`) adds the drift check and the route listing, and the drift commands — `status`,
  * `sync`, `freeze`, `migrate` — run against the schema this app names (none yet; see
  * `Schema.empty`'s default).
  *
  * The table is a value rather than something the server finds by reflection, which is what leaves
  * room for the lines in `routes` below. They split it on provenance and mount one half: the seven
  * routes derived from `models/Post.scala` move under `/admin`, and the handwritten `GET /` stays
  * where it was written. So the blog root answers at http://localhost:8080 and the posts live at
  * `/admin/posts`.
  *
  * That split is the ordinary shape of an application: a page anyone may read at the root, and the
  * screens that edit the data behind a prefix, which is the one place a deployment puts its
  * authentication or its firewall rule. Mounting only the derived half is what lets the public page
  * keep the address it was written for.
  *
  * A mount moves the pages **and** the URLs those pages emit, so nothing under `app/` or in the
  * derived resource has to know it is mounted; the derived pages link to each other in ignorance
  * and come out under `/admin`. What a page outside the mount has to say to point into it is in
  * `app/Index.scala`.
  *
  * The handwritten routes are concatenated first, as the generated table already ordered them:
  * `RouteTable` matches in `Seq` order, and a handwritten route beats a derived one on the same
  * method and path, so preserving that order preserves both rules.
  */
object Main extends EezoApp {

  override def routes: RouteTable = {
    val (derived, handwritten) =
      Routes.table().routes.partition(_.provenance == Provenance.Derived)
    RouteTable(handwritten ++ Route.under("/admin")(derived))
  }
}
