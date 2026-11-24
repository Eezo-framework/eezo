# A tag takes a union of argument types, not a Modifier trait

**Status:** accepted

A view calls a tag with attributes, a key, nodes, text, numbers and lists of these, in one argument list: `div(Attrs.cls := "box", "hello", 42, items.map(s => li(key(s), s)))`. The usual Scala shape for this is a `Modifier` trait with a `given Conversion` from each type that a tag accepts. The modules of eezo build with `-feature -Werror`. Settled on issue #105.

## Decision

A tag takes `Mod*`. `Mod` is a union of the types that a tag accepts, and an `Iterable` of them. There is no `Modifier` trait and no conversion.

## Considered options

**A `Modifier` trait with a `given Conversion` for each type.** Rejected. Scala 3 reports a feature warning at each site that uses a conversion, unless the file imports `scala.language.implicitConversions`. Under `-Werror` that is an error in the modules of eezo, and in an application each view file must start with the language import. ADR 0004 rejects a conversion for `Url` for the same compiler behaviour.

## Consequences

A user cannot add an argument type of their own. That is recoverable: a wider union parameter is source and binary compatible, so `| Modifier` can come on the day that somebody needs it.

The element type of an `Iterable` is erased at runtime, so only the union, at compile time, refuses a list of a type that a tag does not accept.
