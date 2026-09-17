package io.eezo.auth

import io.eezo.core.Id
import io.eezo.core.html.{Attrs, Html, Url}
import io.eezo.core.html.Tags.*
import io.eezo.http.*

/** What a login form decodes to.
  *
  * Keyless on purpose, which is the shape `Form` was built to allow and `Resource` to refuse: it
  * will never be a row, it mounts nothing, and the guard's own POST route is the only handler that
  * reads one. `Password.Plain` rather than `Password` is the whole point of the pair: this is the
  * text as typed, on its way to a `verify`, and the type says so all the way from the input to
  * [[Guard.authenticate]].
  */
case class Login(email: String, password: Password.Plain) derives Form

/** What identifies the user behind a request, for one way of signing in: a password.
  *
  * Built from two functions and a URL, and nothing else. `find` turns the key in the session into
  * the application's own user, and `authenticate` turns an email and a typed password into a key.
  * Both are the application's, which is what keeps `modules/auth` off `modules/db`: the guard never
  * learns that a table exists, and `examples/blog` supplies two one-line closures over its own
  * `read`. Constructing one therefore touches nothing, so a boot that never serves opens no
  * connection.
  *
  * One guard per user model. `required`, `only` and `except` are how it becomes a [[Guarded]], and
  * every declaration it makes carries [[carries]], the same three route instances, so an
  * application that guards anything has mounted the page it redirects to without writing a line.
  */
final class Guard[U] private (
    find: Id[U] => Option[U],
    authenticate: (String, Password.Plain) => Option[Id[U]],
    login: Url,
    home: Url
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
    * @throws Unauthorized
    *   when the session names nobody, or names a row that is no longer there.
    */
  def current(request: Request): U =
    who(request.session).getOrElse(
      throw Unauthorized(
        "no user is signed in for this request; Guard.current is for a handler behind a guarded " +
          "route, and this route is not guarded"
      )
    )

  /** Every route of the thing this declares needs a signed in user. */
  def required[A]: Guarded[A] = declaring(Action.values.toSet)

  /** Exactly these routes need one; the rest are open. */
  def only[A](actions: Action*): Guarded[A] = declaring(actions.toSet)

  /** Everything but these needs one. */
  def except[A](actions: Action*): Guarded[A] = declaring(Action.values.toSet -- actions)

  /** The guard's own three routes, built once per guard.
    *
    * A `val`, so that every [[Guarded]] this guard makes carries the same instances. The route
    * table concatenates what every declaration carries and takes `distinct`, and `distinct` is
    * identity before it is equality: two separately built but equal routes would both survive it,
    * and `RouteTable`'s duplicate check would then refuse to boot with a message about a duplicate
    * route nobody wrote twice.
    */
  private val carries: Seq[Route] = Seq(
    Route.derived(Method.GET, login.path, form(None)),
    Route.derived(Method.POST, login.path, submitted),
    Route.derived(Method.POST, logout.path, signedOut)
  )

  private def declaring[A](actions: Set[Action]): Guarded[A] = Guarded(actions, through, carries)

  /** The wrapper every guarded route goes through.
    *
    * Two cases, because refusing an upgrade is not refusing a page. A browser asking for HTML is
    * sent to the login page, which is a `Response` and never a failure; a WebSocket has nowhere to
    * send anyone, so it is [[Unauthorized]] and the boundary answers 401.
    *
    * It wraps the handler rather than the dispatch, which is the whole of `RouteTable.dispatch`'s
    * ordering guarantee: `Csrf.protect` sits outside this, so a forged `POST` to a guarded route is
    * refused as forged instead of being redirected to a login page an attacker can read.
    */
  private val through: Route => Route = {
    case route: Route.Http =>
      route.copy(handler =
        request => if (signedIn(request)) route.handler(request) else refuse(request)
      )
    case route: Route.Ws =>
      route.copy(endpoint =
        request =>
          if (signedIn(request)) route.endpoint(request)
          else
            throw Unauthorized(
              "this WebSocket route is guarded and no user is signed in; a socket has no page to " +
                "be redirected to, so it is refused instead"
            )
      )
  }

  private def signedIn(request: Request): Boolean = who(request.session).isDefined

  /** The user the session names, if it names one that is still there. */
  private def who(session: Session): Option[U] =
    session
      .reserved(Guard.UserEntry)
      .flatMap(Guard.parse[U])
      .flatMap(find)

  /** The 303 an anonymous or stale browser gets.
    *
    * The refused path is remembered in the session rather than in the query string, because a query
    * string survives being copied out of the address bar and pasted into a chat window, and because
    * a value only this application can sign is one an attacker cannot choose. The stale entry is
    * dropped on the way out, so a browser whose user was deleted does not arrive stale forever.
    *
    * The session written here is the request's, amended, not a fresh one. It therefore still
    * carries the token dispatch minted, which is what keeps `Csrf.protect` from reading this
    * response as a logout and sending the browser to the login page with no token to submit with.
    */
  private def refuse(request: Request): Response =
    Response
      .Redirect(login)
      .withSession(
        request.session
          .withoutReserved(Guard.UserEntry)
          .withReserved(Guard.ReturnEntry, request.path)
      )

  private def form(errors: Option[String]): Handler = request =>
    Response.Ok(Guard.page(Form[Login].render(login, Method.POST, None, request.csrf), errors))

  /** The POST behind the login form.
    *
    * A failure is a 422 carrying the form again rather than a redirect, matching what a rejected
    * `Resource` submission does, and it says one thing for a wrong password and for an unknown
    * email. Which of the two it was is exactly what an attacker is asking.
    *
    * `authenticate` answers an unknown email without hashing anything, so the two cases take
    * measurably different times. That is user enumeration by timing, and it is knowingly accepted
    * here rather than papered over with a dummy verify, which is a defence that has to be
    * maintained and is silently lost the first time the query changes.
    */
  private def submitted: Handler = request =>
    request
      .as[Login]
      .toOption
      .flatMap(credentials => authenticate(credentials.email, credentials.password))
      .fold(rejected(request)) { key =>
        Response
          .Redirect(Guard.back(request.session, home))
          // Rebuilt from empty, so nothing an attacker could have planted in the old session
          // survives the privilege change, and rotated, so the token does not either.
          .withSession(Csrf.rotated(Session.empty.withReserved(Guard.UserEntry, key.show)))
      }

  private def rejected(request: Request): Response =
    form(Some("that email and password do not match"))(request).copy(status = 422)

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

  /** The session entry the refused path travels under, so that signing in lands where the browser
    * was going rather than at a page it did not ask for.
    */
  private[eezo] val ReturnEntry: String = Session.Reserved + "return"

  /** A guard over `U`.
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
    */
  def apply[U](
      find: Id[U] => Option[U],
      authenticate: (String, Password.Plain) => Option[Id[U]],
      login: Url = Url.Mounted("/login"),
      home: Url = Url.Mounted("/")
  ): Guard[U] = new Guard[U](find, authenticate, login, home)

  /** The key out of the session text. A session eezo signed can only hold what eezo wrote, so a
    * value that is not a UUID means the signing key changed under a live browser rather than that
    * anyone tampered; either way there is no user, and answering `None` sends them to log in again.
    */
  private[auth] def parse[U](text: String): Option[Id[U]] = summon[FromPath[Id[U]]].apply(text)

  /** Where a successful login goes: the path the guard refused, when the session remembers one that
    * is safe to use, and the guard's configured `home` otherwise.
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

  /** Whether a remembered path is this application's own.
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
