package io.eezo.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration, Instant}

import io.eezo.core.Id
import io.eezo.core.html.{Attrs, Html, Url}
import io.eezo.core.html.Tags.*
import io.eezo.http.*

/** What a login form decodes to.
  *
  * Keyless on purpose, which is the shape `Form` was built to allow and `Resource` to refuse: it
  * will never be a row, it mounts nothing, and the guard's own POST route is the only handler that
  * reads one. `Password.Plain` rather than `Password` is the whole point of the pair: this is the
  * text as typed, on its way to a `verify`, and the type says so all the way from the input to the
  * one place a [[Guard]] verifies anything.
  */
case class Login(email: String, password: Password.Plain) derives Form

/** What identifies the user behind a request, for one way of signing in: a password.
  *
  * Built from two functions, a URL and how long a sign in should last, and nothing else. `find`
  * turns the key in the session into the application's own user, and `credentials` turns an email
  * into the key and the stored hash that go with it. Both are the application's, which is what
  * keeps `modules/auth` off `modules/db`: the guard never learns that a table exists, and
  * `examples/blog` supplies two one-line closures over its own `read`. Constructing one therefore
  * touches nothing, so a boot that never serves opens no connection.
  *
  * `credentials` says where the hash is and stops there; comparing it against what was typed is the
  * guard's, in [[submitted]] and nowhere else. An application that did its own comparison had to be
  * trusted to answer an unknown email as slowly as a wrong password, which is not a thing a
  * one-line lookup does by accident, and the plain text had to travel out to it to be compared.
  *
  * The login it carries is unthrottled, and deliberately: every attempt that decodes, right or
  * wrong, known email or not, costs one bcrypt at [[Password]]'s shipped strength, so a few hundred
  * guesses a second is also a few hundred quarter seconds of this server's CPU a second. A body
  * that will not decode, an empty password or one past the 72 bytes bcrypt reads, never gets that
  * far. Refusing an address after so many tries is the reverse proxy's job, where the addresses and
  * the rest of the application's traffic already are, and not something a guard can do honestly
  * from inside one process.
  *
  * One guard per user model. `required`, `only` and `except` are how it becomes a [[Guarded]], and
  * every declaration it makes carries [[carries]], the same three route instances, so an
  * application that guards anything has mounted the page it redirects to without writing a line.
  */
