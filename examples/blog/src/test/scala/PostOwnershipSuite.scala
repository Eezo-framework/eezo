import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID

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

import models.Post
import models.User

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** Ownership as the blog actually serves it: two people signed in, one route table, one Postgres.
  *
  * `OwnedResourceSuite` in `modules/http` already pins both readings of a refusal against stand in
  * models and an in-memory store. What it cannot reach is the wiring, and the wiring is three
  * separate pieces that only ever meet here: the generated route table's `Owned[A, ?]` arm, which
  * picks `JdbcStore.owned` for a model that carries a `Table`; the SQL that narrowing becomes; and
  * the real `given Owned[Post, User]` in `models/Post.scala`, whose `except(Index, Show)` is what
  * decides that a foreign row is refused rather than missing. A test against any one of them would
  * pass with the other two wrong.
  *
  * Driven through `Main.routes` and no server, the way `UserSuite` drives it, so the mount under
  * `/admin` and the declaration in the model are both the application's own and neither is
  * assembled here. What this suite adds to that one is a database, because every assertion below is
  * about a row.
  *
  * The refusals arrive as thrown `EezoException`s rather than as statuses, because `Boundary`, the
  * thing that turns one into the other, is `private[http]` and runs inside the server. [[status]]
  * is the same two lines `OwnedResourceSuite` writes for the same reason.
  */
class PostOwnershipSuite extends munit.FunSuite {

  /** The table the application serves, mount and all. A `val`: `Routes.table()` mints a store per
    * call, and two tables over the same rows would make "the browser" an ambiguous thing.
    */
  private val table = Main.routes

  private val Posts = "/admin/posts"

  /** One password for both users. What is being tested is who owns a row, not who guessed a secret,
    * and a second password would only be a second literal to keep in step.
    */
  private val Secret = "correct horse battery staple"

  private val AliceEmail = "alice@example.com"
  private val BobEmail   = "bob@example.com"

  /** What `Resource`'s refusal says when a row belongs to somebody else.
    *
    * Asserted on, and not only the 403 it arrives as, because a stale CSRF token is a 403 too: a
    * suite that read the number alone would pass just as happily on a browser whose token it had
    * forgotten to carry, which is the one mistake a test driving routes by hand can make.
    */
  private val Foreign = "this Post belongs to another user"

  /** The hidden input `Csrf.hidden` renders, read back off a page.
    *
    * The token lives in a reserved session entry that `Csrf.read` opens only to `io.eezo`, so a
    * browser outside the framework cannot be handed one; it takes the token off the form it was
    * served, which is exactly what a real browser submits and the only door an application has.
    */
  private val HiddenToken = s"""name="${Csrf.Field}" value="([^"]+)"""".r

  // ---------------------------------------------------------------------------------------------
  // The database
  // ---------------------------------------------------------------------------------------------

  /** One Postgres for this suite, on the `blog` schema the application names.
    *
    * A container rather than the dev database `README.md` runs on 5442: the assertions below insert
    * users and posts, and a suite that writes into whatever a developer is looking at is a suite
    * nobody runs twice.
    */
  private lazy val postgres: PostgreSQLContainer[?] = {
    val started = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    started.start()
    sys.addShutdownHook(started.stop())
    started
  }

  /** The blog's own database settings, pointed at the container.
    *
    * A second `DbApp` for the same reason `CreateUser` is one: `withDatabase` is `protected`, so
    * installing a `Database` is something only a subclass can ask for, and a test is a front end
    * like any other. The schema, the per connection hook and `AppSchema` are `Main`'s own values
    * rather than copies, so a change to where the blog keeps its tables moves this with it.
    */
  private object BlogDb extends DbApp {
    override def schema: Schema                   = Main.schema
    override def databaseUrl: String              = postgres.getJdbcUrl
    override def databaseUser: String             = postgres.getUsername
    override def databasePassword: String         = postgres.getPassword
    override def databaseSchema: String           = Main.databaseSchema
    override def databaseInit: Connection => Unit = Main.databaseInit
    override def boot(): Unit                     = ()

    /** The one thing this object exists to publish: a `Database` installed around a block, which is
      * what `sbt run` does around `boot` and what every handler below reaches through.
      */
    def around[A](body: => A): A = withDatabase(body)
  }

