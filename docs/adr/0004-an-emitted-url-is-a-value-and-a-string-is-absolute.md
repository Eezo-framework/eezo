# An emitted URL is a value, and a plain `String` means absolute

**Status:** accepted

A URL that a page or a redirect emits is an `io.eezo.core.html.Url`, an enum with two cases. `Url.Mounted` is an address the application owns, so `Route.under` prepends its prefix to it and the value stays `Mounted`, which is what makes nested mounts compose. `Url.Absolute` is a finished address that nothing rewrites. A plain `String` written at a call site means `Url.Absolute`, so a handwritten link is the author's own path and a mount never relocates it.

Only `Mounted` normalises its payload, so `Mounted("posts")` and `Mounted("/posts")` are one value. `Absolute` keeps its payload verbatim, because `https://example.com/` and `mailto:someone@example.com` are legitimate values that trimming would corrupt.

Three mechanisms carry this one rule, which is the price of expressing it in Scala 3 without taxing the user. `UrlAttrName` is a subtype of the attribute name, and its `:=` is the only one that accepts a `Url`, which is what makes `Attrs.cls := someUrl` a compile error. `Response.Redirect` is overloaded on `Url` and on `String`. `Form.render` and `Response.headers` take the union `Url | String`, because a method with default arguments cannot be overloaded. One function, `Response.asUrl`, is the single place that discriminates the union.

## Considered options

**A single `Url` type everywhere plus `given Conversion[String, Url]`.** The design that collapses all three mechanisms into one, keeps `Response.Redirect("/posts")` compiling, and removes every runtime type test. Rejected on evidence rather than on taste. Scala 3 emits a feature warning at the site where a conversion is inserted, not where it is defined, so putting the given in `Url`'s companion object solves finding it and not warning about it. Inside `modules/`, where the build runs `-Werror`, that is a build failure. In a user's own project it is a warning whose documented cure is `import scala.language.implicitConversions`, which switches the feature on for all of their code rather than for eezo's URLs. Scala 3 has the feature designed for this, the `into` parameter modifier, and `-language:help` on 3.8.4 lists it under `experimental.into`, but the parser rejects it in every spelling, with `-experimental` and `-source:future` both set. The clean version of this design is not reachable on this toolchain.

**A single `Url` type with no conversion, so call sites write `Url.Absolute("/posts")`.** Reaches `Seq[(String, Url)]` and one `Redirect` with no implicit machinery. Rejected because it breaks the commonest thing a user writes, and because `Response.headers` then has to dress a header like `Allow: GET, PUT` as a URL, which is a lie in the type.

**Rewriting URLs inside `Html.Raw`.** Rejected permanently: reaching into raw content means parsing HTML. `Raw` is opaque to mounting and its scaladoc says so.

**Making a handwritten `String` travel with its mount.** Rejected because it is the bargain that keeps eezo from silently relocating a deliberate link to an address outside the mount. A page outside a mount that wants to point into one writes the prefix itself, which is what `examples/blog` does.

## Consequences

A page under `app/` and a derived resource both emit URLs in ignorance of any prefix, so nothing a user writes has to know it is mounted. `Route.under` is the one place that resolves them, plus one case in the renderer that flattens an unresolved `Mounted` to its bare payload, which is why an unmounted resource renders exactly the bytes it rendered before mounting existed.

The union survives in the public `Response.headers`, so a reader of a header handles both spellings. This is accepted for now, and the option above is what to reconsider if `into` ever parses.

A mount moves exactly the routes it is given. Mounting a table wholesale moves the handwritten root with it and leaves `GET /` unanswered, which is why `examples/blog` partitions on `Provenance` and mounts only the derived half.

Recorded in full on [issue #140](https://github.com/Eezo-framework/eezo/issues/140).
