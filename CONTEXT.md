# eezo

A Scala 3 web framework where the case class is the source of truth. A model declares what it is,
and eezo derives its HTML form, its routes and, later, its storage from that one declaration.

## Language

### Derivation

**Form**:
A model's HTML presentation and decoding: how its fields become inputs on a page, and how a
submitted body is read back into an instance. It knows nothing about paths, verbs, redirects or
storage, and a model with no key field can still have one.
_Avoid_: view, codec, decoder

**Resource**:
The seven CRUD routes a model mounts: index, new, create, show, edit, update and destroy. It owns
everything a Form refuses to know, the path computed from the class name, the verb of each page,
the store calls, the redirects and the 404 for a missing row, and requires an `id` key because
`create` has to mint one before inserting.
_Avoid_: controller, CRUD, scaffold

**Action**:
One of the seven route roles a Resource can mount. A model subtracts from the full set in its
companion, and a handwritten route may take over the role it subtracted.
_Avoid_: verb, endpoint, operation

**Field**:
How one Scala type reads from and shows as a single form input: its input type, its rendered text,
its parsed value and what an absent submission means for it.
_Avoid_: column, property, attribute

### How Form and Resource relate

Form is what a handler calls. Resource is what writes handlers, and every handler it writes calls
Form. Three shapes follow.

A model that derives only Form is used from handwritten routes: the handler renders the form and
decodes the submission itself, and chooses its own path, verb and outcome. A login form is the
typical case, and it has no key.

A model that derives both mounts seven routes with no handwritten code, each doing exactly what
the handwritten handler above would have done.

A model that derives both and subtracts an Action keeps the remaining routes derived while a
handwritten route serves the subtracted one, still through Form, so the page that emitted the
inputs and the handler that reads them cannot disagree.

Merging the two would break the first shape, since a model with no key cannot mount seven routes,
and would leave the third shape with no Form for the handwritten half to call.

### Application

**Edge**:
One of the two sides of an application that eezo brings up and takes down for it: the database
edge, which is the connection to the database and the schema commands, and the http edge, which
is the server and the routes. An application declares which edges it has, one or both, and a
derivation belongs to one edge: Table to the database edge, Form and Resource to the http edge.
Deriving for an edge the application does not have is a compile error.
_Avoid_: half, side, layer, backend, runtime

**Layout**:
The one frame every HTML reply comes back in, the derived pages, the login page and the error page
included. A trait with one method, from the request, the lifted title and the content to the
document. An application writes one and names it in Main. A route returns its content with a title
element beside it; the title is lifted out and handed to the layout, which builds the document
around the rest. A route that returns a whole document steps outside it.
_Avoid_: template, shell, envelope, wrapper, master page

### Session

**Session**:
What a browser carries between requests: a few named values the application keeps for that one
browser, signed so the browser can hold them but not alter them, and gone when the browser closes.
Who is signed in lives here, and the guard reads it from here.
_Avoid_: server session, session store, session id, state, cookie

**Flash**:
A value kept for exactly the next request and no other, so the page a redirect lands on can say
what the request before it did. Travels inside the session.
_Avoid_: notice, message, toast, alert, one time value

**Secret**:
The one key an application signs its sessions with, set where the application is deployed and
never written in its code. Without one the application runs on a throwaway that lasts as long as
the process, which is right on a laptop and wrong in production.
_Avoid_: secret key base, signing key, app key, salt

### Authentication

**Guard**:
What identifies the user behind a request, for one way of signing in, and what makes routes
guarded. It reads the session, turns what it finds there into the application's own user, sends
a browser it cannot identify to the login page, and carries that login page with it. One per user
model. eezo-auth ships the password guard. A sign in lasts a fixed time from the moment it was
made, however active the user is; past it the guard no longer knows the browser.
_Avoid_: authenticator, filter, middleware, interceptor, before action, timeout, idle

**Guarded**:
The declaration, made beside a model or a page, of which of its routes require a signed in user
and refuse otherwise. A model says it per Action; a page is guarded or public. It is the concept a
guard fulfils, and any way of signing in can produce one. In an application that has a guard,
every route mounted has said whether it is guarded; silence is an error.
_Avoid_: protected, secured, authenticated route, policy

**Current user**:
Who the guard says is behind this request. A handler behind a guarded route asks for it and gets
it; outside one, asking when there is nobody is an error, not a redirect. That is the handler's
question; the key an owned route reads to find its scope answers nothing when there is nobody, so
a public page may ask it and be told nobody. eezo turns that nothing into the same error at the
two places that cannot go on without somebody, narrowing a store to its owner's rows and filling
the owner of a new row. A request carries the guard's answer as a fact about itself, and a live
socket's upgrade is a request with a current user like any other; what the session spells is only
a claim until the guard has read it.
_Avoid_: principal, subject, identity, context, session user

**Bound page**:
A live page rendered behind a guarded route. It remembers the current user of the request that
rendered it and admits one socket, whose upgrade must name the same current user; a sign in that
has expired or been replaced names nobody and is refused. A page rendered on a public route is
unbound, whoever the visitor was, and anyone holding its id may join, as before guards existed.
Binding is checked when the socket opens and never again while it stays open.
_Avoid_: page owner, authenticated page, page identity, page session

