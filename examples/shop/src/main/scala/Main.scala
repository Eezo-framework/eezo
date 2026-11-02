import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.config.Config
import io.eezo.http.{Layout, RouteTable}

import config.{Configured, Settings}

object Main extends EezoApp with Configured {

  /** SPIKE: the one value that names the application's class. The compiler checks
    * `application.conf` against it. No type ascription: the inferred type carries the file's
    * digest, which is what makes an edit to the file alone recompile this object.
    */
  val settings = Config.root[Settings]

  override val config = settings

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
  override def layout: Layout     = views.Layout

  /** The shop keeps its tables in a Postgres schema of its own, so it shares the dev database with
    * the other examples without their drift bleeding into each other. Naming it is enough: eezo
    * creates the schema and sets search_path to it on every pooled connection, and the drift
    * commands read the same name.
    */
  override def databaseSchema: String = "shop"
}


