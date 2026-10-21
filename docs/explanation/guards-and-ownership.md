# Guards, guarded routes and owned rows

Three questions get mixed up in most frameworks: is somebody there, may they take this action,
and are these rows theirs. eezo keeps them apart, with one type for each. This page is about
those three and the fourth question eezo doesn't answer yet.

## The guard: is somebody there

A `Guard[User]` is one way of signing in, for one user model. You build it from two functions
and a few defaults:

```scala
given guard: Guard[User] = Guard[User](
  find        = id => read(Table[User].findById(id)),
  credentials = email => byEmail(email).map(user => (user.id, user.password)),
  home        = Url.Mounted("/books")
)
```

`find` turns the key in the session into your user. `credentials` turns an email into the key
and the stored password hash. Both are yours, which is what keeps `eezo-auth` off the database
module: the guard never opens a connection or learns that a table exists. It also means the
guard owns the comparison. Your lookup says where the hash is and stops; the verify happens
inside the guard, so the plain text never travels out to be compared.

The guard carries its own three routes, `GET /login`, `POST /login` and `POST /logout`, and
they come along with every declaration that guards a page or a socket. Guard one route and the
page it redirects to is mounted, without you writing a line. The login path is a `Url.Mounted`,
so a guard under `/admin` logs in at `/admin/login`. A declaration that guards only API routes
mounts no login page, because a program is never sent to one.

A sign in lasts a fixed time from the moment it was made, two weeks by default, however active
the user is. It isn't refreshed by use, because a sliding window would cost a `Set-Cookie` on
every page that merely reads the session, and a laptop left in a taxi should stop being a way
in on a known date.

## Guarded: may they take this action

`Guarded[A]` is the declaration, written in a model's companion or a page's, of which routes
need a signed in user. A model says it per role; a page is guarded or public:

```scala
given Guarded[Post]       = User.guard.required
given Guarded[Post]       = User.guard.only(Action.Create, Action.Update, Action.Destroy)
given Guarded[Index.type] = Guarded.public
```

A guarded route's handler runs behind a wrapper that reads the session, finds the user, and
either lets the request through with the user named on it or answers a 303 to the login page.
The refused address is remembered in the session, so signing in lands where the browser was
going. A guarded WebSocket route can't be redirected, so it's refused with a 403 instead. A
guarded API route has no session to read, so the wrapper reads its Bearer header instead, and
refuses with a 401; that is the second door, below.

The actions a declaration names cover a model's pages and its JSON routes alike, since both
sets come from the one `Actions`. `except(Index, Show)` on a model that derives `Resource` and
`Api` leaves the list and the detail open on a page and under `/api`, and guards every write
in both places.

**Silence is an error.** In an application whose build names `eezo-auth`, every route mounted
has to say who may reach it, and a route with no `Guarded` in scope is a compile error naming
the type, raised in the generated route table. The reason is the default you'd get otherwise.
A default of public makes an unguarded route the thing you get by forgetting. A default of
guarded makes every application need a guard. So there's no default, and `Guarded.public` is a
named, visible decision in the companion of the thing it was made about. An application that
never names `eezo-auth` isn't asked, because it has nobody to sign in; a `Guarded` written in
one is still honoured.

## The current user

Inside a handler behind a guarded route, `User.guard.current(request)` is the user. If nobody
is there, it throws an `IllegalStateException`, because the wrapper already sent everyone else
to the login page and reaching the handler anonymous means the route wasn't guarded. That's a
mistake in the route table, and a 500 with a message naming it is the right answer.

The request also carries `currentUser`, the key, as a fact written by the wrapper. A public
page that wants to greet a signed in visitor reads the key through an ownership declaration's
`currentUser`, which answers `None` for an anonymous browser and throws nothing. Nobody is
an ordinary visitor on a public page and a defect on a guarded one, and only the call site
knows which.

## The second door: a program and its token

A program calling an API route has no session and never will: it sends
`Authorization: Bearer <token>` with every request, and the token is one the application
issued to a user, once. The guard is the same guard with a second way in, built from one more
function:

```scala
given guard: Guard[User] = Guard[User](
  find        = id => read(Table[User].findById(id)),
  credentials = email => byEmail(email).map(user => (user.id, user.password)),
  home        = Url.Mounted("/books"),
  tokens      = hash => byToken(hash).map(_.id)
)
```

`tokens` turns a `Token`, the hash of what the program sent, into the user's key or nothing.
The guard hashes the header's value and asks; the plain text is neither stored nor looked up,
for the same reason the password's isn't. A `Token` is an unsalted SHA 256 where a `Password`
is a salted bcrypt, because the two answer different questions. A password is verified against
the one row an email names, so its hash may differ on every write. A token arrives with
nothing beside it, so the only way to its row is to hash it the same way twice and look the
hash up. What makes that safe is how a token is made, not how it is hashed: `Token.gen()`
draws 256 random bits, which nobody picks and nobody can walk.

