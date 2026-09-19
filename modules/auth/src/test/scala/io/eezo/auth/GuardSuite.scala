package io.eezo.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration, Instant}

import io.eezo.core.Id
import io.eezo.core.html.Html
import io.eezo.core.html.Url
import io.eezo.http.*

/** The password guard: who it says is behind a request, what it does to a route, and the three
  * routes it carries.
  *
  * Every user here is seeded with [[GuardSuite.cheap]], a precomputed strength 4 hash, rather than
  * through `Password.hash`. Hashing at the shipped strength 12 costs a quarter of a second, and a
  * suite that pays it per user is a suite somebody later marks as ignored.
  */
class GuardSuite extends munit.FunSuite {

  import GuardSuite.*
  import SignInFixtures.*

  case class User(id: Id[User], email: String, password: Password)

  private val ann  = User(Id.gen(), "ann@example.com", cheap)
  private val rows = Map(ann.id -> ann)

  /** The counterpart to the shared `stale`: a second inside the default lifetime, which makes it
    * the oldest sign in still valid.
    */
  private val almostStale: Instant = now.minus(Guard.DefaultLifetime).plusSeconds(1)

  private val find: Id[User] => Option[User] = id => rows.get(id)

  private val authenticate: (String, Password.Plain) => Option[Id[User]] =
    (email, plain) => rows.values.find(_.email == email).filter(_.password.verify(plain)).map(_.id)

  /** Names no lifetime, so every test that uses it runs on `Guard.apply`'s own default. */
  private def guard: Guard[User] = Guard[User](find, authenticate, clock = clock)

  /** A page behind the guard, so a test can tell "the handler ran" from "the wrapper answered". */
  private def page(method: Method = Method.GET, path: String = "/posts"): Route =
    Route.Http(method, PathPattern.parse(path), _ => Response.Ok(Html.text("the posts")))

  /** One guarded page and nothing else, for a test that only asks who gets through. */
  private def posts(g: Guard[User] = guard): RouteTable =
    RouteTable(Seq(g.required[Any].through(page())))

  /** What the guard answers `request` with, and whether the handler behind it ran. */
  private def watching(request: Request): (Response, Boolean) = {
    var ran     = false
    val watched = Route.Http(
      Method.GET,
      PathPattern.parse("/posts"),
      _ => { ran = true; Response.Ok(Html.text("the posts")) }
    )
    val response = RouteTable(Seq(guard.required[Any].through(watched))).dispatch(request)
    (response, ran)
  }

  /** The whole application a guard implies: its three routes, and one guarded page. */
  private def app(guarded: Guarded[Any], behind: Route = page()): RouteTable =
    RouteTable((guarded.carries :+ guarded.through(behind)).distinct)

  /** A browser whose session names `who`, signed in at `since`, which defaults to the reading every
    * guard here is fixed at, so a test with nothing to say about time reads as signed in just now.
    */
  private def signedIn(
      who: Id[User],
      method: Method = Method.GET,
      path: String = "/posts",
      since: Instant = now
  ) =
    browser(method, path).copy(session = signedInSession(who, since))

  /** A browser whose session names `who` and carries no stamp beside it: the shape every session
    * signed before the lifetime shipped has, and the one a forged escalation would most like.
    */
  private def stampless(who: Id[User], path: String = "/posts") =
    browser(Method.GET, path).copy(session = stamplessSession(who))

  /** A public page that offers the way out, which is where an application has to put one: the
    * screens behind a guard are the derived ones, and a derived page renders a plain envelope with
    * nothing to hang a control on.
    */
  private def frontPage(g: Guard[User]): Route =
    Route.Http(
      Method.GET,
      PathPattern.parse("/"),
      request => Response.Ok(Html.text("the blog") ++ g.logoutForm(request))
    )

  private def whom(session: Session): Option[String] = session.reserved(Guard.UserEntry)

  // ------------------------------------------------------------ the current user

  test("the current user is the row the session names") {
    assertEquals(guard.current(signedIn(ann.id)).email, "ann@example.com")
  }