**Password**:
The stored hash of a user's password, one self describing string that names its own algorithm
and cost, and the only form of a password a model carries or a table stores. As a form input it
hashes on decode and never renders its value, so no handler and no page ever holds or shows the
plain text. Changing a password is a flow of its own, not an edit of the user. A credential is
not another word for it: Credentials is the lookup that answers with one, and the hash itself
goes by this name alone.
_Avoid_: digest, encrypted password, password hash, credential

**Plain password**:
What a login form decodes to: the text as typed, alive only until it is hashed or verified, with
no way to be stored.
_Avoid_: raw password, cleartext, secret

**Credentials**:
What an application hands the password guard: for an email, that user's key and their Password,
or nothing when no such user exists. The application says where the hash is kept and stops there;
the guard owns the verify, so the plain text never travels out to be compared.
_Avoid_: authenticate, authenticator, user lookup

**CSRF token**:
The value a form carries and an unsafe request must return, proving the submission came from a
page this application served to this browser and not from a stranger's. One per session, minted
the first time a browser is seen, verified before any handler runs, on every browser route of
every application whether or not anyone can sign in. An API route has none, because no session
takes part in it.
_Avoid_: authenticity token, anti forgery token, form token, nonce

**API route**:
A route called by a program and not by a browser. No session takes part in it, so it carries no
CSRF token, and who may call it is said by its Guarded like on any other route. A page is one
when its handler takes an ApiRequest rather than a Request; nothing else declares it.
_Avoid_: endpoint, webhook route, REST route, stateless route, CSRF exempt route

**Mount**:
The prefix a set of routes is addressed under, and everything that moves with it: the pages, the
redirects between them, and the login page of the guard that protects them. What the servlet world
calls the context root.
_Avoid_: context root, base path, namespace, scope

**Authorization**:
Whether the user holds the role for what they are asking, anywhere in the application. Distinct
from being known (the guard) and from owning a row (ownership). No role exists in eezo today; every
signed in user is equal.
_Avoid_: permission, policy, access control, ACL

**Owner**:
The user a row records as the one who created it, held as that user's key in a field of the model
like any other. Filled once, from the current user, when the row is created, and never taken from a
form.
_Avoid_: creator, user id, foreign key, tenant

**Owned**:
A model that records an owner and says, per Action, which of its routes the owner alone may take.
Being owned requires being guarded, never the reverse. A model is public, guarded or owned.
_Avoid_: scoped model, private model, per user model

**Scope**:
The rows of an owned model that exist for the current user: the ones whose owner they are. What
an owned route reads and writes through, so that a row outside it is simply not there rather than
refused.
_Avoid_: filter, tenant, visibility, where clause

### Development

**Dev loop**:
The build tool's side of developing an application: watching the sources, recompiling on save,
and replacing the running application with a fresh one. A failed compile leaves the previous
application serving.
_Avoid_: hot reload, watch mode, revolver

**Dev server**:
The application started in development: the same routes, plus the drift check at boot, the route
listing, and the reload client on every page.
_Avoid_: dev mode, debug server

**Reload**:
The browser's side of the dev loop: the open tab notices the application was replaced and
refreshes itself, showing the new code without a manual refresh. Whole page, no state kept.
_Avoid_: live reload, livereload, HMR, hot module replacement, LiveView

**Reload client**:
The script every dev server page carries: it watches for the application being replaced and asks
the browser to refresh. Present only on the dev server, never in production.
_Avoid_: dev client, livereload script, reload agent

### Failure

**Refusal**:
A failure the caller is expected to handle: the request cannot be honoured and the client or the
caller is at fault, a missing row, a stale CSRF token, a foreign owner, a body too large. A refusal
is raised where it is detected and travels to the nearest boundary that owns it, which answers it
with its status. It never carries that status itself.
_Avoid_: error, exception, fault, HTTP error

**Defect**:
A failure that means the application is built wrong: a guard asked on an unguarded route, a
session too large for a cookie. Nobody handles a defect where it is raised, because the fix is a
code change; it reaches the boundary and is answered as a server fault, while its message, which
names the mistake, goes to the log and reaches the client only in dev. An environment failure, a
database or a socket gone, is answered the same way. A defect found while the application starts,
such as a resource mounted twice, stops the boot instead and reaches no boundary.
_Avoid_: bug, crash, internal error, panic

**Boundary**:
The place a failure changes shape. A boundary owns the failures it names and lets the rest travel
on. The request boundary is last and names everything, so it answers a refusal with its status,
offers anything else to the application's problems hook, and answers what the hook does not cover
as a server fault. The savepoint boundary names only what its caller declares and
gives it back as a value, so a transaction can carry on. The socket boundary answers a mounted
page once the request is gone: a malformed frame as an error frame, a socket opened from the wrong
origin, by the wrong user or for an unknown or taken page by closing it with a 44xx code, a defect
as a generic error frame and a log line, a dead connection by closing.
_Avoid_: handler, catch, error handler, middleware

### Outbound client

**Content**:
Bytes that know their media type: what a request body is once it has been encoded for the wire. An
application never builds one; it gets one from an encoder such as `Http.form` and hands it to
`post`.
_Avoid_: body, payload, entity
