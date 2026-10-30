import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.{Layout, RouteTable}

import config.Settings

object Main extends EezoApp {

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
  override def layout: Layout     = views.Layout

  /** The shop keeps its tables in a Postgres schema of its own, so it shares the dev database with
    * the other examples without their drift bleeding into each other. Naming it is enough: eezo
    * creates the schema and sets search_path to it on every pooled connection, and the drift
    * commands read the same name.
    */
  override def databaseSchema: String = "shop"

  /** SPIKE: the prints bracket the table build, so the console shows whether the Config is read
    * while the table is built or later.
    */
  override def boot(): Unit = {
    println("[spike] boot: building the table")
    val table = routes
    println("[spike] boot: table built, binding")
    serve(table)
  }
}
