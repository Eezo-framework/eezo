<!-- draft -->
# The live layer

State on the server, a thin browser, one socket: what travels, how the tree is diffed, and what happens when the connection goes.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the model: `init`, `handle`, `render`; one thread per page; state as any immutable value
- the differ: canonical trees, keyed children, `Raw` markup, why some nestings are refused at mount
- patches on the wire, coalescing, the full resync as the repair
- the page registry: pages, the reaper, the cap and the static render past it
- connection loss: the grace window, rejoining the same page, the reload after it, the duplicated tab
- origins: what the socket admits and why, proxies, `allowedOrigins`
- bound pages and the 44xx close codes
- topics and async: two ways state moves without a click
- an event still running after ten seconds
- what is not there: multi-mount, client hooks, a JS API

## Where the material is

- `docs/tutorials/a-live-page.md`
- `modules/live/src/main/scala/io/eezo/live/*`
- `research/build-reload.md` for the reload client, which is not the live client