  test("asking who is there when nobody is, is an error rather than a redirect") {
    intercept[IllegalStateException](guard.current(browser(Method.GET, "/posts")))
    intercept[IllegalStateException](guard.current(signedIn(Id.gen[User]())))
  }

  test("a handler on an unguarded route that asks who is there fails as a programming mistake") {
    // Not an HTTP failure, which is why it leaves the route table as a plain
    // `IllegalStateException` rather than as anything `modules/http` maps to a status: the
    // boundary answers 500 for it, and the half of that this module can see is the type and the
    // message. `BoundarySuite` pins the other half, the 500 itself.
    val open = Route.Http(
      Method.GET,
      PathPattern.parse("/posts"),
      request => Response.Ok(Html.text(guard.current(request).email))
    )
    val thrown =
      intercept[IllegalStateException](
        RouteTable(Seq(open)).dispatch(browser(Method.GET, "/posts"))
      )
    assert(thrown.getMessage.contains("this route is not guarded"), thrown.getMessage)
  }

  // ------------------------------------------------------------ refusing

  test("an anonymous browser is sent to the login page and the handler never runs") {
    val (response, ran) = watching(browser(Method.GET, "/posts"))
    assertEquals(response.status, 303)
    assertEquals(response.header("Location"), Some("/login"))
    assert(!ran, "the handler behind the guard ran anyway")
  }

  test("a session naming a row that is gone is refused, and loses that entry on the way out") {
    val response = posts().dispatch(signedIn(Id.gen[User]()))
    assertEquals(response.status, 303)
    val session = response.session.getOrElse(fail("no session on the refusal"))
    assertEquals(whom(session), None, "the stale entry survived the refusal")
  }

  test("a signed in browser reaches the page") {
    val response = posts().dispatch(signedIn(ann.id))
    assertEquals(response.status, 200)
  }

  test("a WebSocket route refuses rather than redirecting, having no page to send anyone to") {
    val ws = Route.Ws(PathPattern.parse("/live"), _ => fail("the endpoint was built"))
    guard.required[Any].through(ws) match {
      case Route.Ws(_, endpoint, _) =>
        intercept[Forbidden](endpoint(browser(Method.GET, "/live")))
      case other => fail(s"a Ws route came back as $other")
    }
  }

  // ------------------------------------------------------------ how long a sign in lasts

  test("a sign in younger than the lifetime reaches the guarded handler") {
    val (response, ran) = watching(signedIn(ann.id, since = almostStale))
    assertEquals(response.status, 200)
    assert(ran, "a sign in still inside the lifetime never reached the handler")
    // A guarded read that ever rewrote the stamp would turn the absolute lifetime into a sliding
    // one, and every page merely reading the session would start costing a `Set-Cookie`.
    assertEquals(
      response.session.flatMap(_.reserved(Guard.StampEntry)),
      Some(Guard.stamped(almostStale)),
      "a guarded request that let the handler run touched the stamp on the way through"
    )
  }

  test("a stamp ahead of the clock is honoured, not refused as though it were stale") {
    assertEquals(posts().dispatch(signedIn(ann.id, since = now.plusSeconds(60))).status, 200)
  }

  test("a sign in older than the lifetime is refused, and the handler never runs") {
    val (response, ran) = watching(signedIn(ann.id, since = stale))
    assertEquals(response.status, 303)
    assertEquals(response.header("Location"), Some("/login"))
    assert(!ran, "an expired sign in reached the handler behind the guard")
  }

  test("a guard naming no lifetime lasts exactly a fortnight; a millisecond older does not count") {
    // `guard` names no lifetime and the fortnight is spelled as a literal, so a change to
    // `Guard.apply`'s real default shows up here rather than moving with the constant.
    val t = posts()
    assertEquals(t.dispatch(signedIn(ann.id, since = now.minus(Duration.ofDays(14)))).status, 200)
    assertEquals(
      t.dispatch(signedIn(ann.id, since = now.minus(Duration.ofDays(14)).minusMillis(1))).status,
      303
    )
  }

