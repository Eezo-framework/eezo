package site

import io.eezo.generated.Routes
import io.eezo.http.RouteTable
import io.eezo.live.LiveApp

/** The table the site serves: its own routes with the framework's after them, composed the way
  * `HttpApp.serve` composes them. The suites dispatch through it rather than through the generated
  * table alone, so that a framework route the generated table does not hold, the asset route among
  * them, answers here as it does in production, and a collision between the two halves fails a test
  * before it fails a boot.
  */
object Served extends LiveApp {
  override def routes: RouteTable = Routes.table()

  lazy val table: RouteTable = routes ++ RouteTable(frameworkRoutes)
}