On a refusal there is no login page to send a program to, so the answer is a 401 with
`WWW-Authenticate: Bearer`, as a problem whose detail says what was missing. A guard built
without `tokens` refuses to wrap an API route at boot, naming the route: no program could ever
be let through, and a route nobody can reach is a mistake worth finding before the first
request.

Issuing is the application's. `Token.gen()` gives the plain text to show once and the hash to
store, in a `Column[Token]` beside the password; listing, revoking and expiring are pages the
application writes. The shop keeps one token per user, so issuing a new one revokes the last.
Inside a handler, `currentUser` is the same key it is on a page, so an owned model is owned
through either door.

## Owned: are these rows theirs

An owned model records who created each row, in an ordinary field, and says which of its
routes are that user's alone:

```scala
case class Post(id: Id[Post], author: Id[User], title: String, body: String)
  derives Table, Form, Resource

object Post {
  given Owned[Post, User] =
    User.guard.required[Post].owning(_.author).except(Action.Index, Action.Show)
}
```

`required` is who has to be signed in. `owning(_.author)` is the field, written as a selector
so the compiler checks it. `except(Index, Show)` is which routes read the whole table: here
everybody reads everybody's posts, and the other five routes read and write the signed in
author's rows alone. A cart would say `.all`, so a cart is invisible to anyone but its owner.
There's no default for this last part, because covering everything makes the blog's index
private and covering nothing makes the declaration do nothing.

Being owned implies being guarded, never the reverse. `Owned` is a subtype of `Guarded`, so a
model writes one declaration, and a covered route that the guard left open is refused when the
table is built.

Three things follow from the declaration, all decided when the route table is assembled:

- **The owner field is on no page.** The form shows no input for it, the index heads no column
  with it, the show page prints no row. The handler fills it from who is signed in on `create`
  and on a covered `update`, so a hand-crafted `POST` carrying `author=<somebody else>` writes
  a post attributed to whoever sent it.
- **Covered routes read through a scope.** A scope is the rows whose owner is the current
  user, as a store of its own. A row outside it is not there: `find` answers nothing,
  `update` and `delete` match nothing, and the derived route answers 404. The owner is in the
  `where` clause of every statement, so there's no read-then-check race.
- **One case answers 403 instead.** When `Show` is public and mounted, saying "that row is
  somebody else's" reveals nothing a `GET` wouldn't, so asking for someone else's edit page is
  a 403. When `Show` is covered, a 403 would turn the key space into a list of which rows
  exist, so it stays a 404.

The show page offers an Edit link and a Delete button only when the viewer owns the row. A
stranger sees the fields and nothing to click.

The same three hold on the model's JSON routes. A covered list answers the caller's rows, a
foreign row is the same 404 or 403, and a `POST` body that names an owner is overwritten with
the user the token names, so a program writes rows as itself and as nobody else.

## Passwords

`Password` is the stored hash: a string that names its own algorithm and cost, bcrypt at
strength 12. It's an opaque type, so a `String` read from a row becomes one through
`Password.stored`, and a `String` typed in a browser becomes one only by being hashed. There's
no third door. `Password.Plain` is the text as typed, alive until it's hashed or verified, with
a `toString` that prints stars so it can't reach a log by interpolation.

A login attempt costs one bcrypt whether the email exists or not. On a miss, the guard verifies
against a dummy hash and answers false whatever the result, so the clock can't say which emails
have accounts. What the guard doesn't do is throttle. Refusing an address after so many tries
belongs at the reverse proxy, where the addresses and the rest of the traffic already are, and
a counter inside one process would be a promise it can't keep across two.

## What doesn't exist: roles

Authorization by role, whether this user is an admin, is distinct from being known (the guard)
and from owning a row (ownership). No role exists in eezo today. Every signed in user is equal.
When you need one, it's a handwritten check in a handwritten route.

## The bound live page

A live page rendered behind a guarded route remembers the user it was rendered for and admits
one socket, whose upgrade must name the same user. A lapsed sign in names nobody and a browser
signed in as someone else names that account, and both are refused with close code 4403. The
check happens when the socket opens and never again while it stays open; the refusal arrives at
the next reconnect. A page rendered on a public route is unbound, and anyone holding its id
may join.

## Where to go next

The scope a covered route reads through is a store over the database, which is
[the database layer](the-database-layer.md). The bound page is one piece of
[the live layer](the-live-layer.md). The token column, the lookup and the page that issues one
are in [serve a JSON API](../how-to/serve-a-json-api.md).
