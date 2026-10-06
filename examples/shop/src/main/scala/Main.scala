import java.sql.Connection

import io.eezo.EezoApp
import io.eezo.db.Schema
import io.eezo.generated.Routes
import io.eezo.http.{Layout, RouteTable}

object Main extends EezoApp {

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()
  override def layout: Layout     = views.Layout

  /** The shop keeps its tables in a Postgres schema of its own, so it shares the dev database with
    * the other examples without their drift bleeding into each other. The init hook runs on every
    * pooled connection, because search_path is per connection, and `databaseSchema` points the
    * drift commands at the same name.
    */
  override def databaseSchema: String = "shop"

  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try {
      st.execute("""create schema if not exists "shop"""")
      st.execute("""set search_path to "shop"""")
    } finally st.close()
  }
}
