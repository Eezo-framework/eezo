# The live wire protocol

What travels between a live page and the server: the anchor's attributes, the frames in both
directions, the patch kinds, the close codes and the limits. `Component`, `Live`, `Topic` and
`Async` are in [the API](/api/io/eezo/live/Live$.html).

## The anchor

`Live.mount` returns two nodes: the anchor `<div>` holding the first render, and
`<script src="/eezo/live.js" defer>`. The anchor's attributes:

| attribute | written by | value |
|---|---|---|
| `data-eezo-page` | the server, at mount | the page id, 32 hex characters |
| `data-eezo-base` | the server, at mount | `/`, rewritten by a mount to its prefix |
| `data-eezo-state` | the client | `connecting`, `connected`, `lost` |
| `data-eezo-dead` | the server, at mount | `capacity`, when the registry was full and this is a static render |

The client drives the first `[data-eezo-page]` it finds and no other.

## Event bindings

| attribute | sent on | payload |
|---|---|---|
| `data-eezo-click` | click, default prevented | `{}` |
| `data-eezo-input` | input, after `data-eezo-debounce` ms of quiet, 300 by default | `{"value": ...}` |
| `data-eezo-change` | change | `{"value": ...}` |
| `data-eezo-submit` | submit, default prevented | every named field, `{name: value, ...}` |

The value of a checkbox or radio is `on` when ticked and empty otherwise; anything else sends
its `value`. A submit payload follows `FormData`: ticked checkboxes as `on`, the selected
option's value, files skipped. Listeners are delegated at the document, so a binding survives
any patch that replaces its element.

`data-eezo-ignore` on an element keeps the differ out of its children. `data-eezo-key` on
every child of a list makes the list keyed.

## Client to server

| kind | fields | when |
|---|---|---|
| `join` | `base` | first, after the socket opens |
| `event` | `name`, `payload` | a binding fired |
| `ping` | | every 30 seconds |
| `failed` | `reasons` | the applier refused patches |

`base` is validated to `/[A-Za-z0-9_./-]*`, at most 200 characters, and normalised; anything
else is ignored and logged. An event's `name` is a non-empty string, its `payload` an object of
at most 64 string entries. Up to 8 `reasons` of up to 500 characters each are kept. A frame
that fails any of this is answered with an `error` frame and the page carries on.

## Server to client

| kind | fields |
|---|---|
| `patches` | `patches`, a list in application order |
| `error` | `message` |
| `pong` | |

A resync is a `patches` frame with one `setChildren` whose `path` is `[]` and `expect` is
`null`: the anchor's whole content replaced. The server sends one on every join, on a mailbox
overflow, and after a `failed` report.

## Patch kinds

Every patch has `op` and `path`, a list of child indices from the anchor, so the component's
root is `[0]`. `expect` is the node name the server believes is at the path, `#text` for text,
and the client refuses a patch whose target disagrees.

| op | fields | effect |
|---|---|---|
| `setText` | `text` | sets a text node's content; `text` is unescaped |
| `setAttr` | `expect`, `name`, `value` | sets one attribute; a bare attribute is the empty string |
| `removeAttr` | `expect`, `name` | removes one attribute |
| `replaceNode` | `expect`, `html` | replaces the node at the path |
| `removeNode` | `expect` | removes the node at the path |
| `appendChildren` | `expect`, `html` | appends after the last child |
| `insertChild` | `expect`, `index`, `html` | inserts at `index`, shifting what follows |
| `moveChild` | `expect`, `from`, `to` | removes child `from`, inserts it at `to` in the shortened list |
| `setChildren` | `expect`, `html` | replaces every child |

Within one positional list a frame holds in-place updates, then removals highest index first,
then one append. Within one keyed list: removals highest index first, then moves, then inserts
ascending, then content patches at final positions.

## Close codes

| code | sent by | meaning | the client then |
|---|---|---|---|
| 1000 | the client | `beforeunload` | nothing; the server frees the page at once |
| 1001 | the server | the server is draining | retries |
| 1011 | the server | a send to this client failed | retries |
| 4403 | the server | origin not allowed, or not the page's user; the reason says which | logs the reason, stays `lost`, never reloads |
| 4404 | the server | unknown page: reaped or restarted | reloads into a fresh page |
| 4409 | the server | the page already has a socket, a duplicated tab | reloads into a fresh page |
| anything else, or no open | | the server is unreachable | retries |

A retry waits `250 × 2^(n-1)` ms, capped at 3000. A connect attempt that neither opens nor
closes within 4 seconds is closed and counted as a retry. A reload is suppressed when the last
one was under 3 seconds ago without a `patches` frame in between, so a cached page can't loop.

## Limits

| limit | value |
|---|---|
| pages per process | 10000; past it, a static render |
| never-connected page | reaped after 30 s |
| disconnected page, the grace window | reaped after 60 s |
| reaper cadence | every 10 s |
| mailbox per page | 256 topic deliveries; past it, dropped and resynced |
| slow event | logged after 10 s; the socket keeps waiting |
| text message | `maxBodySize`, 1 MiB |
| idle timeout | 5 minutes; the client pings every 30 s |
| outgoing frames queued | 64; a send that blocks longer than 10 s closes the connection |
| `Async` work that throws | logged; the state is unchanged |

## Related

[The live layer](../explanation/the-live-layer.md) is the model behind the protocol.
