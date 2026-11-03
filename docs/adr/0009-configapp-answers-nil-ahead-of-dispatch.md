# ConfigApp answers `Nil` ahead of Dispatch

**Status:** accepted

[ADR 0005](0005-the-entry-traits-landed-five-things-their-decision-tickets-had-settled-otherwise.md) records that `Dispatch` "owns the one `case Nil => program(); 0`". Issue #365 adds a second `Nil` arm. `ConfigApp`, a trait in `modules/config` that `HttpApp` and `DbApp` both extend, answers `Nil` with `configured(Nil)(program())`, and its arms sit between each layer's own arms and `Dispatch`'s. This ADR amends that sentence of 0005: `Dispatch` still owns the `Nil` arm a bare `Dispatch` answers, and `ConfigApp` answers `Nil` first for every entry trait.

The reason is the exit code. When the build names a Config class, boot reads `application.conf` against the environment before the application starts, and a refused read must exit 1 with one `[eezo]` line per offender, no stack trace and no port bound. `program()` returns `Unit`, so a refusal made inside it can end the process only by throwing or by calling `sys.exit`. A throwable other than `SchemaError` escapes `DbApp`'s guard, `run` and `main` with a trace, and `sys.exit` inside the program makes `run(Nil)` impossible to assert in a suite. An arm returns an `Int`, so the refusal is a returned 1 that passes `DbApp`'s guard unchanged. `Dispatch` cannot hold the arm itself, because `core` cannot see `modules/config`.

The arm calls `program()` and does not override it. The stacking refusal of 0005 therefore still holds: `HttpApp` and `DbApp` each implement `program` without `override`, and a trait that stacks the two by hand is still refused by the compiler until it says which program it means. Because both layers extend the one `ConfigApp`, linearization gives `EezoApp` a single copy, so the read runs once and `help` lists `config` once. The read runs before `program()`, so on `DbApp` and `EezoApp` a refused read builds no `Database` and runs no drift check. The `dev` arm takes the same path through `configured`.

## Considered options

**Read the Config inside `program`, and throw on a refusal.** Rejected. Only `SchemaError` leaves `DbApp`'s guard as one clean line, and it lives in `modules/db`, which `http` and `config` cannot see. An `HttpApp` application has no guard at all, so the refusal would print a stack trace.

**Read the Config inside `program`, and call `sys.exit(1)` there.** Rejected. Suites drive `Dispatch.run`, which must return the code and must not end the JVM.

**Make `program` return an `Int`.** Rejected. It changes the hook every layer and every user's `DbApp` implements, for a concern only the configuration read has.

## Consequences

The `Nil` path now runs through two arms, and `Dispatch.commands`' scaladoc names the one ahead of it. A layer that wants to run something before the program must still go through `program` or through `ConfigApp`, never through a third `Nil` arm, or the read may run after it.
