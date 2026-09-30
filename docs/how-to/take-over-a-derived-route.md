<!-- draft -->
# Take over one of the seven derived routes

Replace a derived page with a handwritten one and keep the other six, or remove an action from a resource altogether.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- a handwritten route on the same method and path wins: `app/posts/Index.scala` beats the derived index, the derived twin is dropped, not shadowed
- subtracting an `Action` in the companion so the resource never mounts it
- the handwritten replacement still calls `Form`: rendering the form and decoding the submission the derived handler would have
- the `overridden` and `shadowed` warnings `eezo routes` prints and what each means
- emit order: most static first, `/posts/new` before `/posts/:id`

## Where the material is

- `modules/http/src/main/scala/io/eezo/http/Route.scala` (`RouteTable.overridden`, `shadowed`)
- `modules/http/src/main/scala/io/eezo/http/Actions.scala`, `Resource.scala`
- `CONTEXT.md`, How Form and Resource relate
