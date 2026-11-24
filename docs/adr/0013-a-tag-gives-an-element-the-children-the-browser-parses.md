# A tag gives an element the children the browser parses

**Status:** accepted

The live differ names the node that a patch changes by a path of child indices from the mount anchor. A path is correct only when the children of the tree on the server are the children that the browser parsed from the rendered markup. Three shapes of a tree break this:

* a `Fragment` among the children of an element, because it renders no node of its own, so each index after it is too small;
* two adjacent texts, because the HTML parser makes one text node of them;
* an empty text, because the parser makes no node for it.

Issue #105 settled the rule for the fragment, and the texts follow the same rule.

## Decision

`Tag.apply` is the guard. It splices each `Fragment` child into the children of its element, merges adjacent `Text` children into one, and drops an empty `Text`. An element that a tag builds therefore has the children that the browser parses.

The guard is in `Tag.apply` only. An `Element` built by hand, or a `Fragment` inside a `Fragment`, is outside it. The live differ refuses such a tree and does not repair it, as `docs/explanation/the-live-layer.md` says.

## Considered options

**Keep the tree as the view wrote it, and count DOM nodes with a skipping rule.** The differ on the server and the client in the browser then both skip a `Fragment`, count adjacent texts as one and ignore an empty text. Rejected. Two programs, in two languages, must then apply the same rule in the same way, and a difference sends a patch to the wrong node. The rendered markup is the same in both designs, so the guard costs a user nothing.

## Consequences

A tree that a view builds with tags can go to the differ with no further check, and the cost is paid once, when the element is built.

A `Fragment` is useful where no parent counts its children: at the root of a view, and as the value of `++`.

A void element renders none of its children, so a void element with children also has a tree that is not the DOM. The differ must know which tags are void, and it reads the same list of void tags that rendering reads.