  test("the lifetime is the one the application named, not the default") {
    val t = posts(Guard[User](find, authenticate, lifetime = Duration.ofMinutes(30), clock = clock))
    assertEquals(t.dispatch(signedIn(ann.id, since = now.minusSeconds(29 * 60))).status, 200)
    assertEquals(t.dispatch(signedIn(ann.id, since = now.minusSeconds(31 * 60))).status, 303)
  }

  test("an expired sign in is remembered the way an anonymous one is, path and query together") {
    val g       = guard
    val t       = app(g.required[Any])
    val refused = t.dispatch(
      signedIn(ann.id, since = stale).copy(query = Map("page" -> Seq("3")))
    )
    assertEquals(refused.status, 303)
    val marked = refused.session.getOrElse(fail("the refusal named no session"))
    assertEquals(marked.reserved(Guard.ReturnEntry), Some("/posts?page=3"))
  }

  test("a refusal takes the stamp out along with the user, leaving nothing stale behind") {
    val t = posts()

    val lapsed = t
      .dispatch(signedIn(ann.id, since = stale))
      .session
      .getOrElse(fail("the refusal named no session"))
    assertEquals(whom(lapsed), None, "the expired user entry survived the refusal")
    assertEquals(lapsed.reserved(Guard.StampEntry), None, "the expired stamp survived the refusal")

    val gone = t
      .dispatch(signedIn(Id.gen[User]()))
      .session
      .getOrElse(fail("the refusal named no session"))
    assertEquals(whom(gone), None)
    assertEquals(gone.reserved(Guard.StampEntry), None, "the stamp of a vanished user survived")
  }

  test("the current user of a request whose sign in has expired is an error, not a stale row") {
    intercept[IllegalStateException](guard.current(signedIn(ann.id, since = stale)))
  }

  test("a session naming nobody is anonymous whatever its stamp says, and never an error") {
    val t = posts()
    List(Instant.EPOCH, stale, almostStale, now, now.plusSeconds(60)).foreach { since =>
      val orphan = browser(Method.GET, "/posts").copy(
        session = Session.empty.withReserved(Guard.StampEntry, Guard.stamped(since))
      )
      val refused = t.dispatch(orphan)
      assertEquals(refused.status, 303, since.toString)
      assertEquals(refused.header("Location"), Some("/login"), since.toString)
      intercept[IllegalStateException](guard.current(orphan))
    }
  }

  test("a session naming no user is untouched by age: its entries and its token come back whole") {
    // What an anonymous browser really carries: an entry the application put there, a flash, and
    // the token dispatch minted into it. No `_user` and no stamp, so none of it is a sign in, and
    // that is the point: the lifetime is a property of the sign in, not of the session, so one
    // holding only these is worth nothing to a thief and is never worth expiring.
    //
    // The flash is a pending one, because the delivered map a request really arrives with is
    // `modules/http`'s own to build and is consumed by the response that follows either way. What
    // it pins here is the thing the guard decides, that it amends the session it was handed rather
    // than building a new one.
    val token     = Csrf.Token.gen()
    val anonymous =
      Csrf.carrying(Session.empty, token).set("cart", "two books").flash("notice", "saved")
    val visiting = browser(Method.GET, "/").copy(session = anonymous)
    val going    = browser(Method.GET, "/posts").copy(session = anonymous)

    // The second guard reads a clock ten years past every stamp this suite writes, so an age check
    // that ever started discarding sessions naming nobody would have fired by then.
    val ageless =
      Guard[User](find, authenticate, clock = Clock.offset(clock, Duration.ofDays(3650)))

    List("on the hour" -> guard, "ten years on" -> ageless).foreach { case (when, g) =>
      val seen = RouteTable(Seq(frontPage(g))).dispatch(visiting)
      assertEquals(htmlOf(seen), "the blog", s"an anonymous browser was offered a way out, $when")
      assertEquals(seen.session, Some(anonymous), s"an open page rewrote the session, $when")
      intercept[IllegalStateException](g.current(visiting))

      val refused = posts(g).dispatch(going)
      assertEquals(refused.status, 303, when)
      assertEquals(refused.header("Location"), Some("/login"), when)
      assertEquals(
        refused.session,
        Some(anonymous.withReserved(Guard.ReturnEntry, "/posts")),
        s"the refusal did more than remember where the browser was going, $when"
      )
    }
  }

