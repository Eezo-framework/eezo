import io.eezo.generated.Routes
import io.eezo.http.Eezo

/** The application owns its entry point, and names the generated table in it.
  *
  * `Routes.table()` is a `def` rather than a `val` because it mints the store the derived routes
  * write to. Nothing in this file, and nothing in the model, names that store: it is a throwaway
  * standing in for a query runtime that is still being built.
  */
@main def main(): Unit = Eezo.run(port = 8080, routes = Routes.table(), dev = true)
