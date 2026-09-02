import java.sql.Connection

import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.db.Schema
import io.eezo.http.RouteTable

/** The entry point: name the schema and the generated route table, and `EezoApp` does the rest —
  * `sbt run` serves, `sbt "run dev"` adds the drift check, `sbt "run status|sync|freeze|migrate"`
  * are the schema commands. See README.md for the guided tour.
  */
object Main extends EezoApp {

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()

  /** 8090 rather than the 8080 default, purely so the tour does not collide with whatever else a
    * dev machine runs on 8080.
    */
  override def port: Int = 8090

  /** This app keeps its tables in a Postgres schema of its own, so it can share the dev database
    * with other examples without their drift bleeding into each other. Two halves, told once each:
    * the init hook runs on **every** pooled connection (search_path is per-connection), and
    * `databaseSchema` points the drift commands' catalog reads at the same name.
    */
  override def databaseSchema: String = "todo"

  override def databaseInit: Connection => Unit = { c =>
    val st = c.createStatement()
    try {
      st.execute("""create schema if not exists "todo"""")
      st.execute("""set search_path to "todo"""")
    } finally st.close()
  }
}
