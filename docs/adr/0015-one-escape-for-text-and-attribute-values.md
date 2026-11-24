# One escape for text and attribute values

**Status:** accepted

Rendered text and rendered attribute values need different escapes. Text needs `<` and `&` escaped. An attribute value in double quotes needs `&` and `"` escaped. Escaping is a security boundary, not a format detail (issue #72). Settled on issue #105.

## Decision

One function, `Html.escape`, escapes the same five characters, `<`, `>`, `&`, `"` and `'`, in text and in every attribute value. A mounted url in an attribute goes through the same function.

## Considered options

**An escape for each context.** Text escapes only `<` and `&`, and an attribute value only `&` and `"`. Rejected. A rule split by context is a rule that one day is applied in the wrong context, and that mistake is an injection. Escaping too much has no effect in a browser, so the one function costs nothing.

## Consequences

A reviewer checks one function and one list of five entities. The inverse of the escape, for the readers of escaped text outside rendering, decodes the same five entities.