  test("a stamp that will not read as a moment is no sign in rather than an error page") {
    val t = posts()
    List("", " ", "yesterday", "12.5", "99999999999999999999", "  17  ", "17ms").foreach {
      corrupt =>
        val request = browser(Method.GET, "/posts").copy(
          session = Session.empty
            .withReserved(Guard.UserEntry, ann.id.show)
            .withReserved(Guard.StampEntry, corrupt)
        )
        val refused = t.dispatch(request)
        assertEquals(refused.status, 303, corrupt)
        assertEquals(refused.header("Location"), Some("/login"), corrupt)
        intercept[IllegalStateException](guard.current(request))
    }
  }

  test("signing in stamps the session with the clock's own reading, beside the user and a token") {
    val g     = guard
    val t     = app(g.required[Any])
    val token = tokenOf(t.dispatch(browser(Method.GET, "/login")))

    val ok = t.dispatch(submits("/login", token, "email" -> ann.email, "password" -> "secret"))
    assertEquals(ok.status, 303)
    val after = ok.session.getOrElse(fail("login named no session"))
    assertEquals(whom(after), Some(ann.id.show))
    assertEquals(after.reserved(Guard.StampEntry), Some(Guard.stamped(now)))
    assertNotEquals(
      Csrf.read(after).map(_.value),
      Some(token.value),
      "the stamped session kept the token minted before eezo knew who this was"
    )
  }

