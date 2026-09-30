<!-- draft -->
# Add a live component

Embed server-held, self-updating state in an ordinary page with no JavaScript of your own.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the dependency: `EezoApp` already carries live; `eezo-http` alone opts in with `extends LiveApp`
- the three methods: `init`, `handle`, `render`, and the rules each is held to
- `Live.mount(request, component)` inside a handler's own document
- bindings: `onClick`, `onInput` with its debounce, `onChange`, `onSubmit`, and the payload each sends
- a form inside a component: echoing values while typing, `Live.ignore` for a subtree another script owns
- canonical trees: the errors the differ raises at mount and how to restructure
- one mount per response, and what a second one does
- reading the protocol in DevTools

## Where the material is

- `docs/tutorials/a-live-page.md` §2, §3, §5
- `examples/blog/src/main/scala/components/Counter.scala`, `Signup.scala`, `Board.scala`
- `site/src/main/scala/site/Drawer.scala`, the docs site's own drawer
- `modules/live/src/main/scala/io/eezo/live/Live.scala`, `Component.scala`, `Canonical.scala`
