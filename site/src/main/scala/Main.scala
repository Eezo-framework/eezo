import io.eezo.generated.Routes
import io.eezo.http.RouteTable
import io.eezo.live.LiveApp

/** The documentation site is an application on the http edge alone: five handwritten routes under
  * `app/`, no model, no database. `Routes.table()` is what the sbt plugin generated from the file
  * layout. `LiveApp` rather than `HttpApp`, because the navigation drawer and the front page's demo
  * are live components: it adds the socket and the client script under `/eezo`, and nothing else.
  */
object Main extends LiveApp {
  override def routes: RouteTable = Routes.table()
}
