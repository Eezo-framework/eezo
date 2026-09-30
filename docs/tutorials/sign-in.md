<!-- draft -->
# Adding sign in

Give the application a `User`, a password guard, and the declarations that put its editing screens behind a login page, with each row owned by whoever wrote it.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- Starting point: the model tutorial's application, `eezo-auth` added beside the umbrella and what naming it turns on
- The user
  - `case class User(id: Id[User], email: String, password: Password) derives Table`, and why no `Form` or `Resource`
  - `Password` as the only form a password takes; `Password.hash` as the one door
  - the guard: `val guard = Guard.password[User](credentials, ...)`, what `Credentials` answers and what it never sees
- The first user without a sign up page
  - a second `DbApp` in the project, `CreateUser`, reading the email and password from the environment
  - why the password is typed at a prompt and never on the command line
- Declaring who may reach what
  - `given Guarded[Index.type] = Guarded.public` on a handwritten page
  - `given Owned[Post, User] = User.guard.required[Post].owning(_.author).except(Action.Index, Action.Show)` on a model
  - the compile error for a route that says nothing, and why silence is an error
  - the login and logout routes the declaration mounts with it
- Try it
  - visit a guarded page signed out: the 303, the login page, landing where you were going
  - two users, each editing their own posts: 403 on a foreign row, 404 on a missing one
  - `logoutForm` on the public page, the CSRF token it carries
- What a sign in is and how long it lasts; the `EEZO_SECRET` in production
- Where to go next: the guards explanation, the auth reference, the live tutorial's bound page

## Where the material is

- `examples/blog`: `models/User.scala`, `models/Post.scala`, `CreateUser.scala`, `app/Index.scala`, and its README
- `modules/auth/src/main/scala/io/eezo/auth/Guard.scala`, `Password.scala`, `Owning.scala`
- `modules/http/src/main/scala/io/eezo/http/Guarded.scala`, `Owned.scala`, `Ownership.scala`
- `CONTEXT.md`, the Authentication section