final class Guard[U] private (
    find: Id[U] => Option[U],
    credentials: String => Option[(Id[U], Password)],
    login: Url,
    home: Url,
    lifetime: Duration,
    clock: Clock
) {

  /** Where the logout button posts.
    *
    * Derived from [[login]] rather than taken as a second parameter, so that moving the login page
    * moves the pair: a guard whose login is at `/admin/signin` logs out at `/admin/logout`, and
    * there is no way to configure one without the other and end up with a logout route outside the
    * mount its login page is in.
    */
  private val logout: Url =
    Url.Mounted(login.path.split("/").dropRight(1).mkString("/") + "/logout")

  /** Who the guard says is behind this request.
    *
    * Throws rather than answering `None`, because a handler behind a guarded route has already been
    * told there is somebody: the wrapper redirected everyone else before this handler ran. Reaching
    * here with nobody means the route was not guarded, which is a mistake in the route table and
    * not a thing to branch on. A public page that wants to greet a user if there is one is a
    * separate question, and it stays unanswered until a page actually asks it.
    *
    * A `java.lang.IllegalStateException` and not one of eezo's HTTP failures, because the mistake
    * is in the route table rather than in the request: no request could have made this handler
    * behave, so there is no status that describes the caller's part in it. It reaches the boundary
    * as anything else outside the sealed set does and becomes a 500, whose detail a developer reads
    * in `dev` and nobody reads in production.
    *
    * @throws java.lang.IllegalStateException
    *   when the session names nobody, or names a row that is no longer there.
    */
  def current(request: Request): U =
    who(request.session).getOrElse(
      throw new IllegalStateException(
        "no user is signed in for this request; Guard.current is for a handler behind a guarded " +
          "route, and this route is not guarded"
      )
    )

  /** The sign out form, for a page with somewhere to put one, and nothing at all when the browser
    * is not signed in.
    *
    * A form rather than a link, because signing out changes something and `Csrf.protect` verifies
    * every unsafe verb: the hidden token is the field whose absence is a 403, and it is rendered
    * here so that no application hand builds the one input the logout route will not run without.
    * The empty answer is the other half, and it is why this is a `Html` rather than a pair of
    * methods: a page splices the result and never asks who is there, which keeps [[current]]'s rule
    * intact, that asking who is behind a request is for a handler the guard let through.
    *
    * `action` defaults to the guard's own logout route, a [[Url.Mounted]] that travels with the
    * routes, so a page inside the mount takes the default and names no prefix. A page outside the
    * mount, which is where `examples/blog` has one to show, points in with a [[Url.Absolute]], the
    * same way every other link from outside a mount does.
    */
  def logoutForm(request: Request, action: Url = logout): Html =
    who(request.session).fold(Html.empty) { _ =>
      form(
        Attrs.action := action,
        Attrs.method := "post",
        Csrf.hidden(request.csrf),
        button(Attrs.tpe := "submit", "Sign out")
      )
    }

  /** Every route of the thing this declares needs a signed in user.
    *
    * A [[GuardedBy]] rather than a bare [[Guarded]], which is a subtype and so changes nothing a
    * caller wrote: it is what lets `.owning` be chained on, because ownership needs the one thing a
    * `Guarded` deliberately does not carry, a way back to who is signed in. The model type has to
    * be written when the chain continues, `required[Post].owning(_.author)`, because a selector's
    * parameter type cannot be inferred from a chain the expected type reaches only at its end.
    */
  def required[A]: GuardedBy[A, U] = declaring(Action.values.toSet)

  /** Exactly these routes need one; the rest are open. */
  def only[A](actions: Action*): GuardedBy[A, U] = declaring(actions.toSet)

  /** Everything but these needs one. */
  def except[A](actions: Action*): GuardedBy[A, U] = declaring(Action.values.toSet -- actions)

  /** The guard's own three routes, built once per guard.
    *
    * A `val`, so that every [[Guarded]] this guard makes carries the same instances. The route
    * table concatenates what every declaration carries and takes `distinct`, and `distinct` is
    * identity before it is equality: two separately built but equal routes would both survive it,
    * and `RouteTable`'s duplicate check would then refuse to boot with a message about a duplicate
    * route nobody wrote twice.
    */
  private val carries: Seq[Route] = Seq(
    Route.derived(Method.GET, login.path, loginPage(None)),
    Route.derived(Method.POST, login.path, submitted),
    Route.derived(Method.POST, logout.path, signedOut)
  )

  private def declaring[A](actions: Set[Action]): GuardedBy[A, U] =
    new GuardedBy[A, U](actions, through, carries, currentUserKey, identify)

  /** Who the guard says is behind this request, as the key an owned model's owner field holds, for
    * a declaration that goes on to scope rows by it.
    *
    * The session's own entry, decoded, rather than [[current]]'s row: what an owned model's column
    * holds is the key, and reading the row back to take its key off again would be a lookup per
    * request for a value the session already spells.
    *
    * It answers rather than throwing, unlike [[current]], because a `Resource` asks this about
    * requests it has not vouched for: a public show page reads it to decide whether to offer the
    * owner's controls. Whether nobody is an ordinary visitor or a route table that forgot to guard
    * a covered route is a question only the call site can answer, so the call site is where the
    * mistake surfaces.
    */
  private val currentUserKey: Request => Option[Id[U]] = request => key(request.session)

  /** The request with the guard's verdict written on it, as the key the session spells.
    *
    * One `val` per guard, for the reason [[carries]] is one: every declaration this guard makes
    * hands the table the same instance, so a table mounting twenty guarded things composes one
    * function rather than twenty copies of it.
    *
    * It is the same rule [[currentUserKey]] reads and never a second one, so a page and a socket
    * upgrade cannot disagree about who is there, and it names rather than refuses: [[through]]
    * decides who is let through, and this decides what the request says about whoever was. The page
    * arm of `through` writes the same name through [[named]] off the key it already decoded to let
    * the request in, rather than calling this and reading the session a second time.
    *
    * A request that already carries a name is handed back untouched, rather than overwritten with
    * whatever this guard's own session entries say. `RouteTable` composes the naming of every
    * declaration with `andThen`, and an application may hold more than one guard, one per user
    * model, so a table can carry a guard whose sign in expires sooner beside one whose sign in
    * lasts longer. Without this check the later function in the composition would answer `None` for
    * a session it does not recognise as current and erase the `Some` an earlier guard in the same
    * chain had already written for the same browser, turning a signed in visitor into nobody
    * depending on the order declarations happened to be mounted in.
    */
  private val identify: Request => Request =
    request => currentUserKey(request).fold(request)(named(request, _))

  /** `request` naming `id`, unless it already names someone; see [[identify]] for why not. */
  private def named(request: Request, id: Id[U]): Request =
    if (request.currentUser.isDefined) request else request.copy(currentUser = Some(id.show))

  /** The wrapper every guarded route goes through.
    *
    * Two cases, because refusing an upgrade is not refusing a page. A browser asking for HTML is
    * sent to the login page, which is a `Response` and never a failure; a WebSocket has nowhere to
    * send anyone, so it is [[io.eezo.http.Forbidden]] and the boundary answers 403.
    *
    * 403 rather than a status that says "sign in first", because a cookie session has no challenge
    * to offer a client and a 401 carrying none is a status Jetty's WebSocket client hides from the
    * caller as a protocol violation. A refusal a socket client cannot read is a refusal that
    * teaches nobody anything, and the problem detail says which refusal this is.
    *
    * It wraps the handler rather than the dispatch, which is the whole of `RouteTable.dispatch`'s
    * ordering guarantee: `Csrf.protect` sits outside this, so a forged `POST` to a guarded route is
    * refused as forged instead of being redirected to a login page an attacker can read. Naming
    * sits inside it for the same reason and must stay there: naming out in the dispatch would put
    * it ahead of the CSRF check.
    *
    * Only the page arm names anyone. A page behind this guard is one the wrapper already let
    * through, so the handler reads the user off the request it was handed; an upgrade is named by
    * the route table instead, which is the one thing holding every declaration at once and so the
    * only thing that can name a socket nobody guarded.
    */
  private val through: Route => Route = {
    case route: Route.Http =>
      route.copy(handler =
        request =>
          signedIn(request.session) match {
            case Some((id, _)) => route.handler(named(request, id))
            case None          => refuse(request)
          }
      )
    case route: Route.Ws =>
      route.copy(endpoint =
        request =>
          if (signedIn(request.session).isDefined) route.endpoint(request)
          else
            throw Forbidden(
              "this WebSocket route is guarded and no user is signed in; a socket has no page to " +
                "be redirected to, so it is refused instead"
            )
      )
  }

  /** The user the session names and the key it named them by, if it names one that is still there.
    *
    * The pair rather than one half or the other, because the callers want different halves of the
    * same lookup and neither half recovers the other. [[through]] writes the key on the request,
    * which is what an owner column holds and what [[currentUserKey]] answers, while [[current]]
    * wants the row. Answering the key alone would send every handler behind a guard back through
    * `find` for a row this method had already read, and answering the row alone would mean taking a
    * key back off a user, which is `find`'s direction and not its inverse.
    *
    * It is also the only place either of the two questions a guard is asked about a request, who is
    * there and whether anybody is, gets decided. `find` is the application's, and an application
    * whose users are rows is one where it is a query, so how many times a single request runs it is
    * a number worth keeping at one.
    */
  private def signedIn(session: Session): Option[(Id[U], U)] =
    key(session).flatMap(id => find(id).map((id, _)))

  /** The user the session names, if it names one that is still there. */
  private def who(session: Session): Option[U] = signedIn(session).map(_._2)

  /** The key the session names, whether or not its user is still there, and only while the sign in
    * that wrote it is still inside [[lifetime]].
    *
    * The age check belongs here and nowhere else. [[who]], [[signedIn]], [[current]] and
    * [[currentUserKey]] all read the session through this one method, so a guarded page, a
    * WebSocket, the sign out form and an ownership scope all expire together; a second check beside
    * any of them would be a second thing to keep in step.
    *
    * A `_user` with no stamp beside it reads as nobody rather than as a sign in of unknown age.
    * Every session signed before the lifetime existed has that shape, and reading it as valid would
    * mean the one browser the rule was written for, the copied session, is the one browser it never
    * applies to. Signing everyone out once is the cost, and it is paid once.
    */
  private def key(session: Session): Option[Id[U]] =
    for {
      named <- session.reserved(Guard.UserEntry)
      since <- session.reserved(Guard.StampEntry).flatMap(Guard.readStamp)
      if !expired(since)
      id <- Guard.parse[U](named)
    } yield id

  /** Whether a sign in made at `since` has outlived [[lifetime]].
    *
    * Measured from the stamp to now rather than by adding the lifetime to the stamp, so no arrival
    * can push an instant past what `Instant` can hold and turn a login redirect into a 500. Exactly
    * `lifetime` old is still a sign in: what is refused is a stamp older than the lifetime, and a
    * boundary somebody can reach only by arriving on the nanosecond is not worth a second spelling.
    *
    * A reading ahead of the clock is not refused. The stamp is eezo's own signed value, so nobody
    * but eezo chose it, and a stamp in the future means this server's clock moved backwards rather
    * than that anything is wrong with the browser; signing every live browser out over a correction
    * of a few seconds would be the larger surprise.
    */
  private def expired(since: Instant): Boolean =
    Duration.between(since, clock.instant()).compareTo(lifetime) > 0

  /** The 303 an anonymous or stale browser gets.
    *
    * The refused address is remembered in the session rather than in the query string, because a
    * query string survives being copied out of the address bar and pasted into a chat window, and
    * because a value only this application can sign is one an attacker cannot choose. What is worth
    * remembering is [[Guard.remembered]]'s question. The stale entry is dropped on the way out, so
    * a browser whose user was deleted does not arrive stale forever.
    *
    * A refusal that remembers nothing leaves whatever an earlier one remembered where it is, rather
    * than clearing it: the page a refused submission came from is the page the person is looking
    * at, and sending them back to it is less surprising than sending them to `home` because the
    * last thing they did was press a button.
    *
    * The session written here is the request's, amended, not a fresh one. It therefore still
    * carries the token dispatch minted, which is what keeps `Csrf.protect` from reading this
    * response as a logout and sending the browser to the login page with no token to submit with.
    */
  private def refuse(request: Request): Response = {
    val cleared =
      request.session.withoutReserved(Guard.UserEntry).withoutReserved(Guard.StampEntry)
    Response
      .Redirect(login)
      .withSession(
        Guard.remembered(request).fold(cleared)(cleared.withReserved(Guard.ReturnEntry, _))
      )
  }

  /** The GET behind the login page. Named for the page rather than for the tag it renders, because
    * `form` is the tag [[logoutForm]] builds with and a member of this class would shadow it.
    *
    * `raw` is the submission to render the inputs from, empty for the GET and the rejected body for
    * [[rejected]], which is the parameter `Form.render` has for exactly this and the one a rejected
    * `Resource` submission passes `request.form` to.
    */
  private def loginPage(
      errors: Option[String],
      raw: Map[String, Seq[String]] = Map.empty
  ): Handler = request =>
    Response.Ok(
      Guard.page(Form[Login].render(login, Method.POST, None, request.csrf, raw = raw), errors)
    )

  /** The POST behind the login form.
    *
    * A failure is a 422 carrying the form again rather than a redirect, matching what a rejected
    * `Resource` submission does, and it says one thing for a wrong password and for an unknown
    * email. Which of the two it was is exactly what an attacker is asking. Both failures, the body
    * that will not decode and the one that decodes and is refused, go through [[rejected]], which
    * is where what comes back in the inputs is settled.
    *
    * A miss pays the bcrypt a hit pays, which is [[Password.matches]]'s doing: the two refusals
    * cost the same and the clock stops saying which emails exist. The one thing that survives is
    * `credentials` itself: an application's own query can still take different times for a row that
    * is there and one that is not, and no amount of hashing here closes that.
    *
    * This is the only place a guard verifies anything, which is what makes the paragraph above a
    * property of the guard rather than a habit every application has to keep.
    */
  private def submitted: Handler = request =>
    request
      .as[Login]
      .toOption
      .flatMap { attempt =>
        val found = credentials(attempt.email)
        // Computed out here and not inside `found.map`, because a `None` would skip it, and the
        // miss is the branch that has to pay.
        val matched = Password.matches(found.map { case (_, stored) => stored }, attempt.password)
        found.collect { case (key, _) if matched => key }
      }
      .fold(rejected(request)) { key =>
        Response
          .Redirect(Guard.back(request.session, home))
          // Rebuilt from empty, so nothing an attacker could have planted in the old session
          // survives the privilege change, and rotated, so the token does not either. This is the
          // one place a stamp is ever written: the lifetime runs from the sign in, so refreshing it
          // anywhere a session is merely read would make it a sliding one and would cost a
          // `Set-Cookie` on every page that only looks at the session.
          .withSession(
            Csrf.rotated(
              Session.empty
                .withReserved(Guard.UserEntry, key.show)
                .withReserved(Guard.StampEntry, Guard.stamped(clock.instant()))
            )
          )
      }

  /** The 422 a refused sign in comes back as: the same page, the one message, and the body that was
    * submitted rendered back into the inputs.
    *
    * The whole body goes in, both when it failed to decode as a [[Login]] and when the email and
    * the password it decoded to did not match, so the email is there to be typed over. The password
    * travels in that map too and still never reaches the page: `Form.render` suppresses every field
    * that renders as a password box, which is where that rule belongs, since it holds for a
    * rejected `Resource` submission just as much as for this one.
    *
    * The message stays on the page rather than becoming a [[FormErrors]] against `email` or
    * `password`, because an error beside one input says which of the two was wrong.
    */
  private def rejected(request: Request): Response =
    loginPage(Some("that email and password do not match"), request.form)(request)
      .copy(status = 422)

  /** The POST behind the logout button.
    *
    * `Session.empty` and not an amended session: signing out has to lose the token as well as the
    * user, or the browser walks away from a shared machine still holding a value that its next form
    * would submit. `Csrf.protect` reads an explicitly empty session as exactly this, and expires
    * the cookie rather than carrying anything into it.
    */
  private def signedOut: Handler = _ => Response.Redirect(login).withSession(Session.empty)
}

