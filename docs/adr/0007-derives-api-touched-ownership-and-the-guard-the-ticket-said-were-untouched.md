# derives Api touched Ownership and the guard, which its ticket said were untouched

**Status:** accepted

Issue #311 is the build ticket for `derives Api`, the five JSON routes a model mounts under `/api/<plural>`. Its constraints say "Ownership code is untouched: the five read the named user the way the seven do", and it never names the auth module. Its own acceptance criteria cannot be met under that letter: an owned model listed through a Bearer token answers only the token holder's rows, and a create through a token records the token holder as owner whatever the body says. Two pieces landed outside what the ticket named. This ADR names each one, what the ticket said, what landed, and records that the decision is to keep both.

## Ownership takes field names and gained `owner`

#311 says Ownership is untouched. On `dev`, `Ownership`'s constructor takes the model's `Form[A]`, reads it for the boot check that the owner field exists, derives `shape` from it, and fills the owner in `body` by rewriting `request.form` before `Form.parse` reads it. An `Api` has no `Form`, since its derivation asks for a `Mirror`, a `Serde` and `Actions` alone, and its body is JSON that the `Serde` reads whole into a row, so a form rewrite has nothing to rewrite.

What landed in `modules/http/src/main/scala/io/eezo/http/Ownership.scala`: the constructor takes `fields: Seq[String]` in place of `declared: Form[A]`, and the owner check reads those names; `shape` became `shape(declared: Form[A])`, asked by `Resource`, the one caller that has a Form; and `owner(request, existing)` returns the owner field's name and the key a write puts in it, by the same rule `body` follows, which `body` now calls and which `Api` uses to set the field on the row the `Serde` read. The rule itself, the requester on a covered write and the row's own owner on an uncovered update, is unchanged, and `storeFor`, `missing`, `keeping` and `owns` are untouched. Without `owner`, a JSON body naming another user as owner would be stored as sent, because neither `OwnerOf`, the `Serde` nor any store can put a value into the owner field. Kept.

Amended by [issue #328](https://github.com/Eezo-framework/eezo/issues/328): the `Serde` now fills the owner. `Api` hands it every field eezo decides on a write, the key under `id` and, for an owned model, the name and key `owner(request, existing)` returns, so a body need not name an owner and a program sends no placeholder. Setting the key and the owner again on the row the `Serde` read stays, because the `Serde` is the application's to write: one that trusted the body over what it was handed would otherwise let a client choose the owner, or the key, of the row it writes.

Amended by [issue #329](https://github.com/Eezo-framework/eezo/issues/329): `Ownership` also exposes `ownerField`, the owner field's name read from the declaration it already holds, so `Api` finds that field's position in the row once at mount instead of looking the name up on every request. The answer still comes from `Ownership`, so the declaration is read in one place and the second reading rejected below is still avoided.

## The guard's current user falls back to the token named user

#311 does not name auth. On `dev`, the `currentUser` every `GuardedBy`, `Owning` and `Owned` receives from a `Guard` reads the session only. An API route has no session: the server reads no cookie for it and dispatch empties the session before the handler runs. The token arm of `Guard.through` names the caller on `request.currentUser`, but nothing an `Owned` reaches read that field, so a covered route of an owned model threw on every request that a valid token had already let through.

What landed in `modules/auth/src/main/scala/io/eezo/auth/Guard.scala`: `currentUserKey` reads the session first and falls back to `request.currentUser`, parsed back into `Id[U]` with the guard's own `parse`. It reads nothing else. On an API route that field is written only by the token arm, because the session is empty and no header, query or path parameter ever becomes it, so a browser calling an API URL with its cookie is still nobody and the cookie path dispatch closes stays closed. On a browser route the session answers first, as it always did. Auth is the only layer that holds both halves, the token verdict and the declaration's reader, and `http` must not learn about tokens, so the fallback lives there. Kept.

## Considered options

**Build a second ownership class for `Api`.** Rejected. It would ask the declaration the same three questions in a second place, and the point of `Ownership` is that every handler reads one answer; the anti forgery rule would then exist twice and could drift.

**Read the session on API routes so the existing `currentUserKey` finds a user.** Rejected. A browser holding a cookie would then act as its user on an API URL, the exact path dispatch empties the session to prevent.

## Consequences

#311's "Ownership code is untouched" is superseded by this ADR for the constructor, `shape` and `owner`; the sentence still holds for the ownership rule itself. A later reader of `currentUserKey` who wonders why a guard consults `request.currentUser` finds the reason in its scaladoc and here.

Recorded against [issue #311](https://github.com/Eezo-framework/eezo/issues/311) and [map #307](https://github.com/Eezo-framework/eezo/issues/307).