  /** The two users, made once, through the application's own tools: `run sync --apply` for the
    * tables, exactly as `README.md` says to before the first request, and `CreateUser.hashPassword`
    * for the hash, so the rows the guard verifies against are the rows the operator's tool would
    * have written.
    */
  private lazy val signedUp: (Id[User], Id[User]) = {
    BlogDb.main(Array("sync", "--apply"))
    val alice = Id.gen[User]()
    val bob   = Id.gen[User]()
    BlogDb.around {
      transact {
        val users = Table[User]
        users.insert(User(alice, AliceEmail, CreateUser.hashPassword(Secret)))
        users.insert(User(bob, BobEmail, CreateUser.hashPassword(Secret)))
      }
    }
    (alice, bob)
  }

  private def alice: Id[User] = signedUp._1
  private def bob: Id[User]   = signedUp._2

  /** A test's body with a `Database` installed around it, users and tables already there.
    *
    * `signedUp` is forced first and not inside, because `withDatabase` installs into one global
    * holder and a nested install would uninstall the outer one on its way out.
    */
  private def blog[A](body: => A): A = {
    signedUp
    BlogDb.around(body)
  }

  // ---------------------------------------------------------------------------------------------
  // The browser
  // ---------------------------------------------------------------------------------------------

  /** One browser: a cookie jar, the CSRF token off the last page it was served, and the verbs a
    * test drives it with.
    *
    * It is here rather than in `modules/testkit`, which is still empty, because what it stands on
    * is the doors an application has and nothing more: `Request.session` in, `Response.session`
    * out, and the token read off the rendered form. Anything the framework would add to make this
    * shorter would have to open `Csrf.read` or the session's reserved entries to the outside, which
    * is the one thing those are closed for.
    */
  private final class Browser(email: String) {

    private var jar: Session  = Session.empty
    private var token: String = ""

    /** Signs in through the blog's real login page, and comes back holding the rotated token.
      *
      * Three requests, all of them ones a person makes: the login page, which mints a token into a
      * session that had none; the submission, which `Guard.submitted` answers with a 303 and a
      * session rebuilt from empty around a *new* token; and one page inside the mount, because the
      * token that arrived on the login form died with the session that carried it.
      */
    def signIn(): Browser = {
      get("/admin/login")
      val landed = send(Method.POST, "/admin/login", "email" -> email, "password" -> Secret)
      assertEquals(landed.status, 303, s"$email could not sign in")
      get(s"$Posts/new")
      this
    }

    def get(path: String): Response = send(Method.GET, path)

    /** The cookie jar this browser is carrying, for a test that wants to hand it to something other
      * than a request this browser itself sends, such as `RouteTable.identify`.
      */
    def session: Session = jar

    /** A request the way this browser would send it: the session it holds, and, on an unsafe verb,
      * the token it was last served returned in the form encoded body.
      */
    def send(method: Method, path: String, form: (String, String)*): Response = {
      val fields  = if (method.safe) form else form :+ (Csrf.Field -> token)
      val request = Request(
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

      val response = table.dispatch(request)
      response.session.foreach(session => jar = session)
      markup(response).foreach(scrape)
      response
    }

    /** The status a request answers with, refusals included. `RouteTable.dispatch` throws them, and
      * the boundary that would render one is the server's.
      */
    def status(method: Method, path: String, form: (String, String)*): Int =
      try send(method, path, form*).status
      catch {
        case Forbidden(_) => 403
        case NotFound(_)  => 404
      }

    /** What a refusal said, for the 403s that have two possible authors. See [[Foreign]]. */
    def refusal(method: Method, path: String, form: (String, String)*): String =
      intercept[Forbidden](send(method, path, form*)).getMessage

    /** The page this browser was served, as markup, failing the test when it was handed anything
      * else.
      */
    def page(path: String): String =
      markup(get(path)).getOrElse(fail(s"$path did not answer with a page"))

    private def scrape(page: String): Unit =
      HiddenToken.findFirstMatchIn(page).foreach(found => token = found.group(1))
  }

  private def signedIn(email: String): Browser = new Browser(email).signIn()

  private def encoded(form: Seq[(String, String)]): String =
    form
      .map { case (name, value) => s"${escape(name)}=${escape(value)}" }
      .mkString("&")