object Guard {

  /** The session entry the signed in user's key travels under.
    *
    * Reserved, built from `Session.Reserved`, for the reason the CSRF token's entry is: an
    * application cannot write it through `Session.set`, so "who is signed in" is not something
    * application code can assign itself. An ordinary entry named `user` would be a privilege
    * escalation one line long.
    */
  private[eezo] val UserEntry: String = Session.Reserved + "user"

  /** The session entry the refused address travels under, so that signing in lands where the
    * browser was going rather than at a page it did not ask for.
    */
  private[eezo] val ReturnEntry: String = Session.Reserved + "return"

  /** The session entry the moment of signing in travels under, reserved for [[UserEntry]]'s own
    * reason: a stamp an application could write through `Session.set` is a lifetime an application
    * could set to whenever it liked, which is the same escalation one line long.
    *
    * Epoch milliseconds as plain digits, which is the encoding that cannot fail on the way back in.
    * `Instant.ofEpochMilli` accepts every `Long` there is, where `ofEpochSecond` throws for the
    * large ones and ISO text has a parser with opinions, and [[readStamp]] sits on the path of
    * every guarded request: a value that throws there is an error page where a login redirect
    * belongs.
    */
  private[eezo] val StampEntry: String = Session.Reserved + "since"

  /** How long a sign in lasts when an application says nothing.
    *
    * Two weeks is long enough that a person using an application most days is never asked again,
    * and short enough that a laptop left in a taxi stops being a way in within a fortnight.
    */
  val DefaultLifetime: Duration = Duration.ofDays(14)

