import io.eezo.EezoApp
import io.eezo.generated.Routes
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
  */
object Main extends EezoApp {
  override def routes: RouteTable = Routes.table()
}
