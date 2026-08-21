import eezo.generated.Routes
import eezo.http.Eezo

/** The application owns its entry point, and names the generated table in it.
  *
  * `Routes.table` is an ordinary value: eezo does not find it by reflection, which is what leaves
  * room for a route transformation such as `under("/admin")` and makes a missing plugin a compile
  * error here rather than a runtime message.
  */
@main def main(): Unit = Eezo.run(port = 8080, routes = Routes.table, dev = true)
