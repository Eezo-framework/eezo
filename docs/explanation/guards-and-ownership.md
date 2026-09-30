<!-- draft -->
# Guards, guarded routes and owned rows

The three questions kept apart: is somebody there, may they take this action, and are these rows theirs.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the guard: one way of signing in, one per user model, carrying its own login page
- `Guarded`: the declaration, per action or per page; silence as an error once a guard exists
- current user: a fact about the request, and the two places a missing one becomes an error
- owner, owned, scope: rows outside the scope are not there rather than refused
- what `eezo-auth` deliberately does not depend on, and where `Password` meets storage
- equal cost on a wrong password, and why throttling belongs at the proxy
- authorization by role: what does not exist yet
- the bound live page: checked at the upgrade, never again

## Where the material is

- `CONTEXT.md`, Authentication
- `modules/auth/src/main/scala/io/eezo/auth/Guard.scala`, `Owning.scala`; `modules/http/.../Guarded.scala`, `Owned.scala`, `Ownership.scala`, `Scoped.scala`
- `examples/blog/README.md`
