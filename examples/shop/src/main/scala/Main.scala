import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.{Layout, RouteTable}

import config.{Configured, Settings}

object Main extends EezoApp with Configured[Settings] {

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
  override def layout: Layout     = views.Layout

  /** SPIKE: 8080 is taken on this machine while the spike runs. */
  override def port: Int = 8089

  /** The shop keeps its tables in a Postgres schema of its own, so it shares the dev database with
    * the other examples without their drift bleeding into each other. Naming it is enough: eezo
    * creates the schema and sets search_path to it on every pooled connection, and the drift
    * commands read the same name.
    */
  override def databaseSchema: String = "shop"
}
