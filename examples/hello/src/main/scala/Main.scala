import io.eezo.generated.Routes
import io.eezo.http.Eezo

/** The application owns its entry point, and names the generated table in it.
  *
  * `Routes.table()` is an ordinary call: eezo does not find the table by reflection, which is what
  * leaves room for a route transformation such as `Route.under("/admin")` and makes a missing
  * plugin a compile error here rather than a runtime message. It is a `def` because a table with
  * derived routes in it mints the store those routes write to; this application has none, and the
  * call reads the same either way.
  */
@main def main(): Unit = Eezo.run(port = 8080, routes = Routes.table(), dev = true)
