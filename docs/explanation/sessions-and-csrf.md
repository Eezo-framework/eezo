<!-- draft -->
# Sessions, flash and the CSRF token

What a browser carries between requests, how it is kept honest, and how a form proves it came from this application.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the session: signed values the browser holds but cannot alter, gone when the browser closes
- the flash: a value for exactly the next request
- the secret: `EEZO_SECRET`, the throwaway on a laptop, the per-session secret the dev loop sets, the warning in production
- a session too large for a cookie is a defect
- the CSRF token: one per session, minted on first sight, verified before any handler on unsafe methods; safe methods and the WebSocket upgrade
- `Csrf.hidden` in every form, and why a guard's logout form is built by the guard

## Where the material is

- `CONTEXT.md`, Session and Authentication (CSRF token)
- `modules/http/src/main/scala/io/eezo/http/Session.scala`, `Secret.scala`, `Csrf.scala`, `Cookie.scala`
