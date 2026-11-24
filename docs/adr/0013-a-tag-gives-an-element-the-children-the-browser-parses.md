# The HTML tree is closed, escapes in one place, and keeps its children equal to the DOM

**Status:** accepted

`io.eezo.core.html` is the node tree every page is built from, the tags and attribute names a view writes, and the `Url` a page emits. ADR 0004 records why an emitted URL is a value with two cases. This ADR records the other decisions that bind the tree. Until now the scaladoc of these types carried them. Public scaladoc now tells a user how to use a member, so the decisions are recorded here.

## Four cases, and constructors that only the package can call

`Html` has four cases: `Text`, `Raw`, `Element` and `Fragment`. There is no `Comment` case, because nothing eezo renders emits one and `Html.raw` covers it if something ever does. There is no `Doctype` case, because `Html.doctype` is a `Raw` that a page puts first. A case that is legal at one position of the tree only is a case that every `match` in `modules/live` must handle and then ignore.

The constructors of `Html.Text`, `Attr` and `AttrValue.Link` are private to the package, and their pattern matches are public, because the differ in `modules/live` matches on them. So `Html.text` is the one way to build escaped text from outside, and `:=` on a `UrlAttrName` is the one way to build a `Link`. A public constructor of `Attr` or `Link` would let a call site pair a name of its own with a `Link`, or lift a `Link` out of an `href` it had just built, and so make a url bearing name that the closed list of ADR 0004 does not hold. For the same reason `UrlAttrName` has a private constructor and `Attrs` is the one list of url bearing names, and `AttrName` is `sealed` rather than `final`: `UrlAttrName` adds the one arm the url bearing names need, and no view file can add a fourth kind of attribute name. `Attrs.eezoBase`, `data-eezo-base`, is a fourth url bearing name that belongs to the framework, so it is `private[eezo]` and made in `Attrs`, the one place that may make one. It carries `Url.Mounted("/")`, a mount moves it with the page, and the live client reports the moved value when it joins, as `docs/reference/live-protocol.md` says.

## Tree children are DOM children

The live differ addresses a node by a path of child indices, so the children of a tree must be the children the browser parses. `Tag.apply` is the guard. It splices a `Fragment` child into the children of its element, because a fragment renders no node of its own and every index after it would run ahead of the DOM. It merges adjacent `Text` children and drops empty ones, because the HTML parser knows no border between two texts and makes no node for an empty one. Merging the escaped forms is sound because escaping works character by character, so the escape of a concatenation is the concatenation of the escapes. The guard is the one of `Tag.apply` alone: an `Element` built by hand is outside it, and the differ refuses a tree that is not canonical rather than repairing it, as `docs/explanation/the-live-layer.md` says.

Whether an element is void is a property of its tag name, looked up at render time, so a void element with a flag that says otherwise cannot exist. The list, `Html.VoidTags`, is `private[eezo]`, because the live differ must know it too and a public `Set[String]` would be an API promise that no user asks for. A void tag renders `<br>`, not `<br/>`, because the XHTML spelling is not what an HTML parser reads.

The `key` of a list child is a field of `Element` and has its own type, `Key`, and is not an attribute whose name somebody intercepts. The live differ needs the key to tell rows apart, and a requirement that one module places on another belongs in a type, not in a string two modules agree on.

## One escape for text and attribute values

`Html.escape` escapes the same five characters in text and in an attribute value. Text needs only `<` and `&`, an attribute value only `&` and `"`, but escaping too much does nothing in a browser, one function is one thing a reviewer checks, and a rule split by context is a rule that one day is applied in the wrong context. Every attribute value, a `Link` included, goes through it, because `AttrValue.text` is the one spelling of a value.

`Html.unescape` is its inverse, for the two readers of escaped text outside rendering: the live differ sends a changed text unescaped, because the client sets it as literal data, and the layout of `modules/http` gives the text of a lifted title to a `title` tag that escapes it again. It sits beside `escape` because the five entities are the choice of this file, and an inverse kept elsewhere drifts. It decodes `&amp;` last because `escape` encodes it first.

## The shape a view file writes

`Mod`, what a tag accepts, is a union and not a `Modifier` trait with a `given Conversion`. Under the `-feature -Werror` of this build a conversion is an error at every use site unless the file imports `scala.language.implicitConversions`, and that import in every view file is the opposite of effortless. A user cannot add an arm today. That is recoverable: a wider union parameter is source and binary compatible, so `| Modifier` can come on the day somebody needs it.

The tags are imported with a wildcard and the attribute names qualified, because a view file names many more tags than attribute names, and the two collide on `title`, `style`, `label`, `form`, `value`, `name`, `data` and `span`. The three Scala keywords are spelled `cls`, `tpe` and `htmlFor` and not with backticks, because a backticked `for` would sit on every label of every form, and forms are the markup the framework writes most. The tags cover what the seven derived routes render: document, structure, text, lists, forms, media and tables.

A repeated attribute name keeps its last value, `class` included, because a merge rule for one attribute is a second model to learn. There is no `Html.unless`, because `when(!cond)` is the same length and one concept.

## Mounting a tree

`Html.under`, `Html.transform`, `Html.renderTo`, `Attr.under` and `AttrValue.text` are `private[eezo]`. `Response.under` in `modules/http` is the one caller of `Html.under`, because a mount is a property of where routes are served, not of a view. `transform` rebuilds a tree from the top down and gives back the same object for a node it leaves alone, so a walk that moves one url builds only the nodes above it. It compares by reference, because a `Vector` map that changes nothing still builds a new `Vector`. `renderTo` writes into a builder, so that a streaming body can come later without a second public renderer.

`Url.normalise` is public because a mount prefix is the same idea: `Route.under` normalises its prefix with it, so the path a route answers on and the path its links point at cannot spell one prefix two ways. `Url.under` builds its result through `Url.Mounted.apply`, so the result is normalised as any other `Mounted` is.

## Considered options

**A `Comment` or a `Doctype` case.** Rejected for the reason above: a case legal in one position is a case every walk must skip.

**A `Modifier` trait with a `given Conversion`.** Rejected for the reason above. ADR 0004 rejects a conversion for `Url` for the same compiler behaviour.

**An escape for each context.** Rejected for the reason above.

**The key as an attribute named `data-eezo-key`.** Rejected: an attribute that one module intercepts by name is a convention, not a type.

## Consequences

The scaladoc of the html package now says how to use its members. A reader who wants to know why the tree has its shape finds it here and in ADR 0004. The tests in `MountingSuite`, `EscapingSuite`, `RenderingSuite` and `DslSuite` pin the rules above.
