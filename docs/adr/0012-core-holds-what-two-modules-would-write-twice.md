# Core holds what two modules would write twice

**Status:** accepted

`http` and `db` both depend on `modules/core`, and they never depend on each other. `db` must not see an HTTP status (ADR 0001), and an `http` application must run with no database. But the two layers need some of the same code: the key of a model, the reading of a text into a value, one camel case rule for names, a JSON writer and reader, and the entry point of an application.

## Decision

A piece of code goes into `core` when two modules that cannot see each other both need it, and it needs nothing of its own. `core` has no library dependencies. A type in `core` knows neither layer. When a layer needs an instance of its own typeclass for a type of `core`, that instance is a given in the layer: `db` gives `Id` its `Column`, and `http` gives it its `Field`.

## Considered options

**Each module writes its own copy.** Rejected. Two camel case rules, two JSON escapes or two readings of a number disagree on the day that one of them changes. The difference shows only in an application that has both layers, and no suite of one layer can see it.

**One layer holds the code, and the other depends on that layer.** Rejected. Then `db` sees `http`, or `http` sees `db`, which is the dependency this module graph exists to prevent.

**A library as a dependency of `core`, such as a JSON library.** Rejected. Every module depends on `core`, so a library of `core` arrives on the classpath of every module and of every application. The JSON writer and reader of `core` are small because they serve only what eezo itself writes and reads.

## Consequences

A new member of `core` must pass the rule. If only one module needs it, it goes to that module.

`Dispatch` holds only what both entry layers would write twice: `main`, the answer to an unknown command, `help` over the rows of every layer, and the `--json` flag with its one error shape. It starts and stops nothing, because a server and a database belong to the layers.

The JSON value of `core` stays the writer and reader that eezo needs for its own output and its own wire. `derives Api` is not built on it, and will not be.