  /** The longest remembered address, measured on the form the session actually carries rather than
    * on the address itself, past which a refusal remembers nothing.
    *
    * The session is one signed cookie, and `SessionCookie.encode` refuses to build one past the
    * roughly 4000 bytes a browser keeps rather than let the browser drop it in silence. An
    * outlandish query string would therefore turn a refusal, which is a redirect somebody sees,
    * into a 500 nobody asked for. Forgetting the address instead lands the login on `home`, which
    * is a page.
    *
    * The measurement has to be [[encode]]'s own output. One raw character can become three, six or
    * even twelve encoded ones before the cookie's own base64 step adds a further third on top, so a
    * cap read against the raw address has no bounded relationship to what the cookie can actually
    * hold; a cap read against the encoded form does, because that form is what `_return` carries
    * into `SessionCookie.form`. 2048 measured that way leaves comfortable room under the roughly
    * 2800 bytes the rest of a session, the CSRF token among them, leaves free, while still being
    * generous enough that no address a person is looking at reaches it.
    */
  private[auth] val MaxReturn: Int = 2048

  /** A guard over `U`.
    *
    * `credentials` answers with the key and the stored hash of the row that email names. A lookup
    * that has returned its pair has finished, so the hashing happens after whatever the lookup held
    * is let go: an application reading from a pool, as `examples/blog` does, is no longer holding a
    * connection while bcrypt runs. That is what the shape gives and the whole of it. A
    * `credentials` that handed back a pair from inside a scope it had not closed, or one whose
    * second element is only computed when read, would still be holding it, and no signature can say
    * otherwise.
    *
    * `login` defaults to a mounted `/login`, which is what makes the common case one line and the
    * mounted case correct: a `Url.Mounted` is rewritten by `Route.under`'s response wrapper, so a
    * guard under `/admin` redirects to `/admin/login` and the login route it carries answers there.
    * A plain `String` here would 404 the moment anything was mounted.
    *
    * `home` is where a sign in that was not preceded by a refusal lands, and it defaults to the
    * mount root only because a guard cannot guess which of an application's routes is the one a
    * signed in user wants. An application that mounts nothing at its root, which is the ordinary
    * shape once anything is guarded, sets `home` to the page it does mount, the same way `Post`'s
    * declaration is the only place that names `/posts`.
    *
    * `lifetime` is how long a sign in lasts, counted from the moment it was made and not refreshed
    * by use. A parameter with a default rather than an `HttpApp` override or an environment
    * variable, because how long a sign in should last is a property of the thing being guarded: an
    * admin area and a blog want different answers, and an application with two guards would have no
    * way to say so through one setting.
    *
    * `clock` exists so that a suite can ask what happens after a fortnight without waiting one. It
    * is read only where a sign in is stamped and where its age is measured, so passing one changes
    * nothing else, and the default is the system clock in UTC.
    */
  def apply[U](
      find: Id[U] => Option[U],
      credentials: String => Option[(Id[U], Password)],
      login: Url = Url.Mounted("/login"),
      home: Url = Url.Mounted("/"),
      lifetime: Duration = DefaultLifetime,
      clock: Clock = Clock.systemUTC()
  ): Guard[U] = new Guard[U](find, credentials, login, home, lifetime, clock)

