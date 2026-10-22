import io.eezo.EezoApp
import io.eezo.generated.Routes
import io.eezo.db.Schema
import io.eezo.http.RouteTable

/** The entry point: name the schema and the generated route table, and `EezoApp` does the rest:
  * `sbt run` serves, `sbt "run dev"` adds the drift check,
  * `sbt "run db status|sync|freeze|migrate"` are the schema commands. See README.md for the guided
  * tour.
  */
object Main extends EezoApp {

  override def schema: Schema     = AppSchema
  override def routes: RouteTable = Routes.table()

  /** 8090 rather than the 8080 default, purely so the tour does not collide with whatever else a
    * dev machine runs on 8080.
    */
  override def port: Int = 8090

  /** This app keeps its tables in a Postgres schema of its own, so it can share the dev database
    * with other examples without their drift bleeding into each other. Naming it is enough: eezo
    * creates the schema and sets search_path to it on **every** pooled connection, because
    * search_path is per connection, and the drift commands read the same name.
    */
  override def databaseSchema: String = "todo"
}
