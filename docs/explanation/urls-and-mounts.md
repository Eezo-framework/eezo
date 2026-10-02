# URLs as values, and what a mount moves

This page is about one failure and the type that prevents it. If you've mounted a set of routes
under a prefix and wondered why the links kept working, this is why.

## The failure

Say your blog's seven derived routes serve at `/posts`, and you decide the editing screens
belong behind `/admin`. Moving the routes is one line:

```scala
Route.under("/admin")(derived)
```

Now `GET /admin/posts` works. But the index page was rendered by code that knows nothing about
`/admin`, so every link on it still says `/posts/<id>`, the new-post form still posts to
`/posts`, and a successful create still redirects to `/posts/<id>`. Three kinds of 404, all
from a string that was finished before the prefix existed. A string in an `href` can't be moved
because nothing can tell whether it was meant to be.

## Two kinds of address

eezo makes the difference a type:

```scala
enum Url {
  case Absolute(path: String)   // finished; eezo never touches it
  case Mounted(path: String)    // this application's own; travels with its routes
}
```

A `Url.Mounted("/posts")` is an address inside this application, and it takes whatever prefix
its routes are mounted under. A `Url.Absolute` is an address eezo has no business moving:
another site, a `mailto:`, or a path you mean literally. A plain `String` in an `href` or a
`Location` header means the same as `Absolute`. If you wrote it out, it's finished.

Every link a derived page renders is `Mounted`. The collection path, the member path, the edit
link, the delete form's action, the redirect after a write: all of them are `Url.Mounted`
values, built with no prefix, because derivation has no prefix to bake in. Mounting is what
puts one on.

## Which attributes can carry one

Only three attribute names take a `Url`: `href`, `src` and `action`. (A fourth, `data-eezo-base`,
is the live layer's and you never write it.) The list is closed. `Attrs.attr("href")`
gives you an ordinary attribute, and a value you put in it is a string a mount won't rewrite,
because which names a mount is allowed to change is one decision made in one place, and a
string handed in at a call site shouldn't be able to reopen it.

## What `Route.under` does

Two things, and the second is the one that matters:

- **The pattern moves.** `GET /posts/:id` becomes `GET /admin/posts/:id`. The prefix is
  normalised first, so `/admin`, `admin` and `/admin/` are one prefix.
- **The handler is wrapped.** The response it returns goes through `.under("/admin")` on its
  way out. Every `Url.Mounted` in the page takes the prefix, and so does every header whose
  value is a `Url`, which is how a `Location` of `Mounted("/posts/<id>")` becomes
  `/admin/posts/<id>`. Strings and `Absolute` values are left exactly as written.

The handler is wrapped, and never told its prefix, so nothing you write has to know it's
mounted. And the result is still made of `Mounted` values, which is what lets a second `under`
move it again: there's no third case meaning "resolved", because the next layer would have to
undo it.

A live page gets the same treatment once, on the way out, and then its socket takes over. So
the anchor carries a `Mounted("/")` marker, the client reads what the mount rewrote it to, and
reports it when the socket joins. Every later re-render on the server is rebased to that same
prefix before it's diffed.

## Pointing in from outside

The blog's front page lives at `/` and is not mounted. Its link to the posts has to name the
prefix, because no wrapper will:

```scala
p(a(Attrs.href := "/admin/posts", "All posts"))
```

A `Url.Mounted("/posts")` here would render as `/posts`, which 404s. The page outside the mount
is the one that knows where the mount is, so it writes the finished address. The same page
puts the guard's sign-out form on an `Url.Absolute("/admin/logout")`, for the same reason:
inside the mount, the guard's default `Mounted("/logout")` would do, and outside it, nothing
rewrites it.

That's the whole rule. Inside a mount, write `Mounted` and never spell the prefix. Outside it,
write the address you mean.

## Where to go next

The response that `under` walks, and the request on the other side of the handler, are
[requests and responses](requests-and-responses.md). A mount also carries the guard's login
page with it, which is in [guards and ownership](guards-and-ownership.md).