  private def escape(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def markup(response: Response): Option[String] = response.body match {
    case Body.Html(node) => Some(node.render)
    case _               => None
  }

  // ---------------------------------------------------------------------------------------------
  // Writing a post
  // ---------------------------------------------------------------------------------------------

  /** `who` writes a post and comes back with its key, read out of the redirect the way a browser
    * follows one. `claiming` is what the submission *says* the author is, which is the field the
    * derived form has no input for and a hostile client therefore has to add by hand.
    */
  private def writes(who: Browser, title: String, claiming: Option[Id[User]] = None): Id[Post] = {
    val submission =
      Seq("title" -> title, "body" -> "one paragraph", "minutes" -> "3", "published" -> "on") ++
        claiming.map(user => "author" -> user.show)

    val created = who.send(Method.POST, Posts, submission*)
    assertEquals(created.status, 303, s"$title was not created")
    keyOf(created)
  }

  private def keyOf(response: Response): Id[Post] =
    Id(UUID.fromString(response.header("Location").getOrElse(fail("no Location")).split("/").last))

  private def member(key: Id[Post]): String = s"$Posts/${key.show}"

  /** The row as Postgres holds it, which is the only witness that matters for who owns what. */
  private def stored(key: Id[Post]): Option[Post] = read(Table[Post].findById(key))

  // ---------------------------------------------------------------------------------------------
  // The assertions
  // ---------------------------------------------------------------------------------------------

  test("a submission naming another author is stored as the author who sent it") {
    blog {
      val written = writes(signedIn(AliceEmail), "Hostile", claiming = Some(bob))
      assertEquals(stored(written).map(_.author), Some(alice))
    }
  }

  test("everybody signed in reads everybody's posts, on the index and on the page") {
    blog {
      val written = writes(signedIn(AliceEmail), "Shared")
      val reader  = signedIn(BobEmail)

      assert(reader.page(Posts).contains("Shared"))
      assertEquals(reader.get(member(written)).status, 200)
      assert(reader.page(member(written)).contains("one paragraph"))
    }
  }

  test("the page offers its author the edit link and the delete button, and offers nobody else") {
    blog {
      val author  = signedIn(AliceEmail)
      val written = writes(author, "Controls")

      val own = author.page(member(written))
      assert(own.contains(s"${member(written)}/edit"), own)
      assert(own.contains("Delete"), own)

      val foreign = signedIn(BobEmail).page(member(written))
      assert(foreign.contains("Controls"), foreign)
      assert(!foreign.contains(s"${member(written)}/edit"), foreign)
      assert(!foreign.contains("Delete"), foreign)
    }
  }

  test("editing, updating and deleting somebody else's post are refused and move nothing") {
    blog {
      val written = writes(signedIn(AliceEmail), "Untouched")
      val other   = signedIn(BobEmail)

      val hostile =
        Seq("title" -> "Taken", "body" -> "rewritten", "minutes" -> "1", "published" -> "on")

      assertEquals(other.refusal(Method.GET, s"${member(written)}/edit"), Foreign)
      assertEquals(other.refusal(Method.PUT, member(written), hostile*), Foreign)
      assertEquals(other.refusal(Method.DELETE, member(written)), Foreign)

      assertEquals(stored(written).map(row => (row.title, row.author)), Some(("Untouched", alice)))
    }
  }

  test("deleting a post that is not there is a 404, not the refusal a foreign row earns") {
    blog {
      val nowhere = Id.gen[Post]()
      assertEquals(signedIn(BobEmail).status(Method.DELETE, member(nowhere)), 404)
    }
  }

  test("the author's own update lands, and the author is still the author afterwards") {
    blog {
      val author  = signedIn(AliceEmail)
      val written = writes(author, "First draft")

      val saved = author.send(
        Method.PUT,
        member(written),
        "title"     -> "Second draft",
        "body"      -> "one paragraph",
        "minutes"   -> "4",
        "published" -> "on",
        "author"    -> bob.show
      )

      assertEquals(saved.status, 303)
      assertEquals(
        stored(written).map(row => (row.title, row.author)),
        Some(("Second draft", alice))
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The table's stamp
  // ---------------------------------------------------------------------------------------------

  /** `Main.routes` rebuilds `Routes.table()` into two mounts rather than serving it as generated,
    * which is documented in `Main.scala` as "the ordinary shape of an application". That rebuild
    * must keep the generated table's `identify`, the one thing a socket upgrade reads to learn who
    * is there: `RouteTable.dispatch` never applies it (a guarded page is named by the guard's own
    * wrapper instead), so the only way to observe it is to call it directly, exactly as `Eezo`'s
    * WebSocket creator does on a handshake.
    */
  test("the served table's identify still names a signed in browser, after the admin mount") {
    blog {
      val browser = signedIn(AliceEmail)

      val handshake = Request(
        method = Method.GET,
        path = "/admin/board",
        query = Map.empty,
        headers = Map.empty,
        body = Array.empty[Byte],
        pathParams = Map.empty,
        session = browser.session
      )

      assertEquals(Main.routes.identify(handshake).currentUser, Some(alice.show))
    }
  }
}