  /** The key out of the session text. A session eezo signed can only hold what eezo wrote, so a
    * value that is not a UUID means the secret changed under a live browser rather than that anyone
    * tampered; either way there is no user, and answering `None` sends them to log in again.
    */
  private[auth] def parse[U](text: String): Option[Id[U]] = summon[FromPath[Id[U]]].apply(text)

  /** The moment of a sign in, as the session carries it. [[readStamp]] reads it back. */
  private[auth] def stamped(at: Instant): String = at.toEpochMilli.toString

  /** The moment [[stamped]] wrote, back out of the session text, and `None` for anything that is
    * not one.
    *
    * [[parse]]'s precedent, for [[parse]]'s reason: a value that will not read is no sign in, and
    * answering `None` sends the browser to log in again instead of turning every guarded page into
    * a 500.
    */
  private[auth] def readStamp(text: String): Option[Instant] =
    text.toLongOption.map(Instant.ofEpochMilli)

  /** What a refusal is worth remembering, when it is worth anything.
    *
    * A `GET` and nothing else. Signing in ends in a redirect, which the browser follows with a
    * `GET`, so a remembered `POST`, `PUT`, `PATCH` or `DELETE` address comes back under a verb that
    * address may not answer: a 404 or a 405 on the first page somebody sees after signing in.
    * `HEAD` goes with them rather than with `GET`, since it asks for the headers of a page nobody
    * is looking at.
    *
    * The query string travels with the path, or a refused `/posts?page=3` comes back as page one.
    * It is rebuilt from the parameters the request decoded rather than kept verbatim, because that
    * is what a [[Request]] carries, with the names sorted so one address has one spelling. Every
    * name and value is encoded on the way, which is also why nothing a query carries can spell the
    * backslash or the control character [[relative]] refuses.
    *
    * The length filter reads [[MaxReturn]] against `encode(address)`, not against the address
    * itself. What the session actually stores this string as is that encoded form, so a cap on the
    * raw address bounds nothing about the cookie the guard is trying to protect.
    */
  private[auth] def remembered(request: Request): Option[String] =
    Option
      .when(request.method == Method.GET)(request.path + queryString(request.query))
      .filter(address => encode(address).length <= MaxReturn)

