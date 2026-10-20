import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection

import io.eezo.auth.{Password, Token}
import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.{read, transact}
import io.eezo.http.Body
import io.eezo.http.Csrf
import io.eezo.http.Forbidden
import io.eezo.http.Method
import io.eezo.http.NotFound
import io.eezo.http.Request
import io.eezo.http.Response
import io.eezo.http.Session
import io.eezo.http.Unauthorized

import models.{Product, User}

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** The shop as a program calls it: products over JSON, reading open to anyone, writing only with an
  * API token a signed in user was issued.
  *
  * Driven through `Main.routes` and no server, as the blog's suites are, so the declaration in
  * `models/Product.scala` and the lookup in `models/User.scala` are the application's own and
  * nothing here assembles them. A refusal therefore arrives as the thrown `Unauthorized` rather
  * than as a 401: the boundary that turns one into the other, and adds `WWW-Authenticate`, is
  * private to the server.
  */
class ApiSuite extends munit.FunSuite {

  /** One table for the whole suite, because `Routes.table()` mints a store per call. */
  private val table = Main.routes

  // ---------------------------------------------------------------------------------------------
  // The database
  // ---------------------------------------------------------------------------------------------

  /** A container rather than the dev database, because the suite writes rows and a suite that
    * writes into what a developer is looking at is a suite nobody runs twice.
    */
  private lazy val postgres: PostgreSQLContainer[?] = {
    val started = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    started.start()
    sys.addShutdownHook(started.stop())
    started
  }

  /** The shop's own database settings pointed at the container. `Main`'s schema name and per
    * connection hook rather than copies, so the routes read the `shop` search path they read in
    * production.
    */
  private object ShopDb extends DbApp {
    override def schema: Schema                   = Main.schema
    override def databaseUrl: String              = postgres.getJdbcUrl
    override def databaseUser: String             = postgres.getUsername
    override def databasePassword: String         = postgres.getPassword
    override def databaseSchema: String           = Main.databaseSchema
    override def databaseInit: Connection => Unit = Main.databaseInit
    override def boot(): Unit                     = ()

    def around[A](body: => A): A = withDatabase(body)
  }

  /** The tables, made once by the committed migrations rather than straight from the models, so a
    * model change nobody froze fails here before it fails a deploy.
    */
  private lazy val synced: Unit = ShopDb.main(Array("db", "migrate", "--apply"))

  /** A test's body with a `Database` installed around it. `synced` is forced outside, because
    * `withDatabase` installs into one global holder and a nested install would uninstall the outer
    * one on its way out.
    */
  private def shop[A](body: => A): A = {
    synced
    ShopDb.around(body)
  }

  // ---------------------------------------------------------------------------------------------
  // A program
  // ---------------------------------------------------------------------------------------------

  private def call(
      method: Method,
      path: String,
      body: String = "",
      token: Option[String] = None
  ): Response =
    table.dispatch(
      Request(
        method = method,
        path = path,
        query = Map.empty,
        headers = token.fold(Map.empty)(plain => Map("Authorization" -> Seq(s"Bearer $plain"))),
        body = body.getBytes(StandardCharsets.UTF_8),
        pathParams = Map.empty
      )
    )

  private def json(response: Response): ujson.Value = response.body match {
    case Body.Json(text) => ujson.read(text)
    case other           => fail(s"expected JSON, got $other")
  }

  private def stock(name: String, price: Int): Product = {
    val product = Product(Id.gen(), name, price)
    transact(Table[Product].insert(product))
    product
  }

  /** A user holding a freshly issued API token, and the plain token a program would send. Issued
    * here by the same `Token.gen()` the settings page calls, so what is under test is the guard's
    * lookup and not the page.
    */
  private def issued(): (User, String) = {
    val (plain, hash) = Token.gen()
    (registered(Some(hash)), plain)
  }

  /** Every user in this suite has the same password, since what is tested is who holds which API
    * token and not who knows which password.
    */
  private val PlainPassword = "correct horse battery staple"

  /** Starts with no API token, so a token later found on the row can only have come through the
    * settings page under test.
    */
  private def signedUp(): User = registered(None)

