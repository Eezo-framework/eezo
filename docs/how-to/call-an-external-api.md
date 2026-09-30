<!-- draft -->
# Call an external API from a live component

Do slow work off the page thread and patch the outcome in when it lands.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the `Async` capability by constructor: `Live.mount(request, async => new Callout(async))`
- `async.get` and `async.post`, and the total match over `Reply.Ok`, `Denied`, `Failed`, `Unreachable`
- rendering a loading state now, the outcome later
- starting a fetch at mount from `init`
- the server is where the call runs: `localhost` and port forwards, from the field

## Where the material is

- `docs/tutorials/a-live-page.md` §6 and Troubleshooting
- `examples/blog/src/main/scala/components/Callout.scala`
- `modules/live/src/main/scala/io/eezo/live/Component.scala` (`Async`)
- `modules/http/src/main/scala/io/eezo/http/client/Http.scala`
