import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.http.{Eezo, RouteTable}

/** The application owns its entry point, and names the generated table in it.
  *
  * `Routes.table()` is a `def` rather than a `val` because it mints the store the derived routes
  * write to. Nothing in this file, and nothing in the model, names that store: it is a throwaway
  * standing in for a query runtime that is still being built.
  *
  * `extends EezoApp` is what makes `sbt "run routes"` and the drift commands work with no plugin
  * involved: `main` dispatches, and anything that is not a command — `sbt run` — is `boot`. This
  * app has no database model yet, so `schema` stays at its `Schema.empty` default and the drift
  * commands answer "in sync".
  */
object Main extends EezoApp {

  override def routes: RouteTable = Routes.table()

  def boot(args: Array[String]): Unit = {
    val _ = args
    Eezo.run(port = 8080, routes = routes, dev = true)
  }
}