  private def registered(token: Option[Token]): User = {
    val user = User(
      Id.gen(),
      s"${Id.gen[User]().show}@example.com",
      Password.hash(Password.Plain(PlainPassword)),
      token
    )
    transact(Table[User].insert(user))
    user
  }

  private def stored(user: User): Option[Token] =
    read(Table[User].findById(user.id)).flatMap(_.token)

  private val Lamp = """{"name":"Lamp","price":40}"""

  private def productNames: Set[String] = read(Table[Product].all()).map(_.name).toSet

  // ---------------------------------------------------------------------------------------------
  // A browser
  // ---------------------------------------------------------------------------------------------

  /** The hidden input `Csrf.hidden` renders. A browser outside the framework cannot open the
    * session's reserved entry, so it takes the token off the form it was served, as a real one
    * does.
    */
  private val HiddenToken = s"""name="${Csrf.Field}" value="([^"]+)"""".r

  /** Apart from `call`, because the settings page is a browser route: the session guard and the
    * CSRF check stand before its handler, and only a request carrying the cookie and the token off
    * the last page it was served gets past both.
    */
  private final class Browser {

    private var jar: Session  = Session.empty
    private var token: String = ""

    /** The login page, its submission, and then the settings page, because the token the login form
      * carried died with the session the sign in replaced.
      */
    def signIn(user: User): Browser = {
      get("/login")
      val landed = send(Method.POST, "/login", "email" -> user.email, "password" -> PlainPassword)
      assertEquals(landed.status, 303, s"${user.email} could not sign in")
      get("/settings/token")
      this
    }

    def get(path: String): Response = send(Method.GET, path)

    def send(method: Method, path: String, form: (String, String)*): Response = {
      val fields   = if (method.safe) form else form :+ (Csrf.Field -> token)
      val response = table.dispatch(
        Request(
          method = method,
          path = path,
          query = Map.empty,
          headers =
            if (fields.isEmpty) Map.empty
            else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
          body = encoded(fields).getBytes(StandardCharsets.UTF_8),
          pathParams = Map.empty,
          session = jar
        )
      )
      response.session.foreach(session => jar = session)
      markup(response).flatMap(HiddenToken.findFirstIn(_)).foreach { case HiddenToken(found) =>
        token = found
      }
      response
    }

    /** What another site can make this browser send: its cookie, and no CSRF token, because the
      * token is on the shop's pages and another site cannot read them.
      */
    def forge(method: Method, path: String): Unit =
      table.dispatch(Request(method, path, Map.empty, Map.empty, Array.empty, Map.empty, jar))

    /** Through the page rather than `Token.gen()`, because replacing the last API token is the
      * page's job, and the old one failing afterwards is what proves it did.
      */
    def issue(): String = {
      val reply = send(Method.POST, "/settings/token")
      assertEquals(reply.status, 200)
      plainOn(page(reply))
    }
  }

  /** Anchored on the `code` element, because the page a browser really gets is framed by the shop
    * layout, whose logout form carries a CSRF token of the same length and alphabet, and a looser
    * match would stop telling the two apart the moment the reply is read framed.
    */
  private val PlainToken = """<code>([A-Za-z0-9_-]{43})</code>""".r

  private def plainOn(page: String): String =
    PlainToken.findFirstMatchIn(page).map(_.group(1)).getOrElse(fail(s"no token on $page"))

  private def encoded(form: Seq[(String, String)]): String =
    form.map { case (name, value) => s"${escape(name)}=${escape(value)}" }.mkString("&")

