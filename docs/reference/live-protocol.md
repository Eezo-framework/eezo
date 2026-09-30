<!-- draft -->
# The live wire protocol

What travels between a live page and the server: the anchor's attributes, the frames in both directions, the patch kinds, the close codes and the limits.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done. The types and members themselves
> are documented in [the generated API](/api/); this page holds what scaladoc cannot say.

## What to cover

- the anchor: `data-eezo-page`, `data-eezo-base`, `data-eezo-state`, `data-eezo-dead`
- the event bindings as attributes: `data-eezo-click`, `-input`, `-change`, `-submit`, `-debounce`, and the payload each sends
- client messages: join (with the base), emit, ping, patches failed
- server frames: patches, error, pong; the full resync
- patch kinds, one line each
- close codes: 1000, 1011, 4403, 4404, 4409, and what the client does after each
- limits: the registry cap, the grace window, the message cap, the ten second event warning, the reconnect backoff
- `Component`, `Live`, `Topic` and `Async` are in the API: link them

## Where the material is

- `modules/live/src/main/scala/io/eezo/live/Wire.scala`, `Patch.scala`, `PageRegistry.scala`, `Live.scala`
- `modules/live/src/main/resources/io/eezo/live/client.js`, `applier.js`
