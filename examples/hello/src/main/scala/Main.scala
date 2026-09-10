import io.eezo.generated.Routes
import io.eezo.http.HttpApp
import io.eezo.http.RouteTable

/** The whole entry point: name the generated table, and `HttpApp` does the rest.
  *
  * This application has the http edge only. It depends on `eezo-http`, not on the umbrella, so
  * `HttpApp` is the trait it extends and `routes` is the one thing it has to name. There is no
  * `schema` to name, because there is no database edge: `derives Table` does not compile here
  * (`io.eezo.db` is not on the classpath), and `sbt "run status"` is an unknown command.
  *
  * `Routes.table()` is an ordinary call: eezo does not find the table by reflection, which is what
  * leaves room for a route transformation such as `Route.under("/admin")` and makes a missing
  * plugin a compile error here rather than a runtime message.
  *
  * `main` is inherited and dispatches: `sbt run` serves on port 8080, `sbt "run dev"` (or
  * `sbt eezoDev`) adds the route listing and the reload client, `sbt "run routes"` prints the
  * table. Every knob of the server is an override beside `port`; override `def boot` for anything
  * beyond serving [[routes]].
  */
object Main extends HttpApp {
  override def routes: RouteTable = Routes.table()
}
