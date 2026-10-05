# Test an application

Drive the route table in a unit test, boot against a real Postgres, and test a live component as
plain functions.

## The table, without a server

`Routes.table()` is a value, and `dispatch` runs a request through it exactly as the server
would, CSRF minting and method override included:

```scala
import io.eezo.generated.Routes
import io.eezo.http.{Body, Method, NotFound, Request}
import munit.FunSuite

class RoutesSuite extends FunSuite {

  private def get(path: String) =
    Routes.table().dispatch(Request(Method.GET, path, Map.empty, Map.empty, Array.empty, Map.empty))

  test("the front page") {
    val response = get("/")
    assertEquals(response.status, 200)
    val html = response.body match {
      case Body.Html(h) => h.render
      case other        => fail(s"expected a page, got $other")
    }
    assert(html.contains("<h1>it works</h1>"))
  }

  test("an unknown page is a 404") {
    intercept[NotFound](get("/nothing"))
  }
}
```

Each `Routes.table()` call mints fresh stores, so a test that creates rows through a derived
resource with no `Table` behind it starts from an empty world every time.

A refusal is a thrown exception at this level; the problem page only appears once the boundary
is involved, which it isn't here.

## A POST with its token

An unsafe request has to carry the CSRF token the session holds. Get it from a GET first:

```scala
val page    = get("/books/new")
val session = page.session.getOrElse(fail("no session"))
val token   = """name="_csrf" value="([^"]+)"""".r.findFirstMatchIn(pageHtml).get.group(1)

val response = Routes.table().dispatch(
  Request(
    Method.POST,
    "/books",
    Map.empty,
    Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
    s"_csrf=$token&title=Dune&author=Herbert&pages=412".getBytes,
    Map.empty,
    session
  )
)
assertEquals(response.status, 303)
```

## Against a real database

For anything that touches rows, use a throwaway Postgres from testcontainers and a second
`DbApp` pointed at it:

```scala
private lazy val postgres = {
  val started = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
  started.start()
  sys.addShutdownHook(started.stop())
  started
}

private object TestDb extends DbApp {
  override def schema: Schema           = Main.schema
  override def databaseUrl: String      = postgres.getJdbcUrl
  override def databaseUser: String     = postgres.getUsername
  override def databasePassword: String = postgres.getPassword
  override def boot(): Unit             = ()
  def around[A](body: => A): A          = withDatabase(body)
}

TestDb.main(Array("sync", "--apply"))   // the tables, from the model
TestDb.around {
  transact { Table[User].insert(...) }
  val response = Main.routes.dispatch(...)
}
```

`withDatabase` installs the pool around the block, so `transact`, `read` and the derived
resources' `JdbcStore` all reach it. The blog's `PostOwnershipSuite` is the complete pattern,
two users and the 403 included. Add `"org.testcontainers" % "postgresql" % "1.21.3" % Test` to
the build; the suite needs Docker.

## A live component

`handle` and `render` are pure, so test them without a page:

```scala
val counter = new Counter
assertEquals(counter.handle(Event("inc"), 2), 3)
assert(counter.render(3).render.contains("<span>3</span>"))
```

For the socket itself, boot the application on a free port and connect a WebSocket client; the
framework's own suites use Jetty's.
