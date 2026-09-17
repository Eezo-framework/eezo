# eezo's error exceptions live in `modules/http` and carry no HTTP status

**Status:** accepted

eezo uses exceptions as its failure channel, so a derived `show` can throw `NotFound` and get a 404 without inventing a return type. The set is eight sealed exceptions (`NotFound`, `MethodNotAllowed`, `BadRequest`, `Unauthorized`, `Forbidden`, `PayloadTooLarge`, `NotImplemented`, `InternalServerError`) and it lives in `modules/http`, not in `modules/core`, even though `core`'s own comment in `build.sbt` originally promised to hold the framework's errors. None of them carries a `status` field or a `headers` field: the status is decided by an exhaustive match at the request boundary, where the `Problem` value that becomes the response is built.

## Considered options

**Errors in `core`.** The obvious home, and the one `build.sbt` announced. Rejected because `db` depends on `core` and never on `http`, so putting HTTP vocabulary in `core` is precisely what would let an HTTP status reach domain code. With the set in `http`, a `Store` implementation in `db` cannot throw one even by accident, and the module graph enforces what a convention would only request. The consequence is that `Store[A]` returns `Option` or `Either`, and `derives`, which does depend on `http`, is what turns a `None` into a `NotFound`.

**An open base class carrying its status**, `abstract class EezoException(val status: Int, val headers: Seq[(String, String)])`. Shorter to define, and it makes user extension a one liner. Rejected for the same reason as `core`: a status field on a widely extended base class is the mechanism by which HTTP statuses spread through an application. It also forces seven cases to carry a `headers` member that only 405 uses.

**A `PartialFunction[Throwable, Int]` mapping table in the boundary.** Rejected because a user defined exception cannot register into it, so it needs a fallback anyway and degenerates into the sealed match plus a hook.

## Consequences

Sealing means `modules/auth` cannot add 401 and 403 from its own module, and will widen the set in `http` instead. This is accepted: it is a one line edit in a repository eezo owns, and it keeps every status decision in one exhaustive match. `Forbidden` is that widening, decided on [issue #170](https://github.com/Eezo-framework/eezo/issues/170) and built on [issue #174](https://github.com/Eezo-framework/eezo/issues/174): it carries no status like the rest, the boundary maps it to a 403, and `Csrf.verify` raises it when an unsafe request's form does not return the session's CSRF token. `Unauthorized` is the other half of that widening, built on [issue #175](https://github.com/Eezo-framework/eezo/issues/175): it carries no status either, the boundary maps it to a 401, and the guard in `modules/auth` throws it where a redirect cannot help, on a WebSocket upgrade with no page to send a browser to and on `Guard.current` outside a guarded route. The open point is that this 401 carries no `WWW-Authenticate` header, which RFC 9110 makes mandatory for the schemes it defines: eezo signs in through a form and a session cookie, which is not one of them, and the header would make the browser open its own credential dialog over the page.

Users are not blocked from statuses eezo does not model. `Response.status(code)` returns one as a value, which is the better tool inside a handler, and `Eezo.run(..., problems: PartialFunction[Throwable, Problem])` maps an exception thrown deeper in the call stack. That hook is tried after eezo's own set and before the fallback to 500, so extension sits at the boundary alongside the status itself rather than on the exception.

Recorded in full on [issue #104](https://github.com/Eezo-framework/eezo/issues/104).
