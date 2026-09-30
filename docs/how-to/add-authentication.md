<!-- draft -->
# Add authentication

Put a login page in front of routes, decide per action who may take it, and scope a model's rows to their owner.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `eezo-auth` in `build.sbt` and what naming it turns on
- the `User` model, `Password`, `Credentials`, `Guard.password`
- `Guarded.public` and `guard.required` on a page; `Owned` on a model with `owning` and `except`
- the login, logout routes and `logoutForm`, and the CSRF token they carry
- the first user without a sign up page
- rate limiting `POST /login` at the proxy, and why eezo holds no counter
- sign in lifetime, `EEZO_SECRET`, what a restart does to sessions
- a live page behind a guard: the bound page

## Where the material is

- `docs/tutorials/sign-in.md`
- `examples/blog/README.md`, Signing in and out; Each author edits their own posts
- `modules/auth/src/main/scala/io/eezo/auth/Guard.scala`
