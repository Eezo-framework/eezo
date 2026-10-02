# Sessions, flash and the CSRF token

What a browser carries between requests, how eezo keeps it honest, and how a form proves it
came from this application and not from a stranger's page.

## The session is a signed cookie

A session is a few named strings, kept for one browser. eezo puts them in one cookie,
`eezo_session`, and signs it with the application's secret. The browser holds the value and
sends it back on every request, and it can read it, but it can't change it: a cookie whose
signature doesn't verify is read as no session at all. Signed, not encrypted, because a user
id and a flash aren't secrets from the browser that holds them.

The cookie has no `Max-Age`, so it lives with the browser session and is gone when the browser
closes. It's `HttpOnly`, `SameSite=Lax`, `Path=/`, and `Secure` whenever the request came over
HTTPS.

Names starting with `_` are reserved. `session.set("_user", ...)` is refused, because that's
where the guard keeps who is signed in, and an application that could write it would be a
privilege escalation one line long. The CSRF token and the flash live under reserved names
too, and `session.isEmpty` looks past all of them: it asks whether the application put
anything there.

## The flash

A flash is a value for exactly the next request. A create handler redirects to the show page
and wants that page to say "created"; it writes `session.flash("notice", "created")` on the
response, and the handler of the following request reads `request.session.flash("notice")`.
After that it's gone. The flash rides inside the same cookie, under a reserved prefix, so
there's no second cookie and no server-side store.

## The secret

The signing key is `EEZO_SECRET`, read once when the server boots. It has to be at least 32
bytes, because a shorter key can be guessed and a guessed key signs a session anyone can forge.
`openssl rand -base64 32` makes one.

Without it, eezo generates a throwaway and says so at WARNING. That's the right default on a
laptop: `hello` runs with nothing configured. It's the wrong one in production, where every
restart would sign everyone out and two instances couldn't read each other's cookies, which is
why the warning is there.

Under `eezo dev` you never see that warning. The dev loop forks a fresh JVM on every save, and
a fresh throwaway each time would sign you out on every edit. So the sbt plugin generates one
secret per sbt session and hands it to every restart as `EEZO_SECRET`.

## A session too large for a cookie

Browsers keep about 4096 bytes of one cookie, and a `Set-Cookie` past that is dropped in
silence: the next request arrives with no session or a stale one, and nothing says why. eezo
refuses to encode a session past 3800 bytes, and the refusal is a thrown exception naming the
size, with the advice to store a key in the session and the data elsewhere. That's a defect in
the application, not a request to answer, so it's loud.

## The CSRF token

A form on your page carries a hidden `_csrf` input. Any request that isn't safe, so `POST`,
`PUT`, `PATCH` or `DELETE`, has to return the token the session holds, or it's refused with a
403:

```
the CSRF token is missing or stale; reload the page and try again
```

That's how a submission proves it came from a page this application served to this browser.
Another site can make your browser `POST` to your application, cookie and all, but it can't
read the token out of a page it never received.

How it works, in order:

- **One token per session.** It lives in a reserved session entry, so it's as private as the
  cookie and lives as long. Not a second cookie, since the session is already signed; not one
  per form, since that breaks the back button and a second tab.
- **Minted at dispatch, on first sight.** After a route matches and before its handler runs,
  a session with no token gets one. So `request.csrf` inside a handler can't fail, and a first
  anonymous `GET` costs one `Set-Cookie`.
- **Verified at dispatch, on unsafe methods.** After the match, so a 404 is still a 404; before
  the handler and before any guard around it, so a forged `POST` to a guarded route is refused
  as forged, never redirected to a login page. Safe methods are never checked, and a WebSocket
  upgrade is a `GET`.
- **Rotated at sign in.** A login handler rebuilds the session from empty, so nothing planted
  before the privilege change survives, and the token is part of what gets replaced. The cost
  is a form opened in a second tab before signing in, refused once with the message above.
- **Gone at sign out.** An explicitly empty session on the response expires the cookie, token
  included, so a browser walking away from a shared machine holds nothing its next form would
  submit.

There's no opt-out and no exemption for a prefix. The drift page `eezo dev` serves carries the
token like any form, because a localhost dev server is a classic drive-by target: any open tab
can `POST` to it.

## Putting it in a form

`Form.render` takes the token as a required parameter, so a derived form can't be rendered
without one, and emits the hidden input itself. A handwritten form uses the same helper:

```scala
form(
  Attrs.action := "/books",
  Attrs.method := "post",
  Csrf.hidden(request.csrf),
  ...
)
```

The guard's sign-out button is a form the guard builds for you, `User.guard.logoutForm(request)`,
for this reason. Signing out is a `POST`, a `POST` has to carry the token, and a hand-written
form that forgot it would be refused at the one route you'd least expect to refuse you.

## Where to go next

Who the session says is signed in, and what a guard does with that, is
[guards and ownership](guards-and-ownership.md).