  /** `?a=1&b=2`, or nothing at all when there are no parameters. */
  private def queryString(query: Map[String, Seq[String]]): String =
    if (query.isEmpty) ""
    else
      query.toSeq
        .sortBy(_._1)
        .flatMap { case (name, values) => values.map(value => s"${encode(name)}=${encode(value)}") }
        .mkString("?", "&", "")

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Where a successful login goes: the address the guard refused, path and query string together,
    * when the session remembers one that is safe to use, and the guard's configured `home`
    * otherwise.
    *
    * [[Url.Absolute]] rather than [[Url.Mounted]] for the remembered branch, and that is not a
    * detail. The remembered path is what the guard's wrapper saw, which is already the mounted
    * path, so emitting it as `Mounted` would let `Route.under`'s response wrapper add the prefix a
    * second time and send the browser to `/admin/admin/posts`. `Absolute` is the case that means
    * "finished address, leave it alone". `home` stays a `Url` rather than being resolved here,
    * because it is the caller's own page and only the caller's mount knows how to reach it.
    */
  private[auth] def back(session: Session, home: Url): Url =
    session.reserved(ReturnEntry).filter(relative).map(Url.Absolute.apply).getOrElse(home)

  /** Whether a remembered address is this application's own.
    *
    * The whole of it is checked, query string included, which is safe in the one direction that
    * matters: a query can only make an address that would pass refused, never the other way round.
    * `?next=//evil.example.com` is a value some page reads and not an address anything redirects
    * to, so it passes; a path that itself begins `//` names another host, and is refused whether a
    * query follows it or not.
    *
    * An open redirect is the failure this exists to prevent, and it has more spellings than it
    * looks. A leading `//` or `/\` is protocol relative and names another host; a backslash
    * anywhere is read as a slash by enough browsers that it cannot be treated as an ordinary
    * character; a control character can split the `Location` header into two. Anything not rooted
    * at a single `/` is refused rather than repaired, because repairing an address an attacker
    * chose is how the next spelling gets through.
    */
  private[auth] def relative(path: String): Boolean =
    path.startsWith("/") &&
      !path.startsWith("//") &&
      !path.contains('\\') &&
      !path.exists(c => c.isControl)

  /** The page the login form and its refusal come back in, matching the plain envelope `Resource`
    * and `Boundary` already render into.
    */
  private[auth] def page(rendered: Html, error: Option[String]): Html =
    Html.doctype ++ html(
      head(meta(Attrs.charset := "utf-8"), title("Sign in")),
      body(
        h1("Sign in"),
        error.map(message => p(Attrs.cls := "error", message)).toSeq,
        rendered
      )
    )
}