  private def escape(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def markup(response: Response): Option[String] = response.body match {
    case Body.Html(node) => Some(node.render)
    case _               => None
  }

  private def page(response: Response): String =
    markup(response).getOrElse(fail(s"expected a page, got ${response.body}"))

  // ---------------------------------------------------------------------------------------------
  // The assertions
  // ---------------------------------------------------------------------------------------------

  test("anybody lists the products as JSON, each with its id, name and price") {
    shop {
      val mug = stock("Mug", 12)

      val listed = call(Method.GET, "/api/products")

      assertEquals(listed.status, 200)
      val found = json(listed).arr.find(_("id").str == mug.id.show)
      assertEquals(found.map(p => (p("name").str, p("price").num.toInt)), Some(("Mug", 12)))
    }
  }

  test("a program with no API token is refused, and nothing is created") {
    shop {
      val refused = intercept[Unauthorized](call(Method.POST, "/api/products", Lamp))

      assertEquals(refused.getMessage, "this API route needs an API token issued to a user")
      assert(!productNames.contains("Lamp"))
    }
  }

  test("a program with an API token creates a product the admin pages then list") {
    shop {
      val (_, plain) = issued()
      val body       = """{"name":"Kettle","price":35}"""

      val created = call(Method.POST, "/api/products", body, token = Some(plain))

      assertEquals(created.status, 201)
      val key = json(created)("id").str
      assertEquals(created.header("Location"), Some(s"/api/products/$key"))
      val admin = page(call(Method.GET, "/products"))
      assert(admin.contains("Kettle"), admin)
    }
  }

  test("an API token stops working once its user's token is taken away") {
    shop {
      val (user, plain) = issued()
      transact(Table[User].update(user.copy(token = None)))

      intercept[Unauthorized](
        call(Method.POST, "/api/products", """{"name":"Toaster","price":20}""", token = Some(plain))
      )
      assert(!productNames.contains("Toaster"))
    }
  }

  test("anybody reads the product pages, and the form pages still ask them to sign in") {
    shop {
      val jug = stock("Jug", 9)

      assertEquals(call(Method.GET, "/products").status, 200)
      assertEquals(call(Method.GET, s"/products/${jug.id.show}").status, 200)
      assertEquals(call(Method.GET, "/products/new").status, 303)
      assertEquals(call(Method.GET, s"/products/${jug.id.show}/edit").status, 303)
    }
  }

  test("orders are not exposed over JSON") {
    shop {
      intercept[NotFound](call(Method.GET, "/api/orders"))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Issuing an API token
  // ---------------------------------------------------------------------------------------------

  test("the settings page offers a signed in user a form that issues an API token") {
    shop {
      val page = markup(new Browser().signIn(signedUp()).get("/settings/token")).getOrElse("")

      assert(page.contains("""action="/settings/token""""), page)
      assert(HiddenToken.findFirstIn(page).isDefined, page)
    }
  }

  test("an anonymous browser asking for an API token is sent to sign in, and nothing is issued") {
    shop {
      val anonymous = new Browser()
      anonymous.get("/login")

      val refused = anonymous.send(Method.POST, "/settings/token")

      assertEquals(refused.status, 303)
      assert(refused.header("Location").exists(_.startsWith("/login")), refused.toString)
      assertEquals(markup(refused), None)
    }
  }

  test("a forged request for an API token, carrying the cookie and no CSRF token, is refused") {
    shop {
      val user = signedUp()

      intercept[Forbidden](new Browser().signIn(user).forge(Method.POST, "/settings/token"))
      assertEquals(stored(user), None)
    }
  }

  test("the reply shows the API token once, and the user row keeps only its hash") {
    shop {
      val user    = signedUp()
      val browser = new Browser().signIn(user)

      val reply = browser.send(Method.POST, "/settings/token")
      val shown = page(reply)
      val plain = plainOn(shown)

      assertEquals(shown.sliding(plain.length).count(_ == plain), 1)
      assertEquals(stored(user), Some(Token.hash(plain)))
      assertEquals(reply.header("Location"), None)
      assertEquals(
        call(Method.POST, "/api/products", """{"name":"Clock","price":25}""", Some(plain)).status,
        201
      )
    }
  }

  test("issuing an API token again replaces the last one, which stops working") {
    shop {
      val browser = new Browser().signIn(signedUp())
      val first   = browser.issue()
      val second  = browser.issue()

      intercept[Unauthorized](
        call(Method.POST, "/api/products", """{"name":"Fan","price":30}""", Some(first))
      )
      assertEquals(
        call(Method.POST, "/api/products", """{"name":"Fan","price":30}""", Some(second)).status,
        201
      )
    }
  }
}