  test("signing in again through an expired session starts the lifetime over") {
    val g = guard
    val t = app(g.required[Any])

    val refused = t.dispatch(signedIn(ann.id, since = stale))
    val marked  = refused.session.getOrElse(fail("the refusal named no session"))
    val token   = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.status, 303)
    val after = ok.session.getOrElse(fail("login named no session"))
    assertEquals(after.reserved(Guard.StampEntry), Some(Guard.stamped(now)))
    assertEquals(t.dispatch(browser(Method.GET, "/posts").copy(session = after)).status, 200)
  }

  test("a guarded WebSocket refuses an expired sign in, having no page to send anyone to") {
    val ws = Route.Ws(PathPattern.parse("/live"), _ => fail("the endpoint was built"))
    guard.required[Any].through(ws) match {
      case Route.Ws(_, endpoint, _) =>
        intercept[Forbidden](endpoint(signedIn(ann.id, since = stale, path = "/live")))
      case other => fail(s"a Ws route came back as $other")
    }
  }

  test("a session naming a user with no stamp beside it is anonymous everywhere") {
    val (response, ran) = watching(stampless(ann.id))
    assertEquals(response.status, 303)
    assertEquals(response.header("Location"), Some("/login"))
    assert(!ran, "an unstamped session reached the handler behind the guard")

    intercept[IllegalStateException](guard.current(stampless(ann.id)))
    assertEquals(
      htmlOf(RouteTable(Seq(frontPage(guard))).dispatch(stampless(ann.id, path = "/"))),
      "the blog",
      "an unstamped session was offered a way out"
    )
  }

  // ------------------------------------------------------------ what it carries

  test("a guard carries a GET login, a POST login and a POST logout, all derived") {
    val carried = guard.required[Any].carries
    assertEquals(carried.map(_.describe).toSet, Set("GET /login", "POST /login", "POST /logout"))
    assert(carried.forall(_.provenance == Provenance.Derived))
  }

  test("every declaration one guard makes carries the same instances, so distinct takes one") {
    val g   = guard
    val one = g.required[Any].carries
    val two = g.only[Any](Action.Create).carries
    assertEquals(one.size, 3)
    // Identity, not equality: two equal but separate instances would mount the login page twice,
    // and `RouteTable`'s duplicate check throws on that rather than failing a unit test.
    one.zip(two).foreach { case (a, b) =>
      assert(a eq b, s"${a.describe} is not the same instance")
    }
    // What a route table does with two guarded models: concatenate, then distinct. Without the
    // shared instances this throws, because `RouteTable` refuses the same method and path twice.
    assertEquals(RouteTable((one ++ two).distinct).httpRoutes.size, 3)
  }

  test("a declaration names the actions it was asked for, and no more") {
    val g = guard
    assertEquals(g.required[Any].actions, Action.values.toSet)
    assertEquals(
      g.only[Any](Action.Create, Action.Update).actions,
      Set(Action.Create, Action.Update)
    )
    assertEquals(g.except[Any](Action.Index).actions, Action.values.toSet - Action.Index)
  }

  test("the login page renders an email box and a password box") {
    val g    = guard
    val html = htmlOf(app(g.required[Any]).dispatch(browser(Method.GET, "/login")))
    assert(html.contains("""name="email""""), html)
    assert(html.contains("""type="password""""), html)
    assert(html.contains("""name="password""""), html)
    assert(html.contains(Csrf.Field), html)
  }

  // ------------------------------------------------------------ the round trip

  test("login, then the guarded page; a wrong password never reaches it") {
    val g = guard
    val t = app(g.required[Any])

    val form = t.dispatch(browser(Method.GET, "/login"))
    assertEquals(form.status, 200)
    val token = tokenOf(form)
    // The page's own printed value, not the session read a second way: a login form that embeds a
    // token the session does not hold would still pass every other assertion here, because both
    // `submits` and `tokenOf` reach past the page into the session for their own copy of it.
    assertEquals(
      tokenValueInPage(form),
      token.value,
      "the login page embeds a token other than the one its own session actually needs"
    )

    val wrong = t.dispatch(submits("/login", token, "email" -> ann.email, "password" -> "not it"))
    assertEquals(wrong.status, 422, "a wrong password was accepted")
    assertEquals(wrong.session.flatMap(whom), None, "a wrong password named somebody")

    val ok = t.dispatch(submits("/login", token, "email" -> ann.email, "password" -> "secret"))
    assertEquals(ok.status, 303)
    val after = ok.session.getOrElse(fail("login named no session"))
    assertEquals(whom(after), Some(ann.id.show))

    val served = t.dispatch(browser(Method.GET, "/posts").copy(session = after))
    assertEquals(served.status, 200)
  }

  test("an unknown email is refused the same way a wrong password is") {
    val g  = guard
    val t  = app(g.required[Any])
    val ok = t.dispatch(
      submits("/login", Csrf.Token.gen(), "email" -> "nobody@example.com", "password" -> "secret")
    )
    assertEquals(ok.status, 422)
  }

  test("a refused login hands the email back, so only the password has to be retyped") {
    val g    = guard
    val t    = app(g.required[Any])
    val html = htmlOf(
      t.dispatch(submits("/login", Csrf.Token.gen(), "email" -> ann.email, "password" -> "not it"))
    )
    assert(html.contains(s"""name="email" value="${ann.email}""""), html)
  }

  test("a submission that does not decode at all still hands the email back") {
    val g = guard
    val t = app(g.required[Any])
    // No password field, so `Login` never decodes and `authenticate` is never reached. The typing
    // that did arrive comes back all the same.
    val html = htmlOf(t.dispatch(submits("/login", Csrf.Token.gen(), "email" -> ann.email)))
    assert(html.contains(s"""name="email" value="${ann.email}""""), html)
  }

  test("a refused login never prints the password it was given") {
    val g     = guard
    val t     = app(g.required[Any])
    val typed = "swordfish99"
    val html  = htmlOf(
      t.dispatch(submits("/login", Csrf.Token.gen(), "email" -> ann.email, "password" -> typed))
    )
    assert(!html.contains(typed), html)
    assert(html.contains("""name="password" value=""""), html)
  }

  test("an email with markup in it comes back escaped") {
    val g     = guard
    val t     = app(g.required[Any])
    val typed = """ann"<script>alert(1)</script>@example.com"""
    val html  = htmlOf(
      t.dispatch(submits("/login", Csrf.Token.gen(), "email" -> typed, "password" -> "not it"))
    )
    assert(!html.contains("<script>"), html)
    assert(html.contains("&lt;script&gt;"), html)
    assert(html.contains("""value="ann&quot;&lt;script&gt;"""), html)
  }

  test("the refused path comes back after login") {
    val g = guard
    val t = app(g.required[Any])

    val refused = t.dispatch(browser(Method.GET, "/posts"))
    val marked  = refused.session.getOrElse(fail("the refusal named no session"))
    val token   = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/posts"))
  }

  test("the query string comes back with the path, so a listing returns to the page it was on") {
    val g = guard
    val t = app(g.required[Any])

    val refused = t.dispatch(
      browser(Method.GET, "/posts").copy(query = Map("page" -> Seq("3"), "tag" -> Seq("scala")))
    )
    val marked = refused.session.getOrElse(fail("the refusal named no session"))
    val token  = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/posts?page=3&tag=scala"))
  }

  test("a query value that spells another host is a value, and travels back encoded") {
    val g = guard
    val t = app(g.required[Any])

    val refused = t.dispatch(
      browser(Method.GET, "/posts").copy(query = Map("next" -> Seq("//evil.example.com")))
    )
    val marked = refused.session.getOrElse(fail("the refusal named no session"))
    val token  = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/posts?next=%2F%2Fevil.example.com"))
  }

  test("a refusal remembers nothing when the address would not fit the session once encoded") {
    val g = guard
    val t = app(g.required[Any], page(path = "/posts/:id"))
    // 1017 commas make a path of exactly 1024 characters, the cap MaxReturn used to check the raw
    // address against. Each comma encodes to `%2C` in the value the session actually carries, so
    // the encoded address is far past what the cookie has room for.
    val long = "/posts/" + ("," * 1017)

    val refused = t.dispatch(browser(Method.GET, long))
    val marked  = refused.session.getOrElse(fail("the refusal named no session"))
    assertEquals(marked.reserved(Guard.ReturnEntry), None, "an oversized address was remembered")

    val token = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))
    val ok    = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/"))
  }

  test("a refused POST remembers nothing, because the browser comes back with a GET") {
    val g     = guard
    val t     = app(g.required[Any], page(Method.POST, "/posts"))
    val token = Csrf.Token.gen()

    val refused = t.dispatch(submits("/posts", token, "title" -> "hi"))
    val marked  = refused.session.getOrElse(fail("the refusal named no session"))
    assertEquals(marked.reserved(Guard.ReturnEntry), None)

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/"))
  }

  test("a refused POST leaves an earlier refused GET remembered, that being the page in view") {
    val g = guard
    val t = RouteTable(
      (g.required[Any].carries ++ Seq(
        g.required[Any].through(page()),
        g.required[Any].through(page(Method.POST, "/posts"))
      )).distinct
    )

    val seen  = t.dispatch(browser(Method.GET, "/posts").copy(query = Map("page" -> Seq("3"))))
    val first = seen.session.getOrElse(fail("the refusal named no session"))
    val token = Csrf.read(first).getOrElse(fail("the refusal kept no token"))

    val submitted =
      t.dispatch(submits("/posts", token, "title" -> "hi").copy(session = first))
    val marked = submitted.session.getOrElse(fail("the refusal named no session"))

    val ok = t.dispatch(
      submits("/login", token, "email" -> ann.email, "password" -> "secret").copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/posts?page=3"))
  }

  test("a refused path that is not relative is ignored, and login lands at the mount root") {
    val g = guard
    val t = app(g.required[Any])

    List(
      "https://evil.example.com/",
      "//evil.example.com/",
      "/\\evil.example.com",
      "\\\\evil.example.com",
      "evil.example.com",
      "/ok\nLocation: https://evil.example.com",
      "//evil.example.com/?next=/posts",
      "https://evil.example.com/?next=/posts",
      "/\\evil.example.com?page=3"
    ).foreach { hostile =>
      val token   = Csrf.Token.gen()
      val planted = Csrf.carrying(Session.empty.withReserved(Guard.ReturnEntry, hostile), token)
      val ok      = t.dispatch(
        submits("/login", token, "email" -> ann.email, "password" -> "secret")
          .copy(session = planted)
      )
      assertEquals(ok.status, 303, hostile)
      assertEquals(ok.header("Location"), Some("/"), hostile)
    }
  }

  test("login rotates the token, so nothing minted before eezo knew who this was survives") {
    val g      = guard
    val t      = app(g.required[Any])
    val before = tokenOf(t.dispatch(browser(Method.GET, "/login")))
    val ok     = t.dispatch(submits("/login", before, "email" -> ann.email, "password" -> "secret"))
    val session = ok.session.getOrElse(fail("login named no session"))
    assert(Csrf.read(session).isDefined, "the rebuilt session has no token of its own")
    assertNotEquals(Csrf.read(session).map(_.value), Some(before.value))
  }

  // ------------------------------------------------------------ logout

  test("logout empties the session, and the next guarded request is refused again") {
    val g     = guard
    val t     = app(g.required[Any])
    val token = Csrf.Token.gen()
    val out   = t.dispatch(
      submits("/logout", token).copy(
        session = Csrf.carrying(signedIn(ann.id).session, token)
      )
    )
    assertEquals(out.status, 303)
    val after = out.session.getOrElse(fail("logout named no session"))
    assert(after.isEmpty, "the session survived logout")
    assertEquals(whom(after), None)
    assertEquals(Csrf.read(after), None, "logout kept a token")

    val next = t.dispatch(browser(Method.GET, "/posts").copy(session = after))
    assertEquals(next.status, 303)
    assertEquals(next.header("Location"), Some("/login"))
    assert(next.session.flatMap(Csrf.read).isDefined, "the request after logout got no fresh token")
  }

  // ------------------------------------------------------------ the sign out form

  test("a signed in browser is shown a form posting to the logout route, carrying the token") {
    val g        = guard
    val response = RouteTable(Seq(frontPage(g))).dispatch(signedIn(ann.id, path = "/"))
    val page     = htmlOf(response)
    assert(page.contains("""action="/logout""""), page)
    assert(page.contains("""method="post""""), page)
    // The page's own printed value against the session's, for the reason the login test says: a
    // form embedding a token the session does not hold is refused at the logout it posts to.
    assertEquals(tokenValueInPage(response), tokenOf(response).value)
  }

  test("an anonymous visitor is shown no way out, and the rest of the page still renders") {
    val page = htmlOf(RouteTable(Seq(frontPage(guard))).dispatch(browser(Method.GET, "/")))
    assertEquals(page, "the blog")
  }

  test("the sign out form a mounted guard renders posts inside the mount") {
    val g       = guard
    val mounted = RouteTable(Route.under("/admin")(Seq(frontPage(g))))
    val page    = htmlOf(mounted.dispatch(signedIn(ann.id, path = "/admin")))
    assert(page.contains("""action="/admin/logout""""), page)
  }

  // ------------------------------------------------------------ CSRF comes first

  test("a forged POST to a guarded route is Forbidden, never redirected to login") {
    val g = guard
    val t = RouteTable(Seq(g.required[Any].through(page(Method.POST, "/posts"))))
    // A browser with a session and a token, submitting a body that does not return it.
    val forged = browser(Method.POST, "/posts", "title" -> "hi")
      .copy(session = Csrf.carrying(Session.empty, Csrf.Token.gen()))
    intercept[Forbidden](t.dispatch(forged))
  }

  // ------------------------------------------------------------ mounting

  test("the login page a mounted guard redirects to is inside the mount") {
    val g       = guard
    val mounted = RouteTable(
      Route.under("/admin")((g.required[Any].carries :+ g.required[Any].through(page())).distinct)
    )
    val refused = mounted.dispatch(browser(Method.GET, "/admin/posts"))
    assertEquals(refused.header("Location"), Some("/admin/login"))

    val form = mounted.dispatch(browser(Method.GET, "/admin/login"))
    assertEquals(form.status, 200)

    // The path the guard remembered is already the mounted one, so the redirect after login must
    // not take the prefix a second time.
    val marked = refused.session.getOrElse(fail("the refusal named no session"))
    val token  = Csrf.read(marked).getOrElse(fail("the refusal kept no token"))
    val ok     = mounted.dispatch(
      submits("/admin/login", token, "email" -> ann.email, "password" -> "secret")
        .copy(session = marked)
    )
    assertEquals(ok.header("Location"), Some("/admin/posts"))
  }

  test(
    "a sign in with nothing remembered lands at the guard's home, not the mount root, once mounted"
  ) {
    val g       = Guard[User](find, authenticate, home = Url.Mounted("/posts"))
    val mounted = RouteTable(
      Route.under("/admin")((g.required[Any].carries :+ g.required[Any].through(page())).distinct)
    )

    // No prior refusal here: this is signing in straight from `/admin/login`, which is the path
    // `CreateUser`'s printed instructions send the operator down. Without `home`, the guard falls
    // back to the mount root, `/admin`, a path nothing answers.
    val token = Csrf.Token.gen()
    val ok    = mounted.dispatch(
      submits("/admin/login", token, "email" -> ann.email, "password" -> "secret")
        .copy(session = Csrf.carrying(Session.empty, token))
    )
    assertEquals(ok.header("Location"), Some("/admin/posts"))
  }

  test("constructing a guard reads nothing, so a boot that never serves opens no connection") {
    var asked = 0
    val g     = Guard[User](
      find = id => { asked += 1; rows.get(id) },
      authenticate = (_, _) => { asked += 1; None }
    )
    val _ = g.required[Any]
    assertEquals(asked, 0)
  }
}

object GuardSuite {

  /** `secret`, hashed at strength 4. Precomputed, so no test but `PasswordSuite` pays for a hash.
    */
  val cheap: Password =
    Password.stored("$2b$04$6oCIgC4QzztRP0Q1ZTYV2.rK6diTucKojirfE2pCcbTXpbuFq6hju")

  def browser(method: Method, path: String, form: (String, String)*): Request = {
    val body = form
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers =
        if (form.isEmpty) Map.empty
        else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )
  }

  /** A submission that returns the token, the way a page eezo served makes a browser do. */
  def submits(path: String, token: Csrf.Token, form: (String, String)*): Request =
    browser(Method.POST, path, (form :+ (Csrf.Field -> token.value))*)
      .copy(session = Csrf.carrying(Session.empty, token))

  /** The session's own token, for a test to submit back.
    *
    * Fails rather than minting one, because a response that carries no token is not this helper's
    * business to paper over: a caller asking for the session's token when there is none has a wrong
    * assumption about the response, and inventing a fresh value would make that assumption look
    * right instead of failing the test that held it.
    */
  def tokenOf(response: Response): Csrf.Token =
    response.session
      .flatMap(Csrf.read)
      .getOrElse(throw new NoSuchElementException("the response carries no CSRF token"))

  /** The page a response served, as the browser would receive it. */
  def htmlOf(response: Response): String = response.body match {
    case Body.Html(node) => node.render
    case other           => throw new NoSuchElementException(s"expected an html body, got $other")
  }

  /** The token value the rendered page itself prints, read out of the hidden field's `value`
    * attribute rather than out of the session, so a test can tell "the page embeds the right token"
    * from "the session happens to hold one".
    */
  def tokenValueInPage(response: Response): String = {
    val html    = htmlOf(response)
    val pattern = s"""name="${Csrf.Field}"\\s+value="([^"]*)"""".r
    pattern
      .findFirstMatchIn(html)
      .map(_.group(1))
      .getOrElse(
        throw new NoSuchElementException(s"no ${Csrf.Field} field found in the page:\n$html")
      )
  }
}
