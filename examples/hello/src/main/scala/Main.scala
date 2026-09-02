import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.http.RouteTable

/** The whole entry point: name the generated table, and `EezoApp` does the rest.
  *
  * `Routes.table()` is an ordinary call: eezo does not find the table by reflection, which is what
  * leaves room for a route transformation such as `Route.under("/admin")` and makes a missing
  * plugin a compile error here rather than a runtime message.
  *
  * `main` is inherited and dispatches: `sbt run` serves on port 8080, `sbt "run dev"` (or
  * `sbt eezoDev`) adds the drift check and the route listing, `sbt "run routes"` prints the table.
  * Override `def boot` for anything beyond serving [[routes]].
  */
object Main extends EezoApp {
  override def routes: RouteTable = Routes.table()
}
