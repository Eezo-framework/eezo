import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.http.{Eezo, RouteTable}

/** The application owns its entry point, and names the generated table in it.
  *
  * `Routes.table()` is an ordinary call: eezo does not find the table by reflection, which is what
  * leaves room for a route transformation such as `Route.under("/admin")` and makes a missing
  * plugin a compile error here rather than a runtime message.
  *
  * `extends EezoApp` gives this file its `main`: commands like `sbt "run routes"` dispatch to the
  * CLI, and everything else boots the server. No database is configured and none is needed —
  * nothing here transacts.
  */
object Main extends EezoApp {

  override def routes: RouteTable = Routes.table()

  def boot(args: Array[String]): Unit = {
    val _ = args
    Eezo.run(port = 8080, routes = routes, dev = true)
  }
}
