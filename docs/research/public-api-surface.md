# The public API surface: which public names an application writes

Research for ticket [#263](https://github.com/Eezo-framework/eezo/issues/263), which follows [#211](https://github.com/Eezo-framework/eezo/issues/211), part of map [#193](https://github.com/Eezo-framework/eezo/issues/193). Checked on 2026-09-28 against `main` at `756d1ed`, Scala 3.8.4. Every claim about eezo cites a file and a line in the repository; every claim about a tool or another ecosystem links to the source that owns it. This document takes no decision and narrows nothing.

## The question

[#211](https://github.com/Eezo-framework/eezo/issues/211) settled the rule:

> A name is public API only if the application writes it, tests of that application included. Everything else is `private[eezo]` at most, narrower where a single module is the only reader. A package name promises nothing; the modifier does.

It narrowed the names it listed and found the list incomplete. This document sorts the rest: every declaration that is public today in `core`, `http`, `db`, `live`, `auth` and `eezo`, members included, into four kinds.

1. **Written by the application.** Spelled in an example, the README or a document, or plainly meant to be.
2. **Written through inference.** The application holds a value of the type without naming it.
3. **Spliced** by a macro, a given or the route generator into application code.
4. **Nobody's.** No application writes it by any of the three routes. Each comes with its readers and the narrowest modifier they allow.

A fifth list holds the names that fit no kind cleanly. It also answers whether a golden list of the public API is worth having, with facts only.

Not sorted again, because the five issues filed by #211 already narrow them: `core.internal`, `db.internal`, the four `toJson` methods in `db.schema`, `Wire`, `Patch`, `Differ`, `NotCanonical`, both `cli` packages, `Csrf.Token.gen` and `MiB`.

## What counts as an application

An application is code outside the package `io.eezo`. The evidence read for it:

* The four examples under `examples/`: hello, reminders, blog and todo, main and test sources.
* `modules/example`, whose one substantial file is `modules/example/src/test/scala/example/Tour.scala`, a guided run through `db` in package `example`.
* `README.md` and everything under `docs/`.
* The code the route generator writes (`modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala`), which lands in package `io.eezo.generated`. A `private[eezo]` name therefore stays reachable from generated routes.
* The trees that macros, inline methods and givens place at the application's call site.

## Short answer

1. **The surface is larger than the sweep counted.** The crude sweep saw 128 top level names. Sorted by hand, with a class and its companion counted once and top level extension methods counted one each, the six modules hold about 139 top level names and about 690 members.
2. **About a quarter of the members are nobody's.** Around 15 top level names and 186 members have no application route to them. Most narrow to `private[db]`, `private[http]` or `private[eezo]` with no blocker.
3. **About a quarter fit no kind.** Around 16 top level names and 190 members are public, unread by any example or document, and plausibly something an application would reach for. They reduce to the eight questions in the next section.
4. **`Tour.scala` carries most of the `migrate` and `schema` surface of `db`.** It is the only application evidence for 10 kind 1 top level names, 37 kind 1 members and 4 kind 2 types, `SchemaSnap`, `TableSnap` and `ColumnSnap` among them. The `db` section gives the modifier each would take if `Tour` is not counted.
5. **Five of the ticket's examples sort differently than expected.**
   * `PathPattern` is kind 3: the generator emits `io.eezo.http.PathPattern.parse` (`RouteGenerator.scala:409`). Only its members are nobody's.
   * `Snapshot` is kind 4, but its three `Snap` classes are kind 1 and 2 through `Tour`. `IndexSnap` is kind 4 and blocked by public fields that mention it.
   * `Applied` is kind 2 through `Tour`: it is the result type of `Migrator.applied` (`modules/db/src/main/scala/io/eezo/db/migrate/Migrator.scala:32`).
   * `ReentrantScope` fits no kind: it is one of five `db` exceptions an application could catch by name.
   * `Structure` is not spliced. It runs inside the compiler and its only reader is `TableMacro.scala:15`.
6. **Two names the ticket gave as kind 1 are never spelled.** No example, README or document spells eezo's `Cookie`. No application spells `io.eezo.http.client.Http` either: the blog reaches the client through `Async.get` in `live` and imports only `Auth` and `Reply` (`examples/blog/src/main/scala/components/Callout.scala:5`).
7. **`live` is nearly clean.** One member is nobody's, `Live.routes`, and it takes `private[eezo]` because one suite in `modules/eezo` reads it. The module has no macro, no inline method and no given.
8. **No Scala tool pins a public API against a checked in file.** MiMa and TASTy MiMa both need a published artifact, which eezo does not have. A test of about 50 lines over the TASTy inspector read all 71 TASTy files of the capture checked `modules/db` in a local experiment.

## Counts

Counts are by hand, fragment by fragment, and are approximate. The per module sections state how each was counted.

| Module | Kind 1 | Kind 2 | Kind 3 | Kind 4 | Fits no kind |
|---|---|---|---|---|---|
| `core` | 6 / 51 | 6 / 1 | 3 / 0 | 1 / 34 | 1 / 77 |
| `http` | 29 / 98 | 2 / 1 | 6 / 32 | 3 / 58 | 4 / 51 |
| `db` | 27 / 66 | 9 / 1 | 8 / 32 | 10 / 90 | 11 / 55 |
| `live` | 6 / 17 | 1 / 0 | 0 / 0 | 0 / 1 | 0 / 5 |
| `auth` | 2 / 16 | 2 / 0 | 0 / 2 | 1 / 3 | 0 / 2 |
| `eezo` | 1 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Total | 71 / 248 | 20 / 3 | 17 / 66 | 15 / 186 | 16 / 190 |

Each cell reads top level names, then members. `core` also has 63 package level forwarders from `export Tags.*`, 26 of them kind 1 and 37 in no kind, which the table leaves out.

## Two rows moved after the fragments were written

The per module sections below are the fragments as written. Two groups of rows are read differently here, and the counts above do not reflect the move.

* **`Store.all`, `Store.find`, `Store.insert`, `Store.update`, `Store.delete`** are listed under kind 4 of `core` with `private[eezo]`. The facts in the row stand: no example calls or implements any of the five, and the generator spells only the type `io.eezo.core.Store`. The modifier does more than narrow, though. `Store` is a public trait and the five are its abstract members, so narrowing them makes it impossible for an application to write its own `Store`. That is a decision, and the row belongs with the names that fit no kind.
* **`OwnedStore.by`** is in the same position, for the same reason.

## What goes back to Riccardo

The names that fit no kind are listed in full, with their facts, under "Fits no kind cleanly" in each module section. They reduce to eight questions.

1. **Does `Tour.scala` count as an application?** It sits in package `example`, extends `DbApp`, and is run by `sbt "example/Test/runMain example.Tour"`. If it counts, most of `migrate` and `schema` is kind 1. If it does not, most of it narrows to `private[db]`, and `Change`, `Decision`, `SchemaSnap` and `Schema.snapshot` to `private[eezo]` because `DriftGate` reads them.
2. **May an application write its own `Store`?** This covers the five `Store` methods, `OwnedStore.by`, and the constructor of `Guarded`, whose scaladoc says any other way of signing in "has to produce one of these" (`modules/http/src/main/scala/io/eezo/http/Guarded.scala:8`).
3. **Does a vocabulary stay public as a whole when the examples use part of it?** The families: 37 html tags and 30 attributes in `core` that no example uses, 18 and 22 of which have no reader anywhere; the `Col` operators `<>`, `<`, `>`, `>=`, `in`, `desc`; `Expr` `and`, `or`, `unary_!`; `Query` `limit` and `offset`; four `Check` cases; eleven `PgType` cases; twelve `Change` cases; `Http.put` and `Http.delete`; `Async.post`; `Auth.basic`.
4. **May an application catch a `db` exception by name?** `EscapedScope`, `OffThread`, `ReentrantScope`, `ConnectionUnavailable` and `SchemaError`. ADR 0006 calls a defect something nobody handles where it is raised, which is the reason #211 gave for narrowing `NotCanonical`.
5. **Which doors exist for the tests of an application?** `Event.apply(name)`, `Topic.subscriberCount`, `Csrf.Token.value`, `Secret.throwaway`, the fields of `Problem`, `RouteTable.empty`. The blog tests use none of them; #211 narrowed `Csrf.Token.gen` on that ground.
6. **Does an application write a form by hand?** `Form.apply`, `fields`, `show`, `render`, `parse`, `Request.as`, `FormErrors`, `FieldError`, `FormField`, and the three `Field` members. Every form in the examples is derived. The scaladoc at `Form.scala:13` says `FormField` is "published rather than internal on purpose".
7. **Is scaladoc alone enough for kind 1?** `Cookie` rests on scaladoc only. Eight more rows rest on prose, comments or compiler messages: `Html.raw`, `Attrs.cls`, `Guard.current`, `Guard.only`, `Guard.except`, `Password.hash`, `Owning.all`, `Owning.only`. In `http`, 30 kind 1 members and 12 kind 3 members are marked weak.
8. **Is a golden list worth having now?** The facts are in the last section. The list is about to change by some 200 names, which is the reason #211 declined it.

Left over, smaller: `Request.secure`, `Response.withHeader`, `Response.Redirect(Url)`, `RouteTable.++`, `Session.isEmpty`, the `Secret` builders, `Scopes.detached`, `Schema.empty`, `Ref.value`, `Ref.asId`, `Column.withType`, `Html.text`, `Html.empty`, `Html.when`, top level `key`, `Url.path`, `Url./`, `Url.under`, `Id.value`, `Guard.DefaultLifetime`, `given Field[Password.Plain]`, and the trait `DbInit`.

Protected members were listed and not sorted: the hooks of `Dispatch`, `HttpApp` and `EezoApp`, and `DbInit.database`. No example overrides most of them, and a protected member of a trait the application extends is an override point by construction.

## Blockers that order the narrowing

A name cannot narrow while a wider signature mentions it. The ones found:

| Name that would narrow | Mentioned by |
|---|---|
| `TableDef` | `Table.tableDef` |
| `Bind` | The case fields of `Expr`, `Query.selectSql`, `Query.countSql` |
| `Database` | Protected `DbInit.database` |
| `IndexSnap` | `TableSnap.indexes`, `TableSpec.indexes`, `Change.CreateIndex` |
| `Usage` | Protected `Dispatch.usage` |
| `AttrValue` | `Attr.value` |
| `WsConn`, `WsListener`, `Route.Ws` | Each other; `auth` and `live` read all three |
| `FormErrors`, `FieldError`, `FormField` | `Form.render`, `Form.parse`, `Form.fields`, `Form.show`, `Request.as` |

`Scoped` implements `Store`, so its five methods cannot narrow below the trait's. `equals`, `hashCode` and `toString` on `PathPattern` and `Secret` cannot narrow at all.

## What was not verified

* **Nothing was compiled.** Every modifier is what a text search of the readers allows. A narrowed name can still fail to compile, above all in `modules/db`, which is capture checked and needs `db/clean db/compile` before a green build can be trusted.
* **Whether an enum case accepts an access modifier** was not tested. It matters for `Html`, `AttrValue`, `Expr`, `Change` and `PathPattern.Segment`.
* **Whether `TableMacro` itself can narrow** was not tested. A macro implementation must be reachable from the inline method that calls it.
* **Qualified private members on `Table`**, whose instances are derived inside application code, were not tested.
* **Markdown and `modules/example` were pattern searched** by the `core` pass, not read end to end.
* **Reader lists are samples for names with many test readers**: one or two lines per file.
* **The counts are by hand.**

The rest of this document is the evidence, one section per module, then the golden list.

---

## The three small modules: core, auth and eezo

Repository `/Users/rcardin/Documents/Repositories/eezo`, branch `main`, commit `756d1ed`. Read only research. Nothing was compiled.

Conventions used in every table below.

* `M/` paths are shortened in reader lists only: `http M/Resource.scala:277` means `modules/http/src/main/scala/io/eezo/http/Resource.scala:277`, and `T/` means the same under `src/test`. Declared at and evidence columns use full repo relative paths.
* "Weaker" marks evidence that is prose, a comment, a scaladoc or a compiler message, and not a compiled application call.
* Every test under `modules/*/src/test` sits in a package inside `io.eezo`, with one exception, `modules/http/src/test/scala/elsewhere/MiBVisibilitySuite.scala` (package `elsewhere`), which reads none of the names below.
* The generated route table is emitted into package `io.eezo.generated` (`modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:527`). That package is inside `io.eezo`, so a `private[eezo]` name stays reachable from generated code.
* Top level names are counted by distinct name, so a type and its companion count once.

## core

Files read in full: `Id.scala`, `OwnedStore.scala`, `Dispatch.scala`, `Store.scala`, `html/Html.scala`, `html/Tags.scala`, `html/Url.scala`, `html/Tag.scala`, `html/Attrs.scala`. Skipped as instructed: `internal/Json.scala`, `internal/util.scala`.

### Counts

| Kind | Top level names | Members |
|---|---|---|
| 1, written by the application | 6, plus 26 of the 63 package level forwarders made by `export Tags.*` | 51 |
| 2, written through inference | 6 | 1 |
| 3, spliced | 3 | 0 |
| 4, nobody's | 1 | 34 |
| Fits no kind cleanly | 1, plus 37 of the 63 forwarders | 77 |
| Total | 17, plus 63 forwarders | 163 |

Already not public and therefore not listed: `Dispatch.run` (`private[eezo]`), `Html.under`, `Html.transform`, `Html.renderTo`, `Html.VoidTags`, `Html.unescape` (all `private[eezo]`), `Html.escape` (`private[html]`), `Attr.under`, `AttrValue.text`, `Attrs.eezoBase` (all `private[eezo]`), every private member.

Protected members fall outside the task's definition of public. They are: `Dispatch.program`, `Dispatch.commands`, `Dispatch.usage`, `Dispatch.notes`, `Dispatch.json`, `Dispatch.emit`, `Dispatch.fail` (`modules/core/src/main/scala/io/eezo/core/Dispatch.scala:28, 34, 40, 47, 53, 60, 66`). No example, README or doc overrides or calls any of them. An application object inherits them through `HttpApp`, `DbApp` or `EezoApp`.

### Kind 1: written by the application

| Name | Declared at | Evidence |
|---|---|---|
| `Id` (opaque type and companion) | `modules/core/src/main/scala/io/eezo/core/Id.scala:16, 18` | `examples/todo/src/main/scala/models/Todo.scala:20` |
| `Id.gen` | `Id.scala:20` | `examples/reminders/src/main/scala/models/Reminder.scala:27` |
| `Id.apply` | `Id.scala:19` | `examples/blog/src/test/scala/PostOwnershipSuite.scala:265` |
| `Id.show` (extension) | `Id.scala:24` | `examples/blog/src/test/scala/PostOwnershipSuite.scala:257` |
| `Html` (enum and companion) | `modules/core/src/main/scala/io/eezo/core/html/Html.scala:10, 144` | `examples/blog/src/main/scala/components/Counter.scala:25` |
| `Html.++` | `Html.scala:58` | `examples/hello/src/main/scala/app/Hello.scala:19` |
| `Html.render` | `Html.scala:98` | `examples/blog/src/test/scala/PostOwnershipSuite.scala:242` |
| `Html.doctype` | `Html.scala:156` | `examples/hello/src/main/scala/app/Hello.scala:19` |
| `Html.raw` | `Html.scala:150` | Weaker. `docs/how-to/persisting-a-model.md:273` shows `Response.Ok(Html.raw("<html>..."))` as something a page may return. No example calls it. |
| `Tags` (object) | `modules/core/src/main/scala/io/eezo/core/html/Tags.scala:10` | `examples/blog/src/main/scala/components/Board.scala:4` |
| `Tags.html`, `head`, `body`, `title`, `meta`, `h1`, `p`, `a` | `Tags.scala:13, 14, 15, 16, 17, 36, 42, 43` | `examples/blog/src/main/scala/app/Index.scala:52, 53, 57, 55, 54, 58, 59, 60` |
| `Tags.div`, `ul`, `li`, `button` | `Tags.scala:24, 53, 55, 69` | `examples/blog/src/main/scala/components/Board.scala:42, 48, 48, 47` |
| `Tags.span` | `Tags.scala:25` | `examples/blog/src/main/scala/components/Counter.scala:29` |
| `Tags.h2`, `form`, `label`, `input` | `Tags.scala:37, 61, 64, 65` | `examples/blog/src/main/scala/components/Guestbook.scala:101, 81, 83, 83` |
| `Tags.strong`, `em`, `code` | `Tags.scala:44, 45, 47` | `examples/blog/src/main/scala/components/Callout.scala:63, 63, 57` |
| `Tags.pre` | `Tags.scala:48` | `examples/todo/src/main/scala/app/Health.scala:17` |
| `Tags.dl`, `dt`, `dd` | `Tags.scala:56, 57, 58` | `examples/hello/src/main/scala/app/Hello.scala:27, 28, 29` |
| `Tags.select`, `option` | `Tags.scala:67, 68` | `examples/blog/src/main/scala/components/Signup.scala:98, 102` |
| Package level forwarders from `export Tags.*` for the same 26 names | `Tags.scala:90` | Fifteen are proven to be reached through the package and not through `Tags`, because the file imports only `io.eezo.core.html.*`: `html`, `head`, `meta`, `title`, `body`, `h1`, `p`, `code`, `dl`, `dt`, `dd` (`examples/hello/src/main/scala/app/Hello.scala:3, 19 to 33`), `pre` (`examples/todo/src/main/scala/app/Health.scala:3, 17`), `ul`, `li`, `a` (`examples/todo/src/main/scala/app/Index.scala:5, 28, 29`). The other eleven (`div`, `span`, `h2`, `strong`, `em`, `form`, `label`, `input`, `select`, `option`, `button`) are spelled only in files that import both `io.eezo.core.html.*` and `io.eezo.core.html.Tags.*`, so which of the two names the compiler picks was not verified. |
| `Url` (enum and companion) | `modules/core/src/main/scala/io/eezo/core/html/Url.scala:14, 50` | `examples/blog/src/main/scala/models/User.scala:5` |
| `Url.Mounted` (case), `Url.Mounted.apply` | `Url.scala:30, 59 to 61` | `examples/blog/src/main/scala/models/User.scala:58` |
| `Url.Absolute` (case), `Url.Absolute.apply` | `Url.scala:23, 55 to 57` | `examples/blog/src/main/scala/app/Index.scala:61` |
| `Key` (case class, its constructor and synthesised `apply`) | `modules/core/src/main/scala/io/eezo/core/html/Tag.scala:8` | `examples/blog/src/main/scala/components/Board.scala:48` |
| `Tag.apply` | `Tag.scala:33` | `examples/hello/src/main/scala/app/Hello.scala:19`, every `tag(...)` call is this method |
| `AttrName.:=(String)` | `Tag.scala:103` | `examples/hello/src/main/scala/app/Hello.scala:21` |
| `AttrName.:=(Boolean)` | `Tag.scala:111` | `examples/blog/src/main/scala/components/Signup.scala:93` |
| `UrlAttrName.:=(Url)` | `Tag.scala:127` | `examples/blog/src/main/scala/components/Board.scala:46` |
| `Attrs` (object) | `modules/core/src/main/scala/io/eezo/core/html/Attrs.scala:10` | `examples/hello/src/main/scala/app/Hello.scala:21` |
| `Attrs.style` | `Attrs.scala:15` | `examples/blog/src/main/scala/components/Board.scala:43` |
| `Attrs.href`, `Attrs.charset` | `Attrs.scala:22, 37` | `examples/blog/src/main/scala/app/Index.scala:60, 54` |
| `Attrs.name`, `value`, `tpe`, `disabled` | `Attrs.scala:43, 44, 45, 50` | `examples/blog/src/main/scala/components/Guestbook.scala:85, 86, 84, 96` |
| `Attrs.checked`, `selected` | `Attrs.scala:48, 49` | `examples/blog/src/main/scala/components/Signup.scala:93, 102` |
| `Attrs.cls` | `Attrs.scala:14` | Weaker. `docs/adr/0004-an-emitted-url-is-a-value-and-a-string-is-absolute.md:9` spells `Attrs.cls := someUrl` as the example of a compile error. No example writes `Attrs.cls`. |

### Kind 2: written through inference

| Name | Declared at | Exposed by | Application call site |
|---|---|---|---|
| `Dispatch` (trait) | `modules/core/src/main/scala/io/eezo/core/Dispatch.scala:15` | `trait HttpApp extends Dispatch` (`modules/http/src/main/scala/io/eezo/http/HttpApp.scala:26`), `trait DbApp extends Dispatch` (`modules/db/src/main/scala/io/eezo/db/DbApp.scala:40`) | `examples/hello/src/main/scala/Main.scala:21`, `examples/blog/src/main/scala/CreateUser.scala:32` |
| `Dispatch.main` | `Dispatch.scala:72` | Inherited by every application object. The JVM entry point must be public. | `examples/hello/src/main/scala/Main.scala:21`, launched by `sbt run`. Framework readers of the name: `modules/sbt-plugin` looks the main class up, and four db demo files define a `main` of their own. |
| `Attr` | `modules/core/src/main/scala/io/eezo/core/html/Html.scala:245` | Result type of `AttrName.:=` (`Tag.scala:103, 105, 111`), of `UrlAttrName.:=` (`Tag.scala:127`), of `Live.onClick`, `Live.onInput`, `Live.onChange`, `Live.onSubmit`, `Live.ignore` (`modules/live/src/main/scala/io/eezo/live/Live.scala:59, 65, 72, 78, 84`), arm of `Mod` | `examples/blog/src/main/scala/components/Board.scala:47` |
| `Mod` (type alias) | `Tag.scala:20` | Parameter type of `Tag.apply(mods: Mod*)` (`Tag.scala:33`) | `examples/hello/src/main/scala/app/Hello.scala:19` |
| `Tag` (class) | `Tag.scala:25` | Type of every `Tags` val (`Tags.scala:13 to 87`) | `examples/hello/src/main/scala/app/Hello.scala:19` |
| `AttrName` | `Tag.scala:101` | Type of the `Attrs` vals (`Attrs.scala:13`), result of `Attrs.attr`, `Attrs.data` | `examples/hello/src/main/scala/app/Hello.scala:21` |
| `UrlAttrName` | `Tag.scala:125` | Type of `Attrs.href`, `Attrs.src`, `Attrs.action` (`Attrs.scala:22, 23, 41`). Its constructor is already `private[html]`. | `examples/blog/src/main/scala/components/Board.scala:46` |

### Kind 3: spliced

| Name | Declared at | Emitted by |
|---|---|---|
| `Store` | `modules/core/src/main/scala/io/eezo/core/Store.scala:25` | `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:574, 594` spell `io.eezo.core.Store[A]`. Seen generated at `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:70` and `examples/todo/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:30`. `Resource.routesOf` is inline and takes a `Store[A]` (`modules/http/src/main/scala/io/eezo/http/Resource.scala:98 to 104`). Also spelled in `docs/how-to/persisting-a-model.md:23`. |
| `OwnedStore` | `modules/core/src/main/scala/io/eezo/core/OwnedStore.scala:19` | `RouteGenerator.scala:579 to 582` emits `io.eezo.http.Scoped(io.eezo.db.JdbcStore[A]()(using t), io.eezo.db.JdbcStore.owned(o.ownerOf)(using t, summon))`. The generated expression has type `OwnedStore[A, V]` through `JdbcStore.owned` (`modules/db/src/main/scala/io/eezo/db/JdbcStore.scala:47`) and `Scoped.apply` (`modules/http/src/main/scala/io/eezo/http/Scoped.scala:42`). Seen generated at `examples/blog/target/.../Routes.scala:75 to 78`. The name itself is never spelled by the generator. |
| `OwnerOf` | `modules/core/src/main/scala/io/eezo/core/OwnedStore.scala:41` | `RouteGenerator.scala:581, 583, 596` emit `o.ownerOf`, whose type is `OwnerOf[A, Id[U]]` through `Owned.ownerOf` (`modules/http/src/main/scala/io/eezo/http/Owned.scala:32`). Seen generated at `examples/blog/target/.../Routes.scala:77, 79`. The name itself is never spelled by the generator. |

### Kind 4: nobody's

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `OwnedStore.by` | `OwnedStore.scala:26` | http `M/Ownership.scala:143`, `M/Scoped.scala:43`. Implemented in db `M/JdbcStore.scala:122` and http `M/InMemoryStore.scala:71`. Tests: core `T/core/OwnedStoreSuite.scala:34, 36`, core `T/core/support/OwnedStoreContract.scala`, db `T/OwnedJdbcStoreSuite.scala:25, 41, 42`, http `T/OwnedInMemoryStoreSuite.scala:23 to 55`, eezo `T/GeneratedGuardForSuite.scala:92, 93`. | `private[eezo]` | None found. The implementers in `db` and `http` override it from inside `io.eezo`. |
| `OwnerOf.name`, `OwnerOf.get`, the `OwnerOf` constructor and synthesised `apply` | `OwnedStore.scala:41` | Fields: db `M/JdbcStore.scala:48, 54`, http `M/InMemoryStore.scala:89`, http `M/Ownership.scala:63, 65, 110, 196, 197, 246`. Constructor: auth `M/Owning.scala:52`. Tests: core `T/core/OwnedStoreSuite.scala:16 to 18`, db `T/OwnedSqlSuite.scala:47`, db `T/OwnedJdbcStoreSuite.scala:21`, http `T/OwnedResourceSuite.scala:40 to 42, 114, 115, 508`, http `T/OwnershipSuite.scala:16, 29, 30, 105, 145`, http `T/OwnerControlsSuite.scala:26`, http `T/OwnedInMemoryStoreSuite.scala:19`, auth `T/OwningSuite.scala:45, 82`, eezo `T/GeneratedGuardForSuite.scala:220, 242`. | `private[eezo]` | The type `OwnerOf` is kind 3, so only the members narrow. `GuardedBy.owningField` builds one but is not inline, so no splice reaches the constructor. |
| `Dispatch.Usage` (nested case class, inside `object Dispatch`), its constructor | `Dispatch.scala:109, 112` | http `M/HttpApp.scala:4, 96 to 98`, db `M/DbApp.scala:4, 155 to 163`, core `T/core/DispatchSuite.scala:3, 25, 26, 39`. | `private[eezo]` | `protected def usage: List[Usage]` (`Dispatch.scala:40`) mentions it. That member is protected and is inherited by every application object, so an application that overrode `usage` would have to spell `Usage`. No example or doc does. |
| `Usage.command`, `Usage.description` | `Dispatch.scala:112` | core `M/core/Dispatch.scala:97, 98` only. | `private[core]` | Case class fields. |
| `Store.all`, `Store.find`, `Store.insert`, `Store.update`, `Store.delete` | `Store.scala:35, 38, 46, 51, 56` | Callers: http `M/Resource.scala:257, 263, 352, 412, 418`, http `M/Ownership.scala:167, 221`, http `M/Scoped.scala:23 to 31`, http `M/InMemoryStore.scala:74 to 87`. Implemented in http `M/InMemoryStore.scala:110 to 129`, http `M/Scoped.scala:23 to 31`, db `M/JdbcStore.scala:81 to 102, 133 to 166`. Tests: db `T/JdbcStoreSuite.scala:33, 56, 68, 72`, http `T/InMemoryStoreSuite.scala`, http `T/OwnedInMemoryStoreSuite.scala`, db `T/OwnedJdbcStoreSuite.scala`, core `T/core/support/OwnedStoreContract.scala`, core `T/core/OwnedStoreSuite.scala`, eezo `T/GeneratedGuardForSuite.scala:93`. | `private[eezo]` | The type `Store` is kind 3. `docs/how-to/persisting-a-model.md:48` spells `find(key: Id[A]): Option[A]` in prose about the design, not in application code. No example calls any of the five. |
| `Html.Text` (case), field `escaped` | `Html.scala:16` | live `M/Canonical.scala:46, 71, 76`, live `M/Differ.scala:50, 227`, core `M/core/html/Tag.scala:77, 78`. Tests: core `T/views/DslSuite.scala:132`, live `T/DomEqual.scala:17`, live `T/RefApplier.scala:29, 121`. | `private[eezo]` | The constructor is already `private[html]`. Whether an enum case accepts an access modifier was not compiled. |
| `Html.Raw` (case), field `html` | `Html.scala:24` | live `M/Canonical.scala:46, 86`, live `M/Differ.scala:115`, core `M/core/html/Html.scala:150, 156`. Tests: live `T/DomEqual.scala:18`. | `private[eezo]` | Same unverified point about enum cases. `docs/adr/0004-an-emitted-url-is-a-value-and-a-string-is-absolute.md:17` names `Html.Raw` in prose. |
| `Html.Element` (case), fields `name`, `attrs`, `key`, `children` | `Html.scala:33 to 38` | http `M/Reload.scala:82, 84`, live `M/Canonical.scala:30 to 176`, live `M/Differ.scala:53 to 226`, live `M/Live.scala:168, 177`, live `M/Page.scala:60, 78, 311, 315`, core `M/core/html/Tag.scala:52`. Tests: core `T/core/html/RenderingSuite.scala:11 to 85`, core `T/views/DslSuite.scala:61`, live `T/CanonicalSuite.scala`, `T/DomEqual.scala`, `T/Gen.scala`, `T/PageSuite.scala:49`, `T/RefApplier.scala`. | `private[eezo]` | Same unverified point about enum cases. `docs/how-to/persisting-a-model.md:258, 260` spells `Html.Element` inside a listing of the framework's own `Reload.appendToBody`, which also calls the `private[eezo]` `transform`. Every live signature that mentions `Html.Element` is private or sits in a `private[live]` owner (`Page`, `Canonical`). |
| `Html.Fragment` (case), field `children` | `Html.scala:55` | live `M/Canonical.scala:41, 66`, core `M/core/html/Html.scala:59 to 62, 153`, core `M/core/html/Tag.scala:60`. Tests: core `T/core/html/RenderingSuite.scala:91, 102`, core `T/views/DslSuite.scala:66`, live `T/DomEqual.scala:21`. | `private[eezo]` | Same unverified point about enum cases. |
| `Attr.name`, `Attr.value` | `Html.scala:245` | live `M/Differ.scala:82, 86, 93`, core `M/core/html/Html.scala:119, 120`, core `M/core/html/Tag.scala:92`. Tests: live `T/DomEqual.scala:29`, live `T/RefApplier.scala:40`, core `T/core/html/RenderingSuite.scala`. | `private[eezo]` | Case class fields of a kind 2 type. The constructor is already `private[html]`. |
| `AttrValue` (enum) | `Html.scala:262` | Held by inference in live `M/Differ.scala:82` (`attr.value.map(_.text)`). Spelled in core `M/core/html/Html.scala:245, 251, 278, 279`, core `M/core/html/Tag.scala:103, 105, 127`, core `T/core/html/RenderingSuite.scala:14, 15, 26, 34, 45, 56`. | `private[eezo]` | The public field `Attr.value: Option[AttrValue]` mentions it. If `Attr.value` narrows to `private[eezo]` the blocker goes with it. |
| `AttrValue.Literal` (case), field `value` | `Html.scala:264` | core `M/core/html/Html.scala:278`, core `M/core/html/Tag.scala:103, 105`, core `T/core/html/RenderingSuite.scala` (package `io.eezo.core.html`). | `private[html]` | Same unverified point about enum cases. |
| `AttrValue.Link` (case), field `url` | `Html.scala:271` | core `M/core/html/Html.scala:251, 279`, core `M/core/html/Tag.scala:127`, core `T/core/html/RenderingSuite.scala:34, 45`. | `private[html]` | The constructor is already `private[html]`. Same unverified point about enum cases. |
| `Url.normalise` | `Url.scala:73` | http `M/Route.scala:92`, live `M/Live.scala:313`, core `M/core/html/Url.scala:46, 60`. No test calls it directly. | `private[eezo]` | None found. Its scaladoc gives the reason it is public as the call from `Route.under`, which is inside `io.eezo`. |
| `Key.value` | `Tag.scala:8` | core `M/core/html/Tag.scala:40` only. | `private[html]` | Case class field of a kind 1 type. |
| `Tag.name` | `Tag.scala:25` | core `M/core/html/Tag.scala:52`. Tests: live `T/Gen.scala:78, 167, 168, 169`. | `private[eezo]` | None found. |
| `Tag` constructor | `Tag.scala:25` | core `M/core/html/Tags.scala:13 to 87`. Tests: live `T/Gen.scala:159`. | `private[eezo]` | None found. No example builds a tag of its own. |
| `Tag` companion object | `Tag.scala:56` | core `M/core/html/Tag.scala:41, 52` only. Every member is private. | `private[html]` | It is the companion of a public class. |
| `AttrName.name` | `Tag.scala:101` | core `M/core/html/Tag.scala:103, 105, 111` only, all inside the class. | `private` | `UrlAttrName` passes the argument to the parent constructor and does not read the field. |
| `AttrName` constructor | `Tag.scala:101` | core `M/core/html/Attrs.scala:13 to 80`, live `M/Live.scala:47 to 53, 84, 172`. Tests: live `T/RefApplier.scala:37`. | `private[eezo]` | None found. `Attrs.attr` and `Attrs.data` are the doors an application has to an arbitrary name. |

### Fits no kind cleanly

**The unused tags, as one family (37 members, each also a package level forwarder).**
`link`, `style`, `script`, `base`, `header`, `footer`, `main`, `nav`, `section`, `article`, `aside`, `hr`, `h3`, `h4`, `h5`, `h6`, `small`, `br`, `blockquote`, `ol`, `fieldset`, `legend`, `textarea`, `img`, `figure`, `figcaption`, `video`, `audio`, `source`, `table`, `thead`, `tbody`, `tfoot`, `tr`, `th`, `td`, `caption` (`Tags.scala:18 to 87`).
For: they are the same vocabulary as the 26 tags the examples write, and the scaladoc at `Tags.scala:3 to 9` presents the object as what a view file imports.
Against: no example, README or doc spells any of them. Framework readers exist for some: `script` (http `M/Reload.scala:64`, live `M/Live.scala:172`), `small` (http `M/Boundary.scala:98`, eezo `M/DriftGate.scala:241`), `fieldset` and `legend` (eezo `M/DriftGate.scala:248`), `table`, `thead`, `tbody`, `tr`, `th`, `td` (http `M/Resource.scala:268 to 274`). Tests only: `header`, `main`, `nav` (live `T/RoundTripSuite.scala:97`), `section`, `article`, `br`, `ol`, `img` (live `T/Gen.scala:24, 63, 66, 115`), `h3` (live `T/CanonicalSuite.scala:78`). No reader anywhere: `link`, `style`, `base`, `footer`, `aside`, `hr`, `h4`, `h5`, `h6`, `blockquote`, `textarea`, `figure`, `figcaption`, `video`, `audio`, `source`, `tfoot`, `caption`. Readers allow `private[eezo]` for the first two groups and removal for the last. Whether `export Tags.*` forwards a member that is not public was not compiled.

**The unused attribute names, as one family (30 vals and 2 defs).**
`id`, `title`, `lang`, `role`, `hidden`, `src`, `alt`, `rel`, `target`, `width`, `height`, `content`, `action`, `method`, `htmlFor`, `placeholder`, `readonly`, `required`, `multiple`, `rows`, `cols`, `size`, `maxlength`, `min`, `max`, `step`, `autofocus`, `colspan`, `rowspan`, `scope`, and the defs `attr` and `data` (`Attrs.scala:13 to 80`).
For: same vocabulary as the 10 names the examples and docs write. The scaladoc of `attr` (`Attrs.scala:68 to 76`) calls it the way to an attribute the list does not carry.
Against: no example, README or doc spells any of them. Framework readers: `id` (http `M/Form.scala:235`), `src` (live `M/Live.scala:172`), `action` (auth `M/Guard.scala:110`, eezo `M/DriftGate.scala:238`, http `M/Form.scala:257`, http `M/Resource.scala:375`), `method` (auth `M/Guard.scala:111`, eezo `M/DriftGate.scala:237`, http `M/Form.scala:258`, http `M/Resource.scala:376`), `htmlFor` (http `M/Form.scala:248`), `placeholder` and `required` (eezo `M/DriftGate.scala:255, 256`). Tests only: `title`, `alt`, `size`. No reader anywhere: `lang`, `role`, `hidden`, `rel`, `target`, `width`, `height`, `content`, `readonly`, `multiple`, `rows`, `cols`, `maxlength`, `min`, `max`, `step`, `autofocus`, `colspan`, `rowspan`, `scope`, `attr`, `data`.

**`Html.text`, `Html.empty`, `Html.when`** (`Html.scala:147, 153, 161`).
For: the scaladoc calls `text` "the only door from plain text into the tree", and `when` and `empty` are view helpers with no framework purpose of their own.
Against: no example or doc spells them. Readers: `text` in http `M/Resource.scala:277` and many tests across auth, http, live, eezo. `empty` in auth `M/Guard.scala:108` and tests. `when` in core `T/core/html/RenderingSuite.scala:107, 108` only. Readers allow `private[eezo]` for `text` and `empty`, and `private[html]` for `when`.

**`key` (top level def)** (`Tag.scala:131`).
For: its scaladoc says it builds a list child's `Key`, which is what a live component does.
Against: every example writes `Key(...)` instead (`examples/blog/src/main/scala/components/Board.scala:48`). The one reader is core `T/views/DslSuite.scala:27`.

**`AttrName.:=(Int)`** (`Tag.scala:105`).
For: it is the sibling of the `String` and `Boolean` overloads that examples call.
Against: no example passes an `Int`. Readers: live `M/Live.scala:66` and tests. Readers allow `private[eezo]`.

**`Url.path`, `Url./`, `Url.under`** (`Url.scala:17, 33, 44`).
For: `Url` is a kind 1 type the application builds, and these are its only operations.
Against: no example or doc calls them. Readers: `path` in auth `M/Guard.scala:65, 142 to 144`, http `M/Response.scala:148`, http `M/Resource.scala:433 to 440`, core `M/core/html/Html.scala:279`. `/` in http `M/Resource.scala:212, 371`. `under` in http `M/Response.scala:87`, core `M/core/html/Html.scala:251`. All three in core `T/core/html/UrlSuite.scala`. Readers allow `private[eezo]`. `path` is also the field of both enum cases.

**`Id.value`** (`Id.scala:23`).
For: it is the only way from an `Id` back to its `UUID`.
Against: no example or doc calls it. Readers: db `M/Column.scala:91`, db `M/Ref.scala:19`, db `T/ConstraintSuite.scala:63`, core `T/core/IdSuite.scala:17, 26`. Readers allow `private[eezo]`.

### Method and caveats

* Read in full: the nine core files listed at the top of this section.
* Application evidence read in full: every `.scala` file under `examples/*/src` and the three generated `Routes.scala` files under `examples/{hello,todo,blog}/target/scala-3.8.4/src_managed`. `examples/reminders` has no generated sources on disk. `modules/example` and the Markdown files (`README.md`, `docs/**`, `examples/*/README.md`) were searched by pattern and the hits read in context, not read end to end.
* Every tag and every attribute name was searched by script over that corpus, once as `name(` and once as `Attrs.name`, comment lines excluded for Scala files. A hit for `header(` in `modules/example/src/test/scala/example/Tour.scala:406` is that file's own method, not the tag.
* Readers were found by pattern over every `.scala` file under `modules/` outside `target`. Hits for short names (`main(`, `header(`, `.value`, `.name`, `.render`) were checked by hand and the false ones dropped. Reader lists for names with many test readers give files and not every line.
* Generator source read: `RouteGenerator.scala` lines 360 to 700 by pattern and lines 505 to 545 in full. The only core name it spells is `io.eezo.core.Store`.
* Inline and macro sources checked for spliced core names: `Resource.routesOf`, `Resource.derived`, `Resource.keyIndex` (http), `Form.derived` (http), `TableMacro` (db), `Scopes` (db). `Resource.keyIndex` and `TableMacro` mention `Id`, which is kind 1 already. None of them mention an `io.eezo.core.html` name.
* Docs spellings that show framework internals were not counted as application writing: `Html.Element` and `transform` in `docs/how-to/persisting-a-model.md:255 to 264`, `Store.find` in the same file at line 48, `Html.Raw` and `UrlAttrName` in ADR 0004.
* Not verified, because nothing was compiled: whether an enum case can carry an access modifier, whether `export Tags.*` forwards non public members, which of the two wildcard imports wins in files that import both, and that each narrowest modifier named here compiles.

## auth

Files read in full: `Guard.scala`, `Selector.scala`, `Password.scala`, `Owning.scala`.

### Counts

| Kind | Top level names | Members |
|---|---|---|
| 1, written by the application | 2 | 16 |
| 2, written through inference | 2 | 0 |
| 3, spliced | 0 | 2 |
| 4, nobody's | 1 | 3 |
| Fits no kind cleanly | 0 | 2 |
| Total | 5 | 23 |

Already not public and therefore not listed: `Selector` (`private[auth]` object), the `Guard` constructor (private), `Guard.UserEntry`, `Guard.ReturnEntry`, `Guard.StampEntry` (`private[eezo]`), `Guard.MaxReturn`, `Guard.parse`, `Guard.stamped`, `Guard.readStamp`, `Guard.remembered`, `Guard.back`, `Guard.relative`, `Guard.page` (`private[auth]`), `Password.matches` (`private[auth]`), the `Password.Plain` constructor and `Plain.text` (`private[auth]`), the constructors of `GuardedBy` and `Owning` (`private[auth]`), every private member. `Password.Plain.toString` (`Password.scala:77`) overrides a member of `Any` and cannot narrow.

`GuardedBy` extends `io.eezo.http.Guarded` (`Owning.scala:30`), so it inherits that class's public members. They belong to `http` and are not listed here.

### Kind 1: written by the application

| Name | Declared at | Evidence |
|---|---|---|
| `Guard` (class and companion) | `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:48, 409` | `examples/blog/src/main/scala/models/User.scala:3, 55` |
| `Guard.apply` | `Guard.scala:494` | `examples/blog/src/main/scala/models/User.scala:55` |
| `Guard.logoutForm` | `Guard.scala:107` | `examples/blog/src/main/scala/app/Index.scala:61` |
| `Guard.required` | `Guard.scala:125` | `examples/blog/src/main/scala/models/Post.scala:57` |
| `Guard.current` | `Guard.scala:84` | Weaker. No example calls it. `docs/adr/0001-http-errors-live-in-http-and-carry-no-status.md:17` describes it in prose, and its scaladoc (`Guard.scala:67 to 83`) says it is for a handler behind a guarded route. Readers are auth `T/GuardSuite.scala:113, 204, 449, 461, 490, 515` only. |
| `Guard.only` | `Guard.scala:128` | Weaker. No example calls it. The `implicitNotFound` text of `Guarded` tells the application to write `User.guard.only(Action.Create, Action.Update, Action.Destroy)` (`modules/http/src/main/scala/io/eezo/http/Guarded.scala:72`). Readers are auth `T/GuardSuite.scala:611, 627` only. |
| `Guard.except` | `Guard.scala:131` | Weaker. No example, doc or compiler message spells it on a guard. Scaladoc only. The reader is auth `T/GuardSuite.scala:630`. |
| `Password` (opaque type and companion) | `modules/auth/src/main/scala/io/eezo/auth/Password.scala:23, 25` | `examples/blog/src/main/scala/models/User.scala:25` |
| `Password.stored` | `Password.scala:157` | `examples/blog/src/main/scala/models/User.scala:37` |
| `Password.value` (extension) | `Password.scala:162` | `examples/blog/src/main/scala/models/User.scala:37` |
| `Password.verify` (extension) | `Password.scala:180` | `examples/blog/src/test/scala/CreateUserSuite.scala:22` |
| `Password.Plain` (class and companion) | `Password.scala:72, 80` | `examples/blog/src/test/scala/CreateUserSuite.scala:22` |
| `Password.Plain.apply` | `Password.scala:82` | `examples/blog/src/test/scala/CreateUserSuite.scala:22` |
| `Password.hash` | `Password.scala:115` | Weaker. `examples/blog/README.md:124` names `Password.hash` in prose. No example calls it: `examples/blog/src/main/scala/CreateUser.scala:90` hashes through `Field[Password].read`. Other readers: auth `M/Password.scala:134, 190`, auth `T/GuardSuite.scala:686`, auth `T/PasswordSuite.scala:10 to 76`. |
| `GuardedBy.owning` (inline) | `modules/auth/src/main/scala/io/eezo/auth/Owning.scala:42` | `examples/blog/src/main/scala/models/Post.scala:57` |
| `Owning.except` | `Owning.scala:83` | `examples/blog/src/main/scala/models/Post.scala:57` |
| `Owning.all` | `Owning.scala:75` | Weaker. The comment at `examples/blog/src/main/scala/models/Post.scala:47` says a private model "would end in `.all`". No example calls it. Readers are auth `T/OwningSuite.scala:54, 59, 67, 76` and more in the same file. |
| `Owning.only` | `Owning.scala:78` | Weaker. Scaladoc only. Readers are auth `T/OwningSuite.scala:61, 68`. |

### Kind 2: written through inference

| Name | Declared at | Exposed by | Application call site |
|---|---|---|---|
| `GuardedBy` | `Owning.scala:24` | Result type of `Guard.required`, `Guard.only`, `Guard.except` (`Guard.scala:125, 128, 131`) | `examples/blog/src/main/scala/models/Post.scala:57`, where `.owning` is called on the value |
| `Owning` | `Owning.scala:68` | Result type of `GuardedBy.owning` and `GuardedBy.owningField` (`Owning.scala:42, 51`) | `examples/blog/src/main/scala/models/Post.scala:57`, where `.except` is called on the value |

### Kind 3: spliced

| Name | Declared at | Emitted by |
|---|---|---|
| `GuardedBy.owningField` | `Owning.scala:51` | The inline `GuardedBy.owning` expands to `owningField(Selector.nameOf(selector), selector)` at the application's call site (`Owning.scala:42, 43`). The call site is `examples/blog/src/main/scala/models/Post.scala:57`. Its scaladoc states it is public only for that expansion. |
| `given Field[Password]` | `Password.scala:190` | Summoned at the application's call site by `Field[Password]` (`examples/blog/src/main/scala/CreateUser.scala:90`). `Form.derived` would summon it the same way for a model with a `Password` field (`modules/http/src/main/scala/io/eezo/http/Form.scala:162 to 168`). No example derives `Form` for such a model. |

### Kind 4: nobody's

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `Login` (case class, its companion and the derived `Form[Login]`) | `Guard.scala:20` | auth `M/Guard.scala:336, 357` only. The `Login` in http `T/FormSuite.scala:24` and http `T/ResourceSuite.scala:62` is each suite's own class. | `private[auth]` | None found. No public signature mentions it. `derives Form` expands inside `auth`. |
| `Login.email`, `Login.password` | `Guard.scala:20` | auth `M/Guard.scala:360, 363` only. | `private[auth]` | Case class fields. `password` exposes `Password.Plain`, which is kind 1. |
| `GuardedBy.currentUser` (constructor val) | `Owning.scala:28` | auth `M/Owning.scala:52`, inside the class. Tests: auth `T/OwningSuite.scala:87, 92, 97, 101` (package `io.eezo.auth`). Every other `.currentUser` hit in the repository is `Request.currentUser` or `Owned.currentUser`. | `private[auth]` | None found. The inline `owning` does not mention it, only `owningField` does, and that method is not inline. |

### Fits no kind cleanly

**`Guard.DefaultLifetime`** (`Guard.scala:442`).
For: it is the documented default of the public parameter `lifetime` of `Guard.apply`, and an application that wants a multiple of it would name it.
Against: no example or doc spells it. Readers: auth `M/Guard.scala:499`, auth `T/GuardSuite.scala:44`, auth `T/SignInFixtures.scala:27`. Readers allow `private[auth]`. The default argument is evaluated inside the companion, so an application that omits `lifetime` does not mention the name.

**`given Field[Password.Plain]`** (`Password.scala:91`).
For: an application form model with a `Password.Plain` field would summon it through `Form.derived`, and `Password.Plain` itself is kind 1.
Against: no example or doc has such a model. Readers: the `derives Form` of `Login` (auth `M/Guard.scala:20`) and auth `T/PasswordSuite.scala:68`. Readers allow `private[auth]`.

### Method and caveats

* Read in full: the four auth files.
* `examples/blog` is the only example that depends on `eezo-auth`, so every kind 1 row comes from it or from its README and tests.
* Six kind 1 rows rest on weaker evidence and are marked: `Guard.current`, `Guard.only`, `Guard.except`, `Password.hash`, `Owning.all`, `Owning.only`. Their only compiled readers are tests inside `io.eezo.auth`, which would allow `private[auth]`.
* `GuardedBy.owning` is inline and its body names `Selector.nameOf`, a member of a `private[auth]` object. This compiles today for `examples/blog`, by whatever accessor the compiler generates. How that accessor behaves if surrounding modifiers change was not compiled.
* The anonymous givens have the compiler made names `given_Field_Plain` and `given_Field_Password`. No file in the repository spells either name.
* Not verified: that a `private[auth]` given in a companion is still found by the `derives Form` expansion for `Login`.

## eezo

Files read in full: `EezoApp.scala`, `DriftGate.scala`.

### Counts

| Kind | Top level names | Members |
|---|---|---|
| 1, written by the application | 1 | 0 |
| 2, written through inference | 0 | 0 |
| 3, spliced | 0 | 0 |
| 4, nobody's | 0 | 0 |
| Fits no kind cleanly | 0 | 0 |
| Total | 1 | 0 |

Already not public and therefore not listed: `DriftGate` (`private[eezo]` object, `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:51`) with everything inside it, including its unmodified `apply` at line 62. `EezoApp.program` and `EezoApp.devServer` (`modules/eezo/src/main/scala/io/eezo/EezoApp.scala:36, 38`) are protected overrides.

### Kind 1: written by the application

| Name | Declared at | Evidence |
|---|---|---|
| `EezoApp` | `modules/eezo/src/main/scala/io/eezo/EezoApp.scala:31` | `examples/todo/src/main/scala/Main.scala:3, 12`, `examples/blog/src/main/scala/Main.scala:3, 43`, `docs/how-to/persisting-a-model.md:155, 160`, `docs/live.md:252` |

### Kind 2: written through inference

None.

### Kind 3: spliced

None.

### Kind 4: nobody's

None.

### Fits no kind cleanly

None.

### Method and caveats

* `EezoApp` declares no public member of its own. Everything an application overrides on it (`schema`, `routes`, `port`, `databaseSchema`, `databaseInit`, `boot`) is declared in `HttpApp`, `LiveApp` or `DbApp`, which belong to other modules.
* `modules/sbt-plugin/src/main/scala/io/eezo/sbt/EezoPlugin.scala:122, 175` names `EezoApp` inside error message strings only.
* Readers of `DriftGate`: `modules/eezo/src/main/scala/io/eezo/EezoApp.scala:39` and `modules/eezo/src/test/scala/io/eezo/DriftGateSuite.scala:10, 15` (package `io.eezo`).

---

## http

Module `modules/http/src/main/scala`, packages `io.eezo.http` and `io.eezo.http.client`, at commit 756d1ed. The package `io.eezo.http.cli` and `Csrf.Token.gen` are out of scope and are not sorted here.

In the tables, a path followed by several line numbers means every number refers to that same file. "Weak" marks a row whose only evidence is a scaladoc, an error message or reasoning, with no spelling in an example, the README or the docs.

### Counts

| Kind | Top level names | Members |
|---|---|---|
| 1. Written by the application | 29 | 98 |
| 2. Written through inference | 2 | 1 |
| 3. Spliced | 6 | 32 |
| 4. Nobody's | 3 | 58 |
| Fits no kind cleanly | 4 | 51 |
| Total | 44 | 240 |

Counts were made by hand from the rows below; an overloaded method counts once per overload and a synthesised `apply` counts with its constructor. The total of 44 top level names counts `Request.as` (a top level extension with two overloads) as one name. Of the 29 top level names in kind 1, 3 rest on weak evidence (`Cookie`, `client.Http`, `Secret`). Of the 98 members in kind 1, 30 rest on weak evidence. Of the 32 members in kind 3, 12 rest on weak evidence.

### Kind 1: written by the application

| Name | Declared at | Evidence |
|---|---|---|
| `Action` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:19` | Spelled at `examples/blog/src/main/scala/models/Post.scala:5` and `:57`. |
| `Action.Index`, `Action.Show` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:20` | Spelled at `examples/blog/src/main/scala/models/Post.scala:57` and `examples/blog/README.md:54`. |
| `Action.New`, `Action.Edit`, `Action.Create`, `Action.Update`, `Action.Destroy` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:20` | Weak. No spelling in the evidence. `Create`, `Update` and `Destroy` are in the text the compiler prints to the application at `modules/http/src/main/scala/io/eezo/http/Guarded.scala:72`. All five are cases of the same enum as `Index` and `Show`. |
| `Actions.only`, `Actions.except` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:36`, `:39` | Weak. The scaladoc at `Actions.scala:42` says a `given Actions[Widget]` in the model's companion wins, and `Actions.scala:7` writes `Actions.except(Destroy)` as what a user says. Readers are http tests only (`modules/http/src/test/scala/io/eezo/http/ActionsSuite.scala:17`, `ResourceSuite.scala:23`). |
| `client.Reply` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:21` | Spelled at `examples/blog/src/main/scala/components/Callout.scala:5` and `docs/live.md:177`. |
| `client.Reply.Ok`, `Reply.Denied`, `Reply.Failed`, `Reply.Unreachable`, with their fields `Ok.response`, `Denied.response`, `Failed.response`, `Unreachable.reason` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:24`, `:27`, `:30`, `:35` | Spelled as patterns that bind the field at `examples/blog/src/main/scala/components/Callout.scala:31` to `:34` and `docs/live.md:177` to `:180`. |
| `client.Received.text`, `client.Received.status` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:44`, `:42` | Spelled as `r.text` and `r.status` at `examples/blog/src/main/scala/components/Callout.scala:31`, `:32`. |
| `client.Auth` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:52` | Spelled at `examples/blog/src/main/scala/components/Callout.scala:5`, `:30`. |
| `client.Auth.bearer` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:54` | Spelled at `examples/blog/src/main/scala/components/Callout.scala:30` and `docs/live.md:176`. |
| `client.Http` (class and object) | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:72`, `:128` | Weak. No spelling in the evidence. The application reaches the client through `async.get` (`examples/blog/src/main/scala/components/Callout.scala:30`), which is `io.eezo.live.Async.get` and calls `Http.get` inside the framework at `modules/live/src/main/scala/io/eezo/live/Component.scala:94`. That method is not inline, so nothing is spliced. The scaladoc at `modules/live/src/main/scala/io/eezo/live/Component.scala:57` names `io.eezo.http.client.Http` as what application work calls. |
| `client.Http.get`, `client.Http.post` (object) | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:137`, `:141` | Weak, same reasoning as `client.Http`. Readers: `modules/live/src/main/scala/io/eezo/live/Component.scala:94`, `:104`; `modules/http/src/test/scala/io/eezo/http/client/ClientSuite.scala:47`, `:120`; `modules/live/src/test/scala/io/eezo/live/LiveServerSuite.scala:550`. |
| `Cookie` | `modules/http/src/main/scala/io/eezo/http/Cookie.scala:19` | Weak. No spelling in any example, the README or the docs. The scaladoc at `Cookie.scala:3` calls it "a cookie a response sets", and `Response.withCookie` takes one. |
| `Cookie.apply` (handwritten, companion), `Cookie.expired` | `modules/http/src/main/scala/io/eezo/http/Cookie.scala:47`, `:51` | Weak. The primary constructor is `private[http]`, so these two are the only ways an application can build a `Cookie`. Readers: `modules/http/src/main/scala/io/eezo/http/Session.scala:197`, `:198`; http tests (`CookieSuite.scala:13`, `:27`). |
| `Csrf` | `modules/http/src/main/scala/io/eezo/http/Csrf.scala:28` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:10`. |
| `Csrf.Field` | `modules/http/src/main/scala/io/eezo/http/Csrf.scala:54` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:74`, `:186`. |
| `Csrf.hidden` | `modules/http/src/main/scala/io/eezo/http/Csrf.scala:64` | Weak. The scaladoc at `Csrf.scala:61` names "a handwritten form". Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:112`, `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:239`, `modules/http/src/main/scala/io/eezo/http/Form.scala:144`. |
| `Csrf.rotated` | `modules/http/src/main/scala/io/eezo/http/Csrf.scala:99` | Weak. The scaladoc at `Csrf.scala:115` says a login handler calls it. The only main reader is `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:375`. |
| `EezoException` | `modules/http/src/main/scala/io/eezo/http/Errors.scala:14` | Spelled in `docs/failures.md:19` and `:74`. |
| `BadRequest`, `MethodNotAllowed`, `PayloadTooLarge`, `NotImplemented`, with fields `BadRequest.detail`, `MethodNotAllowed.allowed`, `PayloadTooLarge.limit`, `NotImplemented.method` | `modules/http/src/main/scala/io/eezo/http/Errors.scala:17`, `:25`, `:42`, `:46` | `docs/failures.md:19` to `:21` tells a handler to throw them and spells each with its field. No example throws one. |
| `NotFound`, `Forbidden`, with fields `NotFound.path`, `Forbidden.detail` | `modules/http/src/main/scala/io/eezo/http/Errors.scala:20`, `:39` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:11`, `:13`, `:214`, `:215`, `:220` and `docs/failures.md:20`, `:22`. |
| `Field` | `modules/http/src/main/scala/io/eezo/http/Field.scala:29` | Spelled at `examples/blog/src/main/scala/CreateUser.scala:7`, `:90`. |
| `Field.apply` (summoner), `Field.read` | `modules/http/src/main/scala/io/eezo/http/Field.scala:48`, `:40` | `Field[Password].read(text)` at `examples/blog/src/main/scala/CreateUser.scala:90`. |
| `Field.of` | `modules/http/src/main/scala/io/eezo/http/Field.scala:56` | Weak. The `@implicitNotFound` text at `Field.scala:27` tells the application to write `given Field[X] = Field.of(...)`. Main reader: `modules/auth/src/main/scala/io/eezo/auth/Password.scala:98`. |
| `Form` | `modules/http/src/main/scala/io/eezo/http/Form.scala:58` | Spelled in `derives` clauses at `examples/blog/src/main/scala/models/Post.scala:36`, `examples/todo/src/main/scala/models/Todo.scala:25`, `docs/deploying.md:70`. |
| `Guarded` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:75` | Spelled at `examples/blog/src/main/scala/app/Index.scala:36`. |
| `Guarded.public` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:113` | Spelled at `examples/blog/src/main/scala/app/Index.scala:36`. Also emitted by the generator (`modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:655`, `:681`). |
| `HttpApp` | `modules/http/src/main/scala/io/eezo/http/HttpApp.scala:26` | Spelled at `examples/hello/src/main/scala/Main.scala:21`. |
| `HttpApp.routes`, `HttpApp.port`, `HttpApp.problems` | `modules/http/src/main/scala/io/eezo/http/HttpApp.scala:31`, `:34`, `:50` | Overridden at `examples/hello/src/main/scala/Main.scala:22`, `examples/todo/src/main/scala/Main.scala:20`, `docs/failures.md:44`. |
| `HttpApp.maxBodySize`, `HttpApp.secret`, `HttpApp.boot` | `modules/http/src/main/scala/io/eezo/http/HttpApp.scala:37`, `:45`, `:56` | Weak. Override points on the trait the application extends. The scaladoc at `HttpApp.scala:15` says every knob is an override beside `port`, and `:53` says to override `boot`. The `boot` overrides in the examples are `DbApp.boot`, not this one. |
| `Method` | `modules/http/src/main/scala/io/eezo/http/Method.scala:9` | Spelled at `examples/blog/src/test/scala/UserSuite.scala:1`, `:26`. |
| `Method.GET`, `Method.POST`, `Method.PUT`, `Method.DELETE` | `modules/http/src/main/scala/io/eezo/http/Method.scala:10` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:174`, `:180`, `:319`, `:320`. Also emitted by the generator (`RouteGenerator.scala:408`). |
| `Method.PATCH`, `Method.HEAD`, `Method.OPTIONS` | `modules/http/src/main/scala/io/eezo/http/Method.scala:10` | Weak. No spelling in the evidence. Cases of an enum whose other cases the application writes. Readers are http tests only (`CsrfSuite.scala:180`, `FormSuite.scala:84`, `MethodSuite.scala:13`). |
| `Method.safe` | `modules/http/src/main/scala/io/eezo/http/Method.scala:15` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:186`. |
| `Owned` | `modules/http/src/main/scala/io/eezo/http/Owned.scala:28` | Spelled at `examples/blog/src/main/scala/models/Post.scala:7`, `:56`. |
| `Problem` | `modules/http/src/main/scala/io/eezo/http/Problem.scala:15` | Spelled in `docs/failures.md:44`. |
| `Problem.apply(status, detail, instance)` (handwritten) | `modules/http/src/main/scala/io/eezo/http/Problem.scala:26` | Spelled in `docs/failures.md:45` and `:25`. |
| `Request` and its synthesised `apply` | `modules/http/src/main/scala/io/eezo/http/Request.scala:58` | Spelled at `examples/hello/src/main/scala/app/Hello.scala:15`, constructed at `examples/blog/src/test/scala/UserSuite.scala:25`. |
| `Request.method`, `Request.path`, `Request.query`, `Request.headers`, `Request.body`, `Request.pathParams`, `Request.session` | `modules/http/src/main/scala/io/eezo/http/Request.scala:59` to `:65` | Spelled as named arguments at `examples/blog/src/test/scala/PostOwnershipSuite.scala:188` to `:196`. `request.path` is read at `examples/hello/src/main/scala/app/Hello.scala:33`. |
| `Request.currentUser` | `modules/http/src/main/scala/io/eezo/http/Request.scala:67` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:372`. |
| `Request.header`, `Request.queryParam` | `modules/http/src/main/scala/io/eezo/http/Request.scala:71`, `:75` | Spelled at `examples/blog/src/main/scala/app/api/Index.scala:15` and `examples/todo/src/main/scala/app/Index.scala:20`. |
| `Request.cookies`, `Request.cookie` | `modules/http/src/main/scala/io/eezo/http/Request.scala:81`, `:85` | Weak. The scaladoc at `Request.scala:78` says "what a handler reads for its own cookies". Readers: `modules/http/src/main/scala/io/eezo/http/Session.scala:179`, `:192`; `modules/http/src/test/scala/io/eezo/http/CookieSuite.scala:64`, `:65`. |
| `Request.csrf` | `modules/http/src/main/scala/io/eezo/http/Request.scala:95` | Weak. The scaladoc at `Request.scala:87` says "what a handler hands to `Form.render` and `Csrf.hidden`". Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:112`, `:336`; `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:99`; `modules/http/src/main/scala/io/eezo/http/Resource.scala:300`. |
| `Request.form` | `modules/http/src/main/scala/io/eezo/http/Request.scala:112` | Weak. The scaladoc at `Request.scala:108` says the handler reads it. Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:396`, `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:136`. |
| `Request.param`, `Request.paramOpt` | `modules/http/src/main/scala/io/eezo/http/Request.scala:127`, `:138` | Weak. `Request.param[A]` appears in prose at `docs/adr/0003-skeleton-one-landed-the-http-surface-later-tickets-had-already-decided.md:11`, and the scaladoc at `Request.scala:9` speaks of "the type a handler asked for". No example handler has a path parameter. Readers: `modules/http/src/main/scala/io/eezo/http/Resource.scala:252`, `:400`, `:417`; http tests (`RequestSuite.scala:74`, `:84`). |
| `Resource` | `modules/http/src/main/scala/io/eezo/http/Resource.scala:44` | Spelled in `derives` clauses at `examples/blog/src/main/scala/models/Post.scala:37`, `examples/todo/src/main/scala/models/Todo.scala:26`. |
| `Body` | `modules/http/src/main/scala/io/eezo/http/Response.scala:10` | Spelled at `examples/blog/src/main/scala/app/api/Index.scala:3`. |
| `Body.Bytes`, `Body.Html`, with fields `Bytes.value`, `Html.value` | `modules/http/src/main/scala/io/eezo/http/Response.scala:13`, `:15` | `Body.Bytes(...)` at `examples/blog/src/main/scala/app/api/Index.scala:19`; pattern `Body.Html(node)` at `examples/blog/src/test/scala/PostOwnershipSuite.scala:242`. |
| `Body.Empty` | `modules/http/src/main/scala/io/eezo/http/Response.scala:17` | Weak. No spelling. It is the third case of a three case enum the application matches on (`PostOwnershipSuite.scala:241` matches it through a wildcard). |
| `Response` and its synthesised `apply` | `modules/http/src/main/scala/io/eezo/http/Response.scala:39` | Constructed at `examples/blog/src/main/scala/app/api/Index.scala:16`. |
| `Response.status`, `Response.headers`, `Response.body`, `Response.session` (fields) | `modules/http/src/main/scala/io/eezo/http/Response.scala:40` to `:43` | `examples/blog/src/test/scala/PostOwnershipSuite.scala:175` (`.status`), `:203` (`.session`), `:241` (`.body`); headers passed positionally at `examples/blog/src/main/scala/app/api/Index.scala:18`. |
| `Response.header` | `modules/http/src/main/scala/io/eezo/http/Response.scala:72` | Spelled at `examples/blog/src/test/scala/UserSuite.scala:37`. |
| `Response.Ok` | `modules/http/src/main/scala/io/eezo/http/Response.scala:114` | Spelled at `examples/hello/src/main/scala/app/Hello.scala:18`. |
| `Response.Redirect(location: String)` | `modules/http/src/main/scala/io/eezo/http/Response.scala:118` | Spelled in `docs/failures.md:59`. |
| `Response.status(code)` (companion) | `modules/http/src/main/scala/io/eezo/http/Response.scala:129` | Spelled in `docs/failures.md:58`. |
| `Response.withCookie` | `modules/http/src/main/scala/io/eezo/http/Response.scala:59` | Weak. The scaladoc at `Response.scala:56` speaks of user code. Readers: `modules/http/src/main/scala/io/eezo/http/Session.scala:197`, `:198`; `modules/http/src/test/scala/io/eezo/http/CookieSuite.scala:49`. |
| `Response.withSession` | `modules/http/src/main/scala/io/eezo/http/Response.scala:65` | Weak. The scaladoc at `modules/http/src/main/scala/io/eezo/http/Session.scala:12` says a handler hands the session to it. Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:319`, `:374`, `:406`; `modules/http/src/main/scala/io/eezo/http/Csrf.scala:126`. |
| `Provenance`, `Provenance.Handwritten`, `Provenance.Derived` | `modules/http/src/main/scala/io/eezo/http/Route.scala:14`, `:15` | `Provenance.Derived` spelled at `examples/blog/src/main/scala/Main.scala:64`. `Handwritten` has no spelling and is the other case of the same enum. |
| `Route` | `modules/http/src/main/scala/io/eezo/http/Route.scala:24` | Spelled at `examples/blog/src/main/scala/Main.scala:7`. |
| `Route.provenance`, `Route.describe` | `modules/http/src/main/scala/io/eezo/http/Route.scala:30`, `:55` | Spelled at `examples/blog/src/main/scala/Main.scala:64`, `:68`. `Route.Http.provenance` (`Route.scala:36`) implements the first. |
| `Route.under` | `modules/http/src/main/scala/io/eezo/http/Route.scala:91` | Spelled at `examples/blog/src/main/scala/Main.scala:68`, `:73` and `README.md:55`. |
| `RouteTable` | `modules/http/src/main/scala/io/eezo/http/Route.scala:134` | Spelled at `examples/hello/src/main/scala/Main.scala:22`. |
| `RouteTable.apply` (companion) | `modules/http/src/main/scala/io/eezo/http/Route.scala:292` | Spelled at `examples/blog/src/main/scala/Main.scala:73`. Also emitted by the generator (`RouteGenerator.scala:483`, `:484`, `:503`). |
| `RouteTable.identify`, `RouteTable.routes`, `RouteTable.dispatch` | `modules/http/src/main/scala/io/eezo/http/Route.scala:134`, `:162`, `:196` | Spelled at `examples/blog/src/main/scala/Main.scala:73`, `:64` and `examples/blog/src/test/scala/UserSuite.scala:35`. |
| `Secret` | `modules/http/src/main/scala/io/eezo/http/Secret.scala:21` | Weak. No spelling. It is the result type of the override point `HttpApp.secret` (`HttpApp.scala:45`), so an application that overrides it writes the name. |
| `Session` | `modules/http/src/main/scala/io/eezo/http/Session.scala:25` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:16`, `:162`. |
| `Session.empty` | `modules/http/src/main/scala/io/eezo/http/Session.scala:103` | Spelled at `examples/blog/src/test/scala/PostOwnershipSuite.scala:162`. |
| `Session.get`, `Session.set`, `Session.remove`, `Session.flash(name)`, `Session.flash(name, value)` | `modules/http/src/main/scala/io/eezo/http/Session.scala:31`, `:33`, `:41`, `:93`, `:98` | Weak. The scaladoc at `Session.scala:21` says "the application sees entries and the two flash doors". No main source outside the declaring file reads any of them. Test readers: `modules/http/src/test/scala/io/eezo/http/SessionSuite.scala:40`, `:69`; `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:190`, `:477`. |

### Kind 2: written through inference

| Name | Declared at | Exposed by | Application call site |
|---|---|---|---|
| `client.Received` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:42` | The fields of `Reply.Ok`, `Reply.Denied`, `Reply.Failed` (`Http.scala:24`, `:27`, `:30`). | `examples/blog/src/main/scala/components/Callout.scala:31` binds `r` and reads `r.text`. |
| `Handler` | `modules/http/src/main/scala/io/eezo/http/Response.scala:157` | The `handler` parameter of `Route.Http` (`Route.scala:35`). | The generated lambda `req => app.api.Index.index(req)` at `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:13`, emitted by `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:410`. The alias itself is spelled only inside `io.eezo` (`modules/auth/src/main/scala/io/eezo/auth/Guard.scala:334`, `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:96`). |
| `Csrf.Token` | `modules/http/src/main/scala/io/eezo/http/Csrf.scala:31` | `Request.csrf` (`Request.scala:95`), `Csrf.hidden` (`Csrf.scala:64`), `Form.render` (`Form.scala:91`), `Csrf.Token.gen` (`Csrf.scala:42`). | Weak. No example holds one. The blog test scrapes the token as a `String` instead (`examples/blog/src/test/scala/PostOwnershipSuite.scala:163`, `:229`). The placement follows from `Request.csrf` and `Csrf.hidden` being kind 1 on weak evidence. |

### Kind 3: spliced

| Name | Declared at | Emitted by |
|---|---|---|
| `Actions` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:28`, `:33` | The `using actions: Actions[A]` clause of the inline `Resource.derived` (`modules/http/src/main/scala/io/eezo/http/Resource.scala:109`) is resolved at the `derives Resource` site, for example `examples/blog/src/main/scala/models/Post.scala:37`. The scaladoc at `Actions.scala:42` also expects an application to write `given Actions[Widget]`; no example does. |
| `LowPriorityActions` and its `given [A]: Actions[A]` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:48`, `:50` | The given is the instance the compiler picks for `Resource.derived`'s `Actions[A]` at every `derives Resource` site that declares none (`Post.scala:37`, `examples/todo/src/main/scala/models/Todo.scala:26`). The reference is reached through the object `Actions`, which extends the trait (`Actions.scala:33`). The trait's own name is never spelled outside `Actions.scala`. |
| `Field` givens for `String`, `Int`, `Boolean`, `Id[T]`, `Option[X]` | `modules/http/src/main/scala/io/eezo/http/Field.scala:66`, `:68`, `:77`, `:112`, `:118` | `summonAll[Tuple.Map[m.MirroredElemTypes, Field]]` inside the inline `Form.derived` (`modules/http/src/main/scala/io/eezo/http/Form.scala:166`), expanded at `derives Form` on `Post` (`examples/blog/src/main/scala/models/Post.scala:28` to `:36`: `Id`, `String`, `Int`, `Boolean`) and `Todo` (`examples/todo/src/main/scala/models/Todo.scala:19` to `:25`: `Option[String]`). |
| `Field` givens for `Long`, `Double`, `UUID`, `LocalDate`, `LocalDateTime`, `Instant` | `modules/http/src/main/scala/io/eezo/http/Field.scala:70`, `:72`, `:85`, `:90`, `:95`, `:100` | Weak. Same emitter (`Form.scala:166`), but no model in the examples or the docs that derives `Form` has a field of these types. `Field[UUID]` is also read inside the framework at `Field.scala:113`. |
| `Form.derived` | `modules/http/src/main/scala/io/eezo/http/Form.scala:162` | The compiler's expansion of `derives Form` (`examples/blog/src/main/scala/models/Post.scala:36`). It is inline and calls the private `Form.make` (`Form.scala:189`). |
| `Resource.derived` | `modules/http/src/main/scala/io/eezo/http/Resource.scala:106` | The compiler's expansion of `derives Resource` (`examples/blog/src/main/scala/models/Post.scala:37`). It is inline and calls the private `Resource.make` (`Resource.scala:159`) and the private inline `keyIndex` (`Resource.scala:131`). |
| `Resource.routesOf` | `modules/http/src/main/scala/io/eezo/http/Resource.scala:98` | `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:489`; generated at `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala` in `table()`. The generator's warning text (`RouteGenerator.scala:708`) and `docs/deploying.md:217` also tell the application to write it by hand. |
| `Resource.routes` | `modules/http/src/main/scala/io/eezo/http/Resource.scala:54` | The body of the inline `Resource.routesOf`, `r.routes(store, guarded)` at `Resource.scala:102`, which lands in the generated `Routes.table()`. |
| `Guarded.carries` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:78` | The body of the inline `Resource.routesOf`, `guarded.carries` at `Resource.scala:102`. |
| `Guarded.identify` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:79` | `RouteGenerator.scala:475` emits `<guard>.identify`. |
| `Guarded.mounting` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:96` | `RouteGenerator.scala:439` emits `guardFor[...].mounting(`. |
| `InMemoryStore` | `modules/http/src/main/scala/io/eezo/http/InMemoryStore.scala:18` | `RouteGenerator.scala:583`, `:586`, `:596`, `:597`. Also printed in `docs/how-to/persisting-a-model.md:26`. |
| `InMemoryStore.apply`, `InMemoryStore.scoped` | `modules/http/src/main/scala/io/eezo/http/InMemoryStore.scala:23`, `:50` | `RouteGenerator.scala:586`, `:597` (`apply`) and `:583`, `:596` (`scoped`). |
| `Owned.ownerOf` | `modules/http/src/main/scala/io/eezo/http/Owned.scala:32` | `RouteGenerator.scala:581`, `:583`, `:596` emit `o.ownerOf`. |
| `PathPattern` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:13`, `:99` | `RouteGenerator.scala:409` emits `io.eezo.http.PathPattern.parse(...)`. The type is also the type of the field `Route.Http.pattern` (`Route.scala:34`). |
| `PathPattern.parse` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:114` | `RouteGenerator.scala:409`; generated at `examples/hello/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:12`. |
| `Route.Http` (case and its synthesised `apply`) | `modules/http/src/main/scala/io/eezo/http/Route.scala:32` | `RouteGenerator.scala:407`; generated at `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:10`. |
| `RouteTable.naming` | `modules/http/src/main/scala/io/eezo/http/Route.scala:309` | `RouteGenerator.scala:473`. |
| `Scoped` | `modules/http/src/main/scala/io/eezo/http/Scoped.scala:21`, `:37` | `RouteGenerator.scala:579` emits `io.eezo.http.Scoped(` in the db arm of `storeFor`. It is also the result type of `InMemoryStore.scoped`. |
| `Scoped.apply` | `modules/http/src/main/scala/io/eezo/http/Scoped.scala:42` | `RouteGenerator.scala:579`. |
| `FromPath` | `modules/http/src/main/scala/io/eezo/http/Request.scala:14` | Weak. The `using from: FromPath[A]` clause of `Request.param` and `Request.paramOpt` (`Request.scala:127`, `:138`) is resolved wherever they are called. No application call site exists in the evidence. Spelled in prose at `docs/adr/0003-skeleton-one-landed-the-http-surface-later-tickets-had-already-decided.md:11`. |
| `FromPath.apply` and the `FromPath` givens for `String`, `Int`, `Long`, `UUID`, `Id[T]` | `modules/http/src/main/scala/io/eezo/http/Request.scala:15`, `:20`, `:22`, `:24`, `:26`, `:34` | Weak, same reasoning as `FromPath`. Framework readers: `Request.scala:139`; `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:507` (summons `FromPath[Id[U]]`); `modules/http/src/main/scala/io/eezo/http/Resource.scala:252`, `:400`, `:417` (through `param[Id[A]]`). |

### Kind 4: nobody's

Every test named here sits in a package inside `io.eezo`: `modules/http/src/test` in `io.eezo.http` or `io.eezo.http.client`, `modules/auth/src/test` in `io.eezo.auth`, `modules/live/src/test` in `io.eezo.live`, `modules/eezo/src/test` in `io.eezo`. The one test outside, `modules/http/src/test/scala/elsewhere/MiBVisibilitySuite.scala`, reads none of the names below.

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `InternalServerError` and its field `cause` | `modules/http/src/main/scala/io/eezo/http/Errors.scala:52` | `modules/http/src/main/scala/io/eezo/http/Boundary.scala:44`, `:51`; `modules/http/src/test/scala/io/eezo/http/BoundarySuite.scala:25`. | `private[http]` | It is one case of the sealed `EezoException`, which is kind 1. `docs/failures.md:19` to `:21` lists the six other cases and leaves this one out. No public signature mentions it. |
| `Actions` primary constructor and the synthesised `Actions.apply` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:28` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:36`, `:39`, `:50`. | `private[http]` (line 50 is in the trait `LowPriorityActions`, outside the companion) | `Actions` is a case class, so `copy` and `unapply` follow the constructor. |
| `Actions.allowed` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:28` | `modules/http/src/test/scala/io/eezo/http/ActionsSuite.scala:21`, `:25`, `:30`, `:36`. | `private[http]` | It is a case class field. |
| `Actions.has` | `modules/http/src/main/scala/io/eezo/http/Actions.scala:30` | `modules/http/src/main/scala/io/eezo/http/Ownership.scala:92`, `:166`; `modules/http/src/main/scala/io/eezo/http/Resource.scala:218`, `:224`, `:275`, `:429`. | `private[http]` | None found. |
| `client.Received` primary constructor and synthesised `apply` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:42` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:107`. | `private[client]` | The class is kind 2 and its fields `status` and `text` are kind 1. `copy` and `unapply` follow the constructor. |
| `client.Http` class constructor | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:72` | `modules/http/src/main/scala/io/eezo/http/client/Http.scala:135`, `:158` (both in the companion). | `private` | None found. `Http.withTimeout` is the public way to the same value. |
| `Guarded.actions` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:76` | `modules/http/src/main/scala/io/eezo/http/Owned.scala:58`; `Ownership.scala:93`; `Resource.scala:447`; `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:625`; `modules/auth/src/test/scala/io/eezo/auth/OwningSuite.scala:47`; `modules/eezo/src/test/scala/io/eezo/GeneratedGuardForSuite.scala:41`; `modules/http/src/test/scala/io/eezo/http/GuardedSuite.scala:32`. | `private[eezo]` | None found. |
| `Guarded.through` | `modules/http/src/main/scala/io/eezo/http/Guarded.scala:77` | `modules/http/src/main/scala/io/eezo/http/Owned.scala:58`; `Resource.scala:447`; `Guarded.scala:96`; `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:60`; `modules/http/src/test/scala/io/eezo/http/GuardedSuite.scala:36`. | `private[eezo]` | None found. |
| `InMemoryStore.owned` | `modules/http/src/main/scala/io/eezo/http/InMemoryStore.scala:42` | `modules/http/src/test/scala/io/eezo/http/OwnedInMemoryStoreSuite.scala:34`, `:43`. | `private[http]` | None found. The generator emits `scoped`, never `owned`. |
| `Method.parse` | `modules/http/src/main/scala/io/eezo/http/Method.scala:24` | `modules/http/src/main/scala/io/eezo/http/Request.scala:194`; `HttpServer.scala:394`; `modules/http/src/test/scala/io/eezo/http/MethodSuite.scala:9`. | `private[http]` | None found. |
| `Owned` constructor | `modules/http/src/main/scala/io/eezo/http/Owned.scala:28` | `modules/auth/src/main/scala/io/eezo/auth/Owning.scala:86`; `modules/auth/src/test/scala/io/eezo/auth/OwningSuite.scala:42`; `modules/eezo/src/test/scala/io/eezo/GeneratedGuardForSuite.scala:68`; `modules/http/src/test/scala/io/eezo/http/OwnedResourceSuite.scala:48`; `OwnershipSuite.scala:21`. | `private[eezo]` | The application obtains an `Owned` only through `io.eezo.auth.Owning.all`, `only`, `except` (`Post.scala:57`). |
| `Owned.currentUser`, `Owned.covers` | `modules/http/src/main/scala/io/eezo/http/Owned.scala:46`, `:49` | `modules/http/src/main/scala/io/eezo/http/Ownership.scala:92`, `:116`, `:129`, `:166`, `:234`; `modules/auth/src/test/scala/io/eezo/auth/OwningSuite.scala:46`, `:55`; `modules/http/src/test/scala/io/eezo/http/OwnedResourceSuite.scala:113`. | `private[eezo]` | None found. |
| `PathPattern.segments` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:13` | `PathPattern.scala:33`, `:61`, `:90`; `modules/http/src/main/scala/io/eezo/http/Resource.scala:504`. | `private[http]` | None found. |
| `PathPattern.matchPath` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:16` | `modules/http/src/main/scala/io/eezo/http/Route.scala:201`, `:228`; `modules/http/src/test/scala/io/eezo/http/PathPatternSuite.scala:9`. | `private[http]` | None found. |
| `PathPattern.subsumes` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:47` | `modules/http/src/main/scala/io/eezo/http/Resource.scala:497`; `Route.scala:257`, `:258`; `modules/http/src/test/scala/io/eezo/http/PathPatternSuite.scala:64`. | `private[http]` | None found. |
| `PathPattern.render` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:78` | `PathPattern.scala:96`; `modules/http/src/main/scala/io/eezo/http/Route.scala:56`, `:57`, `:95`; `Resource.scala:528`; `modules/http/src/test/scala/io/eezo/http/MountSuite.scala:118`. | `private[http]` | None found. `toString` returns the same text and cannot narrow. |
| `PathPattern.equals`, `PathPattern.hashCode`, `PathPattern.toString` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:89`, `:94`, `:96` | Not searched by name. | Cannot narrow | They override members of `Any`. |
| `PathPattern.Segment`, its cases `Static`, `Param`, `CatchAll` and their fields `Static.value`, `Param.name`, `CatchAll.name` | `modules/http/src/main/scala/io/eezo/http/PathPattern.scala:101` to `:105` | `PathPattern.scala:3` to `:129`; `modules/http/src/main/scala/io/eezo/http/Resource.scala:493`, `:507`, `:515`, `:516`. | `private[http]` | The public `val segments: Vector[Segment]` (`PathPattern.scala:13`) mentions it, and that member is itself in this table. |
| `Route.Http.method`, `Route.Http.pattern`, `Route.Http.handler` (fields) | `modules/http/src/main/scala/io/eezo/http/Route.scala:33` to `:35` | `modules/http/src/main/scala/io/eezo/http/Route.scala:56`, `:112`, `:201`, `:204`, `:256`; `Resource.scala:497`, `:502`, `:504`; `modules/http/src/main/scala/io/eezo/http/cli/RenderJson.scala:16`; `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:222`, `:225` (`copy(handler = ...)` and `route.handler`); `modules/http/src/test/scala/io/eezo/http/GuardedSuite.scala:43`; `modules/live/src/test/scala/io/eezo/live/BoundPageSuite.scala:31`. | `private[eezo]` | The constructor is kind 3, so the parameter types `Method`, `PathPattern` and `Handler` stay reachable. `copy`, `unapply` and `productElement` of the case still expose the values. |
| `Route.Ws` (case), its synthesised `apply`, and its fields `pattern`, `endpoint`, `provenance` | `modules/http/src/main/scala/io/eezo/http/Route.scala:42` to `:46` | `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:229`, `:230`, `:232`; `modules/live/src/main/scala/io/eezo/live/Live.scala:145`; `modules/http/src/main/scala/io/eezo/http/Route.scala:57`, `:114`, `:170`, `:226`, `:258`; `HttpServer.scala:313`; `modules/http/src/main/scala/io/eezo/http/cli/RenderJson.scala:17`; tests in `modules/auth`, `modules/http`. | `private[eezo]` | It is a case of the public enum `Route`, which the application holds and could match on. The public members `RouteTable.wsRoutes` and `RouteTable.dispatchWs` mention it, and both are in this table. Spelled in prose at `docs/failures.md:83`. Whether an enum case accepts an access modifier was not compiled. |
| `RouteTable` constructor | `modules/http/src/main/scala/io/eezo/http/Route.scala:134` | `modules/http/src/main/scala/io/eezo/http/Route.scala:269` (inside the class), `:293` (companion). | `private` | None found. `RouteTable.apply` is the spelling the application and the generator use. |
| `RouteTable.overridden` | `modules/http/src/main/scala/io/eezo/http/Route.scala:159` | `modules/http/src/main/scala/io/eezo/http/RouteReport.scala:18`; `modules/http/src/main/scala/io/eezo/http/cli/Commands.scala:17`; http tests (`RouteTableSuite.scala:126`). | `private[http]` | None found. |
| `RouteTable.shadowed` | `modules/http/src/main/scala/io/eezo/http/Route.scala:244` | `modules/http/src/main/scala/io/eezo/http/RouteReport.scala:18`; `modules/http/src/main/scala/io/eezo/http/cli/Commands.scala:18`; http tests (`RouteTableSuite.scala:173`). | `private[http]` | None found. |
| `RouteTable.httpRoutes`, `RouteTable.wsRoutes` | `modules/http/src/main/scala/io/eezo/http/Route.scala:168`, `:170` | `modules/http/src/main/scala/io/eezo/http/Route.scala:199`, `:227`, `:252`; `Resource.scala:501`; `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:620`; `modules/http/src/test/scala/io/eezo/http/RouteTableSuite.scala:23`, `:24`. | `private[eezo]`; `private[http]` without the auth test | None found. |
| `RouteTable.dispatchWs` | `modules/http/src/main/scala/io/eezo/http/Route.scala:226` | `modules/http/src/main/scala/io/eezo/http/HttpServer.scala:307`; `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:266`. | `private[eezo]`; `private[http]` without the auth test | None found. |
| `Scoped.by` | `modules/http/src/main/scala/io/eezo/http/Scoped.scala:34` | `modules/http/src/main/scala/io/eezo/http/Ownership.scala:143`; `modules/eezo/src/test/scala/io/eezo/GeneratedGuardForSuite.scala:92`, `:93`; `modules/http/src/test/scala/io/eezo/http/OwnedInMemoryStoreSuite.scala:23`. | `private[eezo]`; `private[http]` without the eezo test | None found. |
| `Scoped.all`, `Scoped.find`, `Scoped.insert`, `Scoped.update`, `Scoped.delete` | `modules/http/src/main/scala/io/eezo/http/Scoped.scala:23` to `:31` | Called through `io.eezo.core.Store`. | Cannot narrow | They implement the public trait `io.eezo.core.Store`. |
| `Secret.equals`, `Secret.hashCode`, `Secret.toString` | `modules/http/src/main/scala/io/eezo/http/Secret.scala:31`, `:36`, `:38` | Not searched by name. | Cannot narrow | They override members of `Any`. |
| `Form.without` | `modules/http/src/main/scala/io/eezo/http/Form.scala:124` | `modules/http/src/main/scala/io/eezo/http/Ownership.scala:110`; implemented at `Form.scala:206`. | `private[http]` | It is an abstract member of the public trait `Form`. No source outside `Form.scala` implements `Form`. |
| `Resource.apply` (summoner) | `modules/http/src/main/scala/io/eezo/http/Resource.scala:59` | http tests only: `modules/http/src/test/scala/io/eezo/http/GuardedSuite.scala:29`, `OwnedResourceSuite.scala:87`, `OwnerControlsSuite.scala:44`, `ResourceSuite.scala:66`. | `private[http]` | None found. |
| `WsConn` (opaque type and companion) | `modules/http/src/main/scala/io/eezo/http/Ws.scala:18`, `:20` | `modules/live/src/main/scala/io/eezo/live/Live.scala:14`, `:212`, `:254`; `modules/http/src/main/scala/io/eezo/http/Ws.scala:57`, `:59`, `:84`, `:89`; `modules/http/src/test/scala/io/eezo/http/WebSocketSuite.scala:101`; `DrainSuite.scala:169`. | `private[eezo]` | `WsListener.onOpen` and `WsListener.onText` mention it. Spelled in prose at `docs/adr/0003-skeleton-one-landed-the-http-surface-later-tickets-had-already-decided.md:37`. |
| `WsConn.close`, `WsConn.send` (extensions) | `modules/http/src/main/scala/io/eezo/http/Ws.scala:28`, `:34` | `modules/live/src/main/scala/io/eezo/live/Live.scala:222`, `:225`, `:227`, `:234`, `:237`, `:247`, `:259`, `:278`, `:280`. | `private[eezo]` | None beyond `WsConn` itself. |
| `WsConn.isOpen` (extension) | `modules/http/src/main/scala/io/eezo/http/Ws.scala:26` | No reader found in any main or test source. The `isOpen` calls in `WebSocketSuite.scala:240`, `:261` are on another value named `session`; whether that value is a `WsConn` was not checked. | `private`, or `private[http]` if the test reads it | None found. |
| `WsListener` and its members `onOpen`, `onText`, `onClose`, `onError` | `modules/http/src/main/scala/io/eezo/http/Ws.scala:55` to `:63` | `modules/live/src/main/scala/io/eezo/live/Live.scala:201`, `:202`, `:212`, `:254`, `:292`, `:301`; `modules/http/src/main/scala/io/eezo/http/Reload.scala:55`, `:60`; `Ws.scala:78` to `:96`; tests in `modules/http` and `modules/auth` (`GuardSuite.scala:262`). | `private[eezo]` | The field `Route.Ws.endpoint: Request => WsListener` (`Route.scala:44`) mentions it, and `Route.Ws` is in this table. Spelled in prose at `docs/how-to/persisting-a-model.md:244`. |

Cascade notes.

* `PathPattern` cannot narrow as a type: `PathPattern.parse` is spliced by the generator and `Route.Http`'s constructor, also spliced, takes one. Only its members narrow.
* `WsConn`, `WsListener` and `Route.Ws` form one group. Each blocks the others, and nothing in kinds 1 to 3 mentions any of them except the enum `Route` that contains the case.
* `Handler` stays because `Route.Http`'s spliced constructor takes one.
* `Secret` stays because `HttpApp.secret` returns one. `HttpConfig` is already `private[eezo]` and mentions `Secret` and `Problem`.
* `Guarded`'s constructor is read by the public class `io.eezo.auth.GuardedBy` (`modules/auth/src/main/scala/io/eezo/auth/Owning.scala:30`), which extends `Guarded` from another module. See the next section.

### Fits no kind cleanly

Each entry is public, has no spelling in an example, the README or the docs, and is something an application could plausibly reach for. Readers and the modifier they would allow are given as facts only.

* **`Request.as[A]` and `Request.as[A](key)`** (top level extension, `modules/http/src/main/scala/io/eezo/http/Form.scala:325`, `:328`). The scaladoc calls it "decoding a request body into a model". Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:357`, `modules/http/src/test/scala/io/eezo/http/FormSuite.scala:319`, `:331`. The readers allow `private[eezo]`.
* **`Form.apply`, `Form.fields`, `Form.show`, `Form.render`, `Form.parse`** (`Form.scala:129`, `:61`, `:70`, `:87`, `:108`). The scaladoc of `render` (`Form.scala:75`) names `request.csrf` inside a handler and `Csrf.Token.gen()` in a test, which is how `Token.gen` justifies being public. No example calls any of them: every example form is derived and rendered by `Resource`. Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:336`; `modules/http/src/main/scala/io/eezo/http/Ownership.scala:63`, `Resource.scala:269`, `:272`, `:300`, `:338`; http tests. The readers allow `private[eezo]`. The four instance members are abstract members of a public trait.
* **`FormErrors` (with `errors`, `isEmpty`, `of`), `FormErrors.empty`, `FieldError` (with `name`, `message`), `FormField` (with `name`, `label`, `inputType`)** (`Form.scala:29`, `:35`, `:21`, `:18`). Readers are `Form.scala` itself and `modules/http/src/test/scala/io/eezo/http/FormSuite.scala:140`, `:148`, which allow `private[http]`. The public signatures of `Form.render`, `Form.parse`, `Form.fields`, `Form.show` and `Request.as` mention them, so they follow whatever is decided for those. The scaladoc at `Form.scala:13` says `FormField` is "published rather than internal on purpose".
* **`Field.inputType`, `Field.show`, `Field.absent`** (`modules/http/src/main/scala/io/eezo/http/Field.scala:32`, `:35`, `:43`). Members of a trait the application names. `Field.of` builds an instance without the application writing any of them, but an instance with a custom `absent` has to be written as `new Field[X] { ... }`, as the framework does at `Field.scala:77`. Readers: `Form.scala:201`, `:215`, `:289`; `modules/auth/src/test/scala/io/eezo/auth/PasswordSuite.scala:29`, `:30`. The readers allow `private[eezo]`.
* **`Cookie.name`, `Cookie.value`, `Cookie.secure`, `Cookie.render`** (`modules/http/src/main/scala/io/eezo/http/Cookie.scala:20` to `:22`, `:32`). Fields and the renderer of a value the application builds. Readers: `Response.scala:59` (`render`); `modules/http/src/test/scala/io/eezo/http/CookieSuite.scala:13`. The readers allow `private[http]`.
* **`Csrf.Token.value`** (extension, `modules/http/src/main/scala/io/eezo/http/Csrf.scala:49`). The only way to turn a token into text. Readers are tests only: `modules/http/src/test/scala/io/eezo/http/ResourceFixtures.scala:30`, `CsrfSuite.scala:27`, `modules/http/src/test/scala/io/eezo/http/client/ClientSuite.scala:32`, `modules/auth/src/test/scala/io/eezo/auth/GuardSuite.scala:530`. They allow `private[eezo]`. The blog test avoids it by scraping the page (`examples/blog/src/test/scala/PostOwnershipSuite.scala:229`).
* **`client.Auth.basic`** (`modules/http/src/main/scala/io/eezo/http/client/Http.scala:56`). Sibling of `Auth.bearer`, which is kind 1. Only reader: `modules/http/src/test/scala/io/eezo/http/client/ClientSuite.scala:79`.
* **`client.Http.put`, `client.Http.delete`, `client.Http.withTimeout`, `client.Http.DefaultTimeout`** (object, `Http.scala:149`, `:139`, `:158`, `:133`) **and the instance methods `get`, `delete`, `post`, `put`** (class, `Http.scala:81`, `:84`, `:87`, `:93`). `io.eezo.live.Async` wraps only `get` and `post`. Readers: `Http.scala:135` to `:158` (the object delegates to the instance); `ClientSuite.scala:97` (`withTimeout(...).get`). No reader of `Http.put` or `Http.delete` was found anywhere. The instance methods are reachable by the application only through `withTimeout`.
* **`client.Received.headers`, `client.Received.body`, `client.Received.header`** (`Http.scala:42`, `:47`). Siblings of `text` and `status`, which are kind 1. Readers: `Http.scala:44`, `:48`; `ClientSuite.scala:116`.
* **`Request.secure`** (`modules/http/src/main/scala/io/eezo/http/Request.scala:66`). A case class field with a default. The scaladoc at `Request.scala:47` says it decides a cookie's `Secure` attribute. Readers: `modules/http/src/main/scala/io/eezo/http/Session.scala:198`; `modules/live/src/main/scala/io/eezo/live/Origins.scala:99`; written at `HttpServer.scala:426`. The readers allow `private[eezo]`.
* **`Response.withHeader`** (`modules/http/src/main/scala/io/eezo/http/Response.scala:53`). Readers: `modules/http/src/main/scala/io/eezo/http/Boundary.scala:70`; `Response.scala:59`; `modules/http/src/test/scala/io/eezo/http/ResponseSuite.scala:33`. They allow `private[http]`. The blog example builds its headers in the constructor instead (`examples/blog/src/main/scala/app/api/Index.scala:18`).
* **`Response.Redirect(location: Url)`** (`Response.scala:125`). The overload of a kind 1 method. Readers: `modules/auth/src/main/scala/io/eezo/auth/Guard.scala:318`, `:368`, `:406`; `modules/http/src/main/scala/io/eezo/http/Resource.scala:346`, `:420`. They allow `private[eezo]`.
* **`RouteTable.++`, `RouteTable.empty`** (`modules/http/src/main/scala/io/eezo/http/Route.scala:268`, `:295`). The blog example concatenates `Seq[Route]` and calls `RouteTable(...)` rather than `++` (`examples/blog/src/main/scala/Main.scala:73`). Readers of `++`: `modules/http/src/main/scala/io/eezo/http/HttpApp.scala:73`; `modules/live/src/test/scala/io/eezo/live/BoundPageSuite.scala:45`; `modules/eezo/src/test/scala/io/eezo/BoundLivePageSuite.scala:61`. Readers of `empty`: `Route.scala` has none; `modules/eezo/src/test/scala/io/eezo/EezoAppSuite.scala:203`; `modules/http/src/test/scala/io/eezo/http/HealthSuite.scala:18`. Both sets allow `private[eezo]`.
* **`Guarded` constructor** (`modules/http/src/main/scala/io/eezo/http/Guarded.scala:75`). The scaladoc at `Guarded.scala:8` says any other way of signing in "has to produce one of these", which reads as an invitation to construct one outside `modules/auth`. Readers: `modules/auth/src/main/scala/io/eezo/auth/Owning.scala:30` (the public class `GuardedBy` extends it); `modules/http/src/main/scala/io/eezo/http/Owned.scala:58`; `Guarded.scala:113`; tests in `modules/http`. They allow `private[eezo]`.
* **`Problem` primary constructor and fields `tpe`, `title`, `status`, `detail`, `instance`** (`modules/http/src/main/scala/io/eezo/http/Problem.scala:15` to `:21`). The docs spell only the three argument `apply`. An application test of its own `problems` hook would read the fields. Readers: `modules/http/src/main/scala/io/eezo/http/Boundary.scala:65`, `:93` to `:98`; `HttpServer.scala:272`; `Problem.scala:27`; `modules/http/src/test/scala/io/eezo/http/BoundarySuite.scala:30`, `:52`; `CsrfSuite.scala:216`. They allow `private[http]`.
* **`Secret.parse`, `Secret.throwaway`, `Secret.fromEnv`, `Secret.EnvVar`** (`modules/http/src/main/scala/io/eezo/http/Secret.scala:60`, `:73`, `:87`, `:48`). The class constructor is private, so these three methods are the only way to build the value an override of `HttpApp.secret` must return. Readers: `modules/http/src/main/scala/io/eezo/http/HttpApp.scala:45` (`fromEnv`); `HttpConfig.scala:28` (`throwaway`); `Secret.scala:88` (`parse`, `EnvVar`); http tests (`SessionSuite.scala:12`, `:33`). They allow `private[http]`, and `private` for `EnvVar`. The sbt plugin keeps its own copy of the variable name (`modules/sbt-plugin/src/main/scala/io/eezo/sbt/DevProcess.scala:35`).
* **`Session.isEmpty`** (`modules/http/src/main/scala/io/eezo/http/Session.scala:90`). Sibling of `get`, `set` and `remove`. Its scaladoc names `Csrf.protect` as "the one caller that matters". Readers: `modules/http/src/main/scala/io/eezo/http/Csrf.scala:127`; `modules/http/src/test/scala/io/eezo/http/SessionSuite.scala:44`; `CsrfSuite.scala:139`. They allow `private[http]`.

### Method and caveats

What was read in full.

* Every file under `modules/http/src/main/scala/io/eezo/http` except the four in `cli`: `Actions`, `Boundary`, `Cookie`, `Csrf`, `Errors`, `Field`, `Form`, `Guarded`, `HttpApp`, `HttpConfig`, `HttpServer`, `InMemoryStore`, `Method`, `Owned`, `Ownership`, `PathPattern`, `Problem`, `Reload`, `Request`, `Resource`, `Response`, `Route`, `RouteReport`, `Scoped`, `Secret`, `Session`, `Ws`, and `client/Http`.
* `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala`, all 758 lines.
* Every Scala file under `examples/*/src/main` and `examples/blog/src/test`, with comment lines filtered out of the listing.
* `modules/auth/src/main/scala/io/eezo/auth/Owning.scala`, `docs/failures.md` from line 15, and part of `modules/live/src/main/scala/io/eezo/live/Component.scala`.
* The generated `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala` in part. Generated sources exist for hello, todo and blog. None exists for reminders, which has no http edge.

What was searched.

* ripgrep for each name or member pattern over `examples`, `modules/example`, `README.md` and `docs` (research included), with `target` excluded, for spellings.
* ripgrep over `modules/*/src/main` and `modules/*/src/test` for readers, with scaladoc and comment lines filtered out. Reader lists for names with many test readers give one or two lines per file rather than every line.
* ripgrep over `modules/sbt-plugin/src/main`: `EezoPlugin.scala`, `DevProcess.scala` and `Deploy.scala` contain no `io.eezo.http` name, so `RouteGenerator.scala` is the only emitter.
* ripgrep for `inline` and `scala.quoted` in `modules/auth`, `modules/live`, `modules/eezo`, `modules/testkit` main sources. The only inline methods outside http are `io.eezo.auth.GuardedBy.owning` and `io.eezo.auth.Selector.nameOf`, and neither body names an http declaration.
* `modules/example` and its test contain no `io.eezo.http` name.

What is excluded by the definition of public.

* Already narrower than public, so not sorted: `Boundary`, `HttpConfig`, the `MiB` extension, `HttpServer`, `Ownership`, `Reload`, `Orphan`, `RouteReport`, `SessionCookie`, `JettyListener`, `Resource.orphaned`, `Route.derived`, `RouteTable.ReservedPrefix`, `Field.PasswordInput`, `Csrf.read`, `Csrf.carrying`, `Csrf.Entry`, `Csrf.protect`, `Session.reserved`, `Session.withReserved`, `Session.withoutReserved`, `Session.Reserved`, the `Session` constructor and its three fields, the `Cookie` primary constructor and `Cookie.maxAge`, `Cookie.parse`, `WsConn.apply`, `PathPattern.dropLast`, `Body.mapHtml`, `Response.under`, `Response.HtmlContentType`, `Response.asUrl`, `Response.renderUrl`, `InMemoryStore.narrowing`, `Request.isFormEncoded` and the four `private[http]` members of the `Request` companion, `Problem.phrase`, `Form.reserved`, `Secret.sign`, `Http.LoopbackSpared`, `Http.LoopbackSparing`.
* The protected members of `HttpApp` are outside the task's definition and are not counted: `frameworkRoutes`, `serve`, `devServer`, `program`, `commands`, `usage` (`modules/http/src/main/scala/io/eezo/http/HttpApp.scala:65` to `:99`). They are still reachable from an application that extends the trait, and the scaladoc at `HttpApp.scala:59` expects a user to call `serve` from an overridden `boot`. Their signatures mention `Route`, `RouteTable` and `io.eezo.core.Dispatch.Usage`.

What could not be verified.

* Nothing was compiled and sbt was not run. Every narrowest modifier is what the readers found by text search allow, not what the compiler accepts.
* Whether a public object may extend a narrowed `LowPriorityActions` while the given it inherits is still resolved from application code was not compiled.
* Whether an enum case (`Route.Ws`, `InternalServerError` is a plain case class and is not affected) or an enum case field accepts an access modifier was not compiled.
* The inline methods `Form.derived` and `Resource.derived` call private methods (`Form.make`, `Resource.make`). The compiler generates accessors for those; their names and visibility were not inspected.
* Reader searches for very common member names (`status`, `name`, `value`, `body`, `headers`, `render`, `show`, `read`, `get`, `set`) were confirmed by reading the hit lines in the files where an http receiver was likely. A reader reached through a differently named local value in a file that was not opened could have been missed.
* Line numbers in generated sources under `target` reflect whatever build last produced them, which may not be commit 756d1ed.
* Spellings in prose (ADRs and how-to text) are reported as prose. They describe the framework and are not code an application writes.
* `Cookie` was given as a known kind 1 name. No spelling of it exists in the examples, the README or the docs, so its row rests on scaladoc alone.

---

## db

Module `modules/db/src/main/scala`, package `io.eezo.db` and subpackages, at commit 756d1ed on `main`.
Packages `io.eezo.db.cli` and `io.eezo.db.internal` are skipped, and so are the four `toJson` methods in `db/schema/Snapshot.scala`.

Path legend, used in every table below to keep rows readable. Both prefixes are repo relative.

* `DB/` stands for `modules/db/src/main/scala/io/eezo/db/`
* `DBT/` stands for `modules/db/src/test/scala/io/eezo/db/`
* `Tour` stands for `modules/example/src/test/scala/example/Tour.scala`

Every file under `DBT/` declares a package inside `io.eezo.db` (checked file by file, see Method). Every file under `modules/eezo/src` declares `package io.eezo`.

"Tour only" in an evidence cell means that the one application spelling found is in `Tour`, which is the test source of `modules/example` (package `example`, outside `io.eezo`). No example under `examples/`, no README and no file under `docs/` spells that name. The section "Dependence on Tour" in Method and caveats gives the readers and the modifier that apply if `Tour` is not counted.

### Counts

A class or trait and its companion object count as one top level name. The eleven extension methods in `DB/Crud.scala` are top level declarations and count one each. The two exported aliases in `DB/Exports.scala` count one each, separately from the two aliases they export.

| Kind | Top level names | Members |
|---|---|---|
| 1. Written by the application | 27 | 66 |
| 2. Written through inference | 9 | 1 |
| 3. Spliced | 8 | 32 |
| 4. Nobody's | 10 | 90 |
| Fits no kind cleanly | 11 | 55 |
| Total | 65 | 244 |

Of the 27 top level names in kind 1, 10 rest on `Tour` only. Of the 66 members in kind 1, 37 rest on `Tour` only. Of the 9 top level names in kind 2, 4 rest on `Tour` only (`PgType`, `Applied`, `ColumnSnap`, `TableSnap`).

### Kind 1: written by the application

#### Top level

| Name | Declared at | Evidence |
|---|---|---|
| `io.eezo.db.Tx` (exported alias) | `DB/Exports.scala:10` | `modules/example/src/main/scala/example/AccessProbe.scala:14` writes `(using Tx)` under `import io.eezo.db.*` |
| `io.eezo.db.DB` (exported alias) | `DB/Exports.scala:10` | Weaker evidence. No application spells it. The `implicitNotFound` message at `DB/capability/Capability.scala:11` tells the user to write `(using DB)`. |
| `io.eezo.db.capability.Tx` | `DB/capability/Capability.scala:30` | Target of the export above. `examples/blog/src/main/scala/models/User.scala:8` mentions the package `capability` in a comment only. |
| `io.eezo.db.capability.DB` | `DB/capability/Capability.scala:29` | Target of the export above. Weaker evidence, same as `io.eezo.db.DB`. |
| `Check` | `DB/Check.scala:3` | `modules/example/src/main/scala/example/Title.scala:15` |
| `Column` (trait and object) | `DB/Column.scala:9`, `:42` | `examples/blog/src/main/scala/models/User.scala:37` |
| `DbApp` | `DB/DbApp.scala:40` | `examples/reminders/src/main/scala/Main.scala:23`, `examples/blog/src/main/scala/CreateUser.scala:32`, `examples/blog/src/test/scala/PostOwnershipSuite.scala:100`, `README.md:11` |
| `Ref` (opaque type and object) | `DB/Ref.scala:15`, `:17` | `modules/example/src/main/scala/example/Author.scala:10`, `modules/example/src/main/scala/example/Book.scala:9` |
| `Schema` (abstract class and object) | `DB/Schema.scala:43`, `:102` | `examples/reminders/src/main/scala/AppSchema.scala:9`, `examples/blog/src/main/scala/Main.scala:45`, `docs/deploying.md:80` |
| `Scopes` | `DB/Scopes.scala:14` | `examples/reminders/src/main/scala/Main.scala:5` (`import io.eezo.db.Scopes.*`) |
| `Table` (trait and object) | `DB/Table.scala:13`, `:49` | `examples/reminders/src/main/scala/models/Reminder.scala:21`, `docs/deploying.md:61` |
| `insert` (extension on `Table[T]`) | `DB/Crud.scala:17` | `examples/blog/src/main/scala/CreateUser.scala:60`, `examples/reminders/src/main/scala/Main.scala:48`, `docs/failures.md:57` |
| `update` (extension) | `DB/Crud.scala:33` | `examples/reminders/src/main/scala/Main.scala:60` |
| `findById` (extension) | `DB/Crud.scala:76` | `examples/blog/src/main/scala/models/User.scala:56`, `examples/blog/src/test/scala/PostOwnershipSuite.scala:270` |
| `query` (extension) | `DB/Crud.scala:87` | `examples/reminders/src/main/scala/Main.scala:47` |
| `where` (extension) | `DB/Crud.scala:89` | `examples/reminders/src/main/scala/Main.scala:53`, `examples/blog/src/main/scala/models/User.scala:46` |
| `orderBy` (extension) | `DB/Crud.scala:90` | Weaker evidence. `examples/reminders/src/main/scala/Main.scala:55` calls `orderBy` on a `Query`, which is `Query.orderBy`. No application calls the extension on a `Table` directly. It is the twin of `where`. |
| `DeployCheck` | `DB/migrate/DeployCheck.scala:10` | Tour only, `Tour:333` |
| `Resolution` | `DB/migrate/Freeze.scala:9` | Tour only, `Tour:294`, `Tour:304` |
| `Decision` | `DB/migrate/Freeze.scala:16` | Tour only, `Tour:294`. Also read by `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:166`. |
| `Freeze` | `DB/migrate/Freeze.scala:22` | Tour only, `Tour:292`, `Tour:346` |
| `Migrator` | `DB/migrate/Migrator.scala:17` | Tour only, `Tour:325`, `Tour:331`, `Tour:379` |
| `Change` | `DB/schema/Change.scala:3` | Tour only, `Tour:451`. Also read by `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:80`. |
| `Ddl` | `DB/schema/Ddl.scala:9` | Tour only, `Tour:228`, `Tour:266` |
| `Differ` | `DB/schema/Differ.scala:3` | Tour only, `Tour:213` |
| `Introspect` | `DB/schema/Introspect.scala:5` | Tour only, `Tour:430` |
| `SchemaSnap` | `DB/schema/Snapshot.scala:47` | Tour only, `Tour:288`, `Tour:294`, `Tour:430` |

#### Members

| Name | Declared at | Evidence |
|---|---|---|
| `Check.MaxLen` (with its field `n`) | `DB/Check.scala:5` | `modules/example/src/main/scala/example/Title.scala:15` |
| `Col.===` | `DB/Col.scala:18` | `examples/reminders/src/main/scala/Main.scala:53`, `examples/blog/src/main/scala/models/User.scala:46` |
| `Col.<=` | `DB/Col.scala:21` | `examples/reminders/src/main/scala/Main.scala:54` |
| `Col.asc` | `DB/Col.scala:28` | `examples/reminders/src/main/scala/Main.scala:55` |
| `Column.apply` (object) | `DB/Column.scala:43` | `examples/blog/src/main/scala/models/User.scala:37` (`Column[String]`) |
| `Column.imap` | `DB/Column.scala:17` | `examples/blog/src/main/scala/models/User.scala:37` |
| `Column.withCheck` | `DB/Column.scala:33` | `modules/example/src/main/scala/example/Title.scala:15` |
| `ColumnDef.name`, `ColumnDef.pgType`, `ColumnDef.nullable`, `ColumnDef.primaryKey`, `ColumnDef.checks`, `ColumnDef.references` | `DB/ColumnDef.scala:11` to `:16` | Tour only, `Tour:111`, `Tour:112` |
| `PgType.render` | `DB/PgType.scala:16` | Tour only, `Tour:111` |
| `DbApp.schema` | `DB/DbApp.scala:45` | `examples/reminders/src/main/scala/Main.scala:25`, `docs/deploying.md:90`, `docs/live.md:253` |
| `DbApp.databaseSchema` | `DB/DbApp.scala:50` | `examples/reminders/src/main/scala/Main.scala:32`, `examples/blog/src/main/scala/Main.scala:52` |
| `DbApp.boot` | `DB/DbApp.scala:56` | `examples/reminders/src/main/scala/Main.scala:42`, `examples/blog/src/main/scala/CreateUser.scala:46` |
| `DbInit.databaseUrl`, `DbInit.databaseUser`, `DbInit.databasePassword` | `DB/DbInit.scala:35`, `:41`, `:44` | `examples/blog/src/test/scala/PostOwnershipSuite.scala:102` to `:104`, `modules/example/src/main/scala/example/Cli.scala:20` to `:22`, named in prose at `docs/how-to/persisting-a-model.md:174` |
| `DbInit.databaseInit` | `DB/DbInit.scala:71` | `examples/reminders/src/main/scala/Main.scala:34`, `examples/todo/src/main/scala/Main.scala:29` |
| `DbInit.databasePoolSize` | `DB/DbInit.scala:53` | Weaker evidence. No application overrides it. It is an override point on a trait every `DbApp` extends. `docs/deploying.md:162` documents the environment variable it reads, not the member. Overridden in `DBT/engine/ConnectionUnavailableSuite.scala:150`. |
| `DbInit.databaseAcquireTimeout` | `DB/DbInit.scala:61` | Weaker evidence, same as above. Overridden in `DBT/engine/ConnectionUnavailableSuite.scala:151`. The refusal at `DB/engine/Pool.scala:47` names `databaseAcquireTimeout` as "the setting the user wrote". `docs/deploying.md:164` documents the environment variable. |
| `Query.where` | `DB/Query.scala:20` | `examples/reminders/src/main/scala/Main.scala:54` (second `where` in the chain) |
| `Query.orderBy` | `DB/Query.scala:21` | `examples/reminders/src/main/scala/Main.scala:55` |
| `Query.list` | `DB/Query.scala:43` | `examples/reminders/src/main/scala/Main.scala:56` |
| `Query.first` | `DB/Query.scala:50` | `examples/blog/src/main/scala/models/User.scala:46` |
| `Query.count` | `DB/Query.scala:52` | `examples/reminders/src/main/scala/Main.scala:47` |
| `Ref.apply` | `DB/Ref.scala:18` | Tour only, `Tour:181` |
| `Ref.to` | `DB/Ref.scala:19` | `modules/example/src/main/scala/example/Author.scala:15`, `modules/example/src/main/scala/example/Book.scala:21` |
| `TableSpec.index` | `DB/Schema.scala:27` | `examples/reminders/src/main/scala/AppSchema.scala:10`, `examples/todo/src/main/scala/AppSchema.scala:10` |
| `TableSpec.unique` | `DB/Schema.scala:30` | `examples/blog/src/main/scala/AppSchema.scala:17`, `docs/deploying.md:81` |
| `Schema.snapshot` | `DB/Schema.scala:87` | Tour only, `Tour:213`. Also read by `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:85`. |
| `Schema.ddl` | `DB/Schema.scala:96` | Tour only, `Tour:128`. `docs/how-to/persisting-a-model.md:129` and `:182` name `JournalSchema.ddl` and `Schema.ddl` in prose. |
| `Scopes.transact` | `DB/Scopes.scala:21` | `examples/reminders/src/main/scala/Main.scala:46`, `docs/failures.md:56` |
| `Scopes.read` | `DB/Scopes.scala:39` | `examples/blog/src/main/scala/models/User.scala:46` |
| `Scopes.attempt` | `DB/Scopes.scala:64` | `docs/failures.md:57`. No example calls it. |
| `Scopes.Attempt.apply` | `DB/Scopes.scala:98` | `docs/failures.md:57` (the braces after `attempt[SQLException]`) |
| `Table.apply` (object) | `DB/Table.scala:50` | `examples/reminders/src/main/scala/Main.scala:43` (`Table[Reminder]`) |
| `Table.insertSql` | `DB/Table.scala:36` | Tour only, `Tour:146`, `Tour:438` |
| `Table.selectAllSql` | `DB/Table.scala:37` | Tour only, `Tour:157` |
| `DeployCheck.verify` | `DB/migrate/DeployCheck.scala:11` | Tour only, `Tour:333` |
| `Resolution.change`, `Resolution.decision` | `DB/migrate/Freeze.scala:9` | Tour only, written positionally in the constructor call at `Tour:294` |
| `Decision.Accept` | `DB/migrate/Freeze.scala:17` | Tour only, `Tour:294` |
| `Freeze.existing` | `DB/migrate/Freeze.scala:41` | Tour only, `Tour:346` |
| `Freeze.write` | `DB/migrate/Freeze.scala:63` | Tour only, `Tour:292`, `Tour:302` |
| `Applied.number` | `DB/migrate/Migrator.scala:8` | Tour only, `Tour:379` |
| `Migrator.applied` | `DB/migrate/Migrator.scala:32` | Tour only, `Tour:379` |
| `Migrator.status` | `DB/migrate/Migrator.scala:61` | Tour only, `Tour:325` |
| `Migrator.apply` | `DB/migrate/Migrator.scala:96` | Tour only, `Tour:331`, `Tour:372` |
| `Migrator.Status.Ok` and its field `pending` | `DB/migrate/Migrator.scala:56` | Tour only, `Tour:327` |
| `Migrator.Status.Tampered` and its field `problems` | `DB/migrate/Migrator.scala:57` | Tour only, `Tour:326` |
| `Change.destructive`, `Change.risky`, `Change.describe` | `DB/schema/Change.scala:17`, `:23`, `:31` | Tour only, `Tour:452`, `Tour:264`, `Tour:216` |
| `Ddl.render(c: Change)` | `DB/schema/Ddl.scala:18` | Tour only, `Tour:266` |
| `Ddl.render(cs: List[Change])` | `DB/schema/Ddl.scala:62` | Tour only, `Tour:228` |
| `Differ.diff` | `DB/schema/Differ.scala:6` | Tour only, `Tour:213` |
| `Introspect.snapshot` | `DB/schema/Introspect.scala:32` | Tour only, `Tour:430` |
| `ColumnSnap.name` | `DB/schema/Snapshot.scala:8` | Tour only, `Tour:289` |
| `TableSnap.name`, `TableSnap.columns` | `DB/schema/Snapshot.scala:37` | Tour only, `Tour:289`, `Tour:376` |
| `SchemaSnap.tables` | `DB/schema/Snapshot.scala:47` | Tour only, `Tour:288`. Also read by `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:85`. |

Protected members are outside the task's definition of public, so they are not counted. Two of them are written by applications all the same: `Schema.table` (`DB/Schema.scala:46`, written at `examples/reminders/src/main/scala/AppSchema.scala:10`) and `DbApp.withDatabase` (`DB/DbApp.scala:61`, written at `examples/blog/src/test/scala/PostOwnershipSuite.scala:112`). `DbInit.database` (`DB/DbInit.scala:73`) is protected too and matters for `Database` in kind 4.

### Kind 2: written through inference

| Name | Declared at | Exposed by | Application call site |
|---|---|---|---|
| `Col` (class) | `DB/Col.scala:13` | `ColsOf[T]` in `where(f: ColsOf[T] -> Expr[T])` at `DB/Crud.scala:89` and in `TableSpec.index` at `DB/Schema.scala:27` | `examples/reminders/src/main/scala/Main.scala:53` (`_.sent` is a `Col[Reminder, Boolean]`), `examples/reminders/src/main/scala/AppSchema.scala:10` |
| `Expr` (enum and object) | `DB/Expr.scala:16`, `:33` | Result type of `Col.===` at `DB/Col.scala:18` and of the lambda taken by `where` | `examples/reminders/src/main/scala/Main.scala:53`, `examples/blog/src/main/scala/models/User.scala:46` |
| `Order` | `DB/Expr.scala:60` | Result type of `Col.asc` at `DB/Col.scala:28` and of the lambda taken by `orderBy` at `DB/Query.scala:21` | `examples/reminders/src/main/scala/Main.scala:55` |
| `Query` | `DB/Query.scala:12` | Result type of `query`, `where`, `orderBy` at `DB/Crud.scala:87` to `:90` | `examples/reminders/src/main/scala/Main.scala:47`, `:52` to `:56` |
| `TableSpec` | `DB/Schema.scala:7` | Result type of `Schema.table` at `DB/Schema.scala:46` and of `index` and `unique` | `examples/reminders/src/main/scala/AppSchema.scala:10` (the `val reminders` holds one) |
| `PgType` | `DB/PgType.scala:3` | `ColumnDef.pgType` at `DB/ColumnDef.scala:12`, `Column.pgType` at `DB/Column.scala:10` | Tour only, `Tour:111` (`c.pgType.render`) |
| `Applied` | `DB/migrate/Migrator.scala:8` | Result type of `Migrator.applied` at `DB/migrate/Migrator.scala:32` | Tour only, `Tour:379` |
| `ColumnSnap` | `DB/schema/Snapshot.scala:7` | `TableSnap.columns` at `DB/schema/Snapshot.scala:37` | Tour only, `Tour:289` (`t.columns.filterNot(_.name == "isbn")`) |
| `TableSnap` | `DB/schema/Snapshot.scala:37` | `SchemaSnap.tables` at `DB/schema/Snapshot.scala:47` | Tour only, `Tour:288` and `:289` (`t.copy(columns = ...)`), `Tour:376` |
| `Migrator.Status` (sealed trait, member of `Migrator`) | `DB/migrate/Migrator.scala:54` | Result type of `Migrator.status` at `DB/migrate/Migrator.scala:61` | Tour only, `Tour:325` (scrutinee of the match) |

### Kind 3: spliced

#### What each expansion names

`Table.derived` (`DB/Table.scala:51`) is `inline` and its body is the splice `${ TableMacro.derive[T] }`. The compiler writes `Table.derived[T]` at every `derives Table` clause, for example `examples/reminders/src/main/scala/models/Reminder.scala:21`. The tree that `TableMacro.derive` returns (`DB/macros/TableMacro.scala:117` to `:128`) references these framework names inside the application:

* `Table[T]`, as the parent of an anonymous class (`TableMacro.scala:118`), with definitions of `tableName`, `columns`, `encode`, `decode`, `idOf` and `cols` (`:119` to `:126`)
* `ColumnDef` as a type (`:120`) and `ColumnDef.of[t](name, primaryKey, references)(using c)` (`:63` to `:67`)
* `Column[A]` through `Expr.summon[Column[A]]` (`:41`), so whichever given instance resolves, and `Column.put` (`:78`) and `Column.get` (`:89`) on it
* `RefTarget[t]` through `Expr.summon[RefTarget[t]]` (`:56`, `:109`), so whichever given instance resolves, and `RefTarget.table` on it (`:59`)
* `new Col[T, t](name)(using c)` (`:111`)
* `ColsOf[T]` (`:114`, `:126`)
* `io.eezo.core.Id[T]` (`:125`), which belongs to `core`
* `snake` from `io.eezo.core.internal.util` runs at compile time inside the macro (`:57`, `:110`, `:119`) and its result is spliced as a string literal, so the expansion does not name it

`Scopes.transact` (`DB/Scopes.scala:21`), `Scopes.read` (`:39`) and `Scopes.attempt` (`:64`) are `inline`. Their expansions name `TxCap` and `DBCap` in the `summonFrom` patterns (`:25`, `:30`, `:41`), `Run.tx` (`:35`), `Run.read` (`:42`), the types `Tx` and `DB` in the context function types, and `new Attempt[E]` (`:68`). `Scopes.nameTheFailure` is `private inline` (`:71`) and only calls `scala.compiletime.error`.

The route generator emits `io.eezo.db.Table[A]`, `io.eezo.db.JdbcStore[A]()(using t)` and `io.eezo.db.JdbcStore.owned(o.ownerOf)(using t, summon)` at `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:578` to `:585`. The generated sources confirm it: `examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:74` to `:81` and `examples/todo/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala:34` to `:41`. The `summon` in `JdbcStore.owned` resolves a `Column[V]` inside the application, in the examples the given for `Id[T]`.

#### Top level

| Name | Declared at | Emitted by |
|---|---|---|
| `DBCap` | `DB/capability/Capability.scala:13` | `summonFrom` patterns in `inline def transact` and `inline def read`, `DB/Scopes.scala:30`, `:41`. Also the expansion of the alias `DB`. |
| `TxCap` | `DB/capability/Capability.scala:25` | `summonFrom` pattern in `inline def transact`, `DB/Scopes.scala:25`. Also the expansion of the alias `Tx`. |
| `ColsOf` | `DB/Col.scala:40` | `DB/macros/TableMacro.scala:114`, `:126`. Also reached through inference in every `where`, `orderBy`, `index` and `unique` lambda. |
| `ColumnDef` (case class and object) | `DB/ColumnDef.scala:10`, `:21` | `DB/macros/TableMacro.scala:63`, `:120` |
| `JdbcStore` | `DB/JdbcStore.scala:23` | `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:580`, `:581`, `:585`. Quoted as generated code at `docs/how-to/persisting-a-model.md:25`. |
| `RefTarget` (trait and object) | `DB/Ref.scala:8`, `:10` | `Expr.summon[RefTarget[t]]` at `DB/macros/TableMacro.scala:56`, `:109` |
| `Run` | `DB/engine/Run.scala:24` | `DB/Scopes.scala:35`, `:42`. The scaladoc at `DB/engine/Run.scala:14` to `:16` records that `private[eezo]` compiled and then failed at run time with `NoClassDefFoundError`. |
| `TableMacro` | `DB/macros/TableMacro.scala:12` | The splice in `inline def derived`, `DB/Table.scala:51`. The other hits for the name are comments at `modules/http/src/main/scala/io/eezo/http/Form.scala:170` and `modules/http/src/test/scala/io/eezo/http/FormSuite.scala:26`. |

#### Members

| Name | Declared at | Emitted by |
|---|---|---|
| `Col` constructor `new Col[T, A](name)(using codec)` | `DB/Col.scala:13` | `DB/macros/TableMacro.scala:111` |
| `Column.put`, `Column.get` | `DB/Column.scala:14`, `:15` | `DB/macros/TableMacro.scala:78`, `:89` |
| `Column` givens for `String`, `Int`, `Long`, `Boolean`, `BigDecimal`, `UUID`, `LocalDate`, `Instant`, `Array[Byte]`, `Id[T]`, `Option[A]` | `DB/Column.scala:54`, `:56`, `:57`, `:58`, `:61`, `:67`, `:70`, `:76`, `:81`, `:91`, `:93` | `Expr.summon[Column[A]]` at `DB/macros/TableMacro.scala:41`. The examples exercise `String`, `Int`, `Boolean`, `LocalDate`, `Id[T]` and `Option[A]` (`examples/blog/src/main/scala/models/Post.scala:28` to `:35`, `examples/reminders/src/main/scala/models/Reminder.scala:16` to `:21`, `examples/todo/src/main/scala/models/Todo.scala:22`). The givens for `Long`, `BigDecimal`, `UUID`, `Instant` and `Array[Byte]` are reached by the same route when a model has such a field. No example has one. The given for `UUID` is also read inside `db` at `DB/Column.scala:91` and `DB/Ref.scala:26`. |
| `ColumnDef.of` | `DB/ColumnDef.scala:22` | `DB/macros/TableMacro.scala:63` |
| `JdbcStore.apply` | `DB/JdbcStore.scala:28` | `RouteGenerator.scala:580`, `:585` |
| `JdbcStore.owned` | `DB/JdbcStore.scala:47` | `RouteGenerator.scala:581` |
| `RefTarget.table` | `DB/Ref.scala:8` | `DB/macros/TableMacro.scala:59` |
| `RefTarget` given for `Option[A]` | `DB/Ref.scala:11` | `Expr.summon[RefTarget[t]]`, reached by `modules/example/src/main/scala/example/Author.scala:10` (`mentor: Option[Ref[Author]]`) |
| `Ref` given `Column[Ref[T]]` | `DB/Ref.scala:26` | `Expr.summon[Column[A]]`, reached by `modules/example/src/main/scala/example/Book.scala:9` |
| `Ref` given `RefTarget[Ref[T]]` | `DB/Ref.scala:28` | `Expr.summon[RefTarget[t]]`, reached by `modules/example/src/main/scala/example/Book.scala:9` |
| `Scopes.Attempt` (class, constructor `private[Scopes]` with `@publicInBinary`) | `DB/Scopes.scala:89` | `new Attempt[E]` at `DB/Scopes.scala:68`, inside `inline def attempt` |
| `Table.tableName`, `Table.columns`, `Table.encode`, `Table.decode`, `Table.cols`, `Table.idOf` | `DB/Table.scala:14`, `:15`, `:18`, `:19`, `:26`, `:31` | Defined by the anonymous class at `DB/macros/TableMacro.scala:119` to `:126`. `Tour` also spells `tableName` (`Tour:103`), `columns` (`Tour:109`), `encode` (`Tour:147`) and `decode` (`Tour:159`). No application spells `cols` or `idOf`. |
| `Table.derived` | `DB/Table.scala:51` | Written by the compiler for every `derives Table` clause |
| `Run.tx`, `Run.read` | `DB/engine/Run.scala:26`, `:32` | `DB/Scopes.scala:35`, `:42` |
| `TableMacro.derive` | `DB/macros/TableMacro.scala:14` | The splice at `DB/Table.scala:51` |

### Kind 4: nobody's

#### Top level

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `TableDef` | `DB/ColumnDef.scala:31` | `DB/Table.scala:35`, `DB/Crud.scala:99`, `:104`, `DB/Query.scala:35`, `:40`, `DB/JdbcStore.scala:124` to `:127`. Tests: `DBT/OwnedSqlSuite.scala:14`, `DBT/QuerySuite.scala:89`, `DBT/demo/QueryDemo.scala:91`. | `private[db]` | `Table.tableDef` (`DB/Table.scala:35`) is a public member of a kind 1 trait and its type is `TableDef`. `Table[T]` is instantiated by the macro inside the application, so the anonymous class there inherits `tableDef`. |
| `DbInit` (object only) | `DB/DbInit.scala:84` | `DB/DbInit.scala:32`, `:53`, `:62`. Tests: `DBT/DbInitSuite.scala:104` to `:127`. | `private[db]` | None found. Every member is already `private[eezo]` or `private`. |
| `Bind` | `DB/Expr.scala:8` | `DB/Expr.scala:17`, `:18`, `:42`, `DB/Query.scala:33`, `:38`, `:60`, `:66`, `:77`, `DB/JdbcStore.scala:131`, `:171`. No test spells it. | `private[db]` | Public signatures mention it: the fields of `Expr.Cmp` and `Expr.In` (`DB/Expr.scala:17`, `:18`), `Expr.render` (`:42`), `Query.selectSql` and `Query.countSql` (`DB/Query.scala:33`, `:38`). `Expr` and `Query` are kind 2. |
| `Database` (class and object) | `DB/engine/Database.scala:11`, `:17` | `DB/DbInit.scala:73`, `:74`, `DB/DbApp.scala:62` to `:67`, `DB/engine/Database.scala:44` to `:55`, `DB/engine/Run.scala:28`, `:34`. Tests: `DBT/support/Pg.scala:50`, `:81`, `DBT/PoolSuite.scala:33`, `:97`, `:140`, `:154`, `DBT/engine/ConnectionUnavailableSuite.scala:152`. | `private[db]` | `DbInit.database` (`DB/DbInit.scala:73`) is `protected` on a trait that applications extend, and its result type is `Database`. The scaladoc at `DB/engine/Database.scala:8` says "Users never name this type". `modules/testkit` has no sources today, although the scaladoc names it as a future caller. |
| `Pool` (class; its companion is already `private[engine]`) | `DB/engine/Pool.scala:33` | `DB/engine/Database.scala:11`, `:33`. Tests: `DBT/PoolSuite.scala:4`, `:35`, `:139` and later, `DBT/engine/ConnectionUnavailableSuite.scala:17`, `:26`, `:81`, `:100`, `:139`. | `private[db]` | None found. The constructor and the three methods are already `private[eezo]`. The only signature that mentions `Pool` is the `private[eezo]` constructor of `Database`. |
| `ScopeKind` | `DB/engine/Scope.scala:4` | `DB/engine/Run.scala:27`, `:33`, `DB/engine/Scope.scala:29`, `:45`, `:78`, `:105`. Tests: `DBT/ScopeSuite.scala:3`, `:11`, `:26`, `DBT/engine/ConnectionUnavailableSuite.scala:83`, `:84`, `:125`, `:126`. | `private[db]` | None found. `Scope.enter` mentions it, and `Scope` is already `private[eezo]`. The references in `Run.tx` and `Run.read` sit in the bodies of methods that are not inline, so they are not spliced. |
| `Structure` | `DB/macros/Structure.scala:5` | `DB/macros/TableMacro.scala:15`, `:40` | `private[macros]` | None found. `Structure` runs inside the compiler when the macro executes. No quote in `TableMacro` references it, so the expansion does not name it. |
| `Migration` (case class and object) | `DB/migrate/Migration.scala:5`, `:23` | `DB/migrate/Freeze.scala:75`, `DB/migrate/Migrator.scala:69`, `:73`, `:113`. Tests: `DBT/migrate/MigrationSuite.scala:20` to `:113`, `DBT/migrate/MigrateSuite.scala:72`, `:75`, `:94`, `:96`, `DBT/BacklogSuite.scala:245`, `DBT/cli/FreezeCommandSuite.scala:3`, `:29`. | `private[db]` | None found. |
| `IndexSnap` | `DB/schema/Snapshot.scala:27` | `DB/Schema.scala:8`, `:12`, `:33`, `DB/schema/Introspect.scala:117`, `:135`, `DB/schema/Change.scala:14`, `DB/internal/SnapshotJson.scala:47`. Tests: `DBT/BacklogSuite.scala:201`, `:205`, `:215`, `DBT/support/Snaps.scala:21`, `DBT/schema/DifferSuite.scala:30`, `:124` to `:136`, `DBT/schema/DdlSuite.scala:58`, `:116`, `:121`, `:130`, `DBT/schema/DifferRoundTripSuite.scala:76` to `:115`. | `private[db]` | Public signatures mention it: the field `TableSnap.indexes` and with it the constructor and `copy` of `TableSnap` (`DB/schema/Snapshot.scala:37`), `TableSpec.indexes` (`DB/Schema.scala:33`), `Change.CreateIndex` (`DB/schema/Change.scala:14`). `Tour:289` calls `TableSnap.copy`, which carries `indexes: List[IndexSnap]` as a default argument. |
| `Snapshot` | `DB/schema/Snapshot.scala:57` | `DB/Schema.scala:38`. Tests: `DBT/BacklogSuite.scala:132`, `:148`. | `private[db]` | None found. |

#### Members

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `Check.render` | `DB/Check.scala:11` | `DB/schema/Snapshot.scala:64` | `private[db]` | None found. |
| `Col.name` | `DB/Col.scala:13` | `DB/Col.scala:16`, `:26`, `:28`, `:29`, `:31`, `DB/Schema.scala:28`, `:31`. Tests: `DBT/ColsSuite.scala:14` to `:64`, `DBT/demo/ColsDemo.scala:29` to `:32`, `modules/eezo/src/test/scala/io/eezo/PasswordStorageSuite.scala:26`. | `private[eezo]` | It is a `val` parameter of the constructor the macro calls from application code (`DB/macros/TableMacro.scala:111`). |
| `Col.codec` | `DB/Col.scala:13` | `DB/Col.scala:16`, `:26`. Tests: `DBT/ColsSuite.scala:28`. | `private[db]` | It is a `using val` parameter of the constructor the macro calls from application code. |
| `ColumnDef.quoted` | `DB/ColumnDef.scala:18` | `DB/ColumnDef.scala:35`, `:40`, `:50`, `:56`, `:67` to `:98` (all inside `TableDef`) | `private[db]` | None found. |
| `TableDef.name`, `TableDef.columns`, `TableDef.quoted`, `TableDef.insert`, `TableDef.selectAll`, `TableDef.idColumn`, `TableDef.selectById`, `TableDef.selectAllOrderedById`, `TableDef.updateById`, `TableDef.deleteById`, `TableDef.selectAllOrderedByIdOwnedBy`, `TableDef.selectByIdOwnedBy`, `TableDef.updateByIdOwnedBy`, `TableDef.deleteByIdOwnedBy`, `TableDef.selectWhere`, `TableDef.countWhere`, `TableDef.deleteWhere`, `TableDef.deleteAll` | `DB/ColumnDef.scala:31` to `:109` | `DB/Table.scala:36` to `:46` (`insert`, `selectAll`, `selectById`, `selectAllOrderedById`, `updateById`, `deleteById`), `DB/Crud.scala:99` (`deleteWhere`), `:104` (`deleteAll`), `DB/Query.scala:35` (`selectWhere`), `:40` (`countWhere`), `DB/JdbcStore.scala:124` to `:127` (the four `OwnedBy` methods). `idColumn`, `quoted`, `name` and `columns` are read inside `TableDef` only, and `idColumn` is named in a comment at `DB/JdbcStore.scala:36`. Tests: `DBT/OwnedSqlSuite.scala:19`, `:26`, `:33`, `:40`, `DBT/QuerySuite.scala:89`, `DBT/demo/QueryDemo.scala:91`. | `private[db]`, following the owner | Follows `TableDef`. |
| `Expr.Cmp`, `Expr.In`, `Expr.And`, `Expr.Or`, `Expr.Not`, `Expr.All` (enum cases with their fields) | `DB/Expr.scala:17` to `:26` | `DB/Col.scala:16`, `:26`, `DB/Expr.scala:29`, `:30`, `:36` to `:53`, `DB/Query.scala:14`. No test spells a case. | `private[db]` | They are cases of a kind 2 enum. `Query.pred` has the default `Expr.All[T]()` (`DB/Query.scala:14`). Whether Scala accepts an access modifier on an enum case was not compiled. |
| `Expr.and` (object) | `DB/Expr.scala:35` | `DB/Expr.scala:28`, `DB/Query.scala:20` | `private[db]` | None found. |
| `Expr.render` (object) | `DB/Expr.scala:42` | `DB/Crud.scala:98`, `DB/Query.scala:34`, `:39` | `private[db]` | None found. |
| `Order.column`, `Order.direction` and the constructor | `DB/Expr.scala:60` | `DB/Col.scala:28`, `:29` (constructor), `DB/Expr.scala:61` | `private[db]` | `Order` is a kind 2 case class, so `copy` and `unapply` expose both fields. |
| `Order.render` | `DB/Expr.scala:61` | `DB/Query.scala:26` | `private[db]` | None found. |
| `Query.table`, `Query.pred`, `Query.ord`, `Query.lim`, `Query.off` and the constructor | `DB/Query.scala:13` to `:17` | Fields: `DB/Query.scala:20` to `:54` only. Constructor: `DB/Crud.scala:87`. No test reads a field. | `private[db]` for the constructor, `private` for the fields | `Query` is a kind 2 case class, so `copy` and `unapply` expose the fields and with them `Table[T]`, `Expr[T]` and `Order[T]`. |
| `TableSpec.table` and the constructor | `DB/Schema.scala:7` | `DB/Schema.scala:12`, `:28`, `:31`, `:37`, `:38`, `:47`, `:54` to `:99` | `private[db]` | None found. |
| `TableSpec.indexes` | `DB/Schema.scala:33` | `DB/Schema.scala:39`, `:75`. Tests: `DBT/ColsSuite.scala:43`, `DBT/demo/ColsDemo.scala:39`. | `private[db]` | Its type is `List[IndexSnap]`. |
| `TableSpec.snapshot` | `DB/Schema.scala:35` | `DB/Schema.scala:88` | `private[db]` | Its type is `TableSnap`. |
| `Schema.dropAll` | `DB/Schema.scala:98` | None found anywhere. The three hits in `Tour` (`:322`, `:419`, `:432`) are `Tour`'s own private method of the same name. | `private` | None found. |
| `Table.tableDef` | `DB/Table.scala:35` | `DB/Crud.scala:99`, `:104`, `DB/Query.scala:35`, `:40`, `DB/JdbcStore.scala:124` to `:127`. Tests: `DBT/OwnedSqlSuite.scala:14`, `DBT/QuerySuite.scala:89`, `DBT/demo/QueryDemo.scala:91`. | `private[db]` | `Table[T]` is subclassed by the macro expansion inside the application. Whether a `private[db] final lazy val` on the trait is accepted there was not compiled. |
| `Table.selectByIdSql`, `Table.updateByIdSql`, `Table.deleteByIdSql` | `DB/Table.scala:38`, `:45`, `:46` | `DB/Crud.scala:77`, `:52`, `:69`. Tests: `DBT/demo/CrudDemo.scala:42` to `:44`. | `private[db]` | Same as `Table.tableDef`. Their siblings `insertSql` and `selectAllSql` are kind 1 through `Tour`. |
| `Table.selectAllOrderedByIdSql` | `DB/Table.scala:44` | `DB/JdbcStore.scala:83` | `private[db]` | Same as `Table.tableDef`. |
| `Database.close` | `DB/engine/Database.scala:14` | `DB/DbApp.scala:67`. Tests: `DBT/support/Pg.scala`, `DBT/PoolSuite.scala`, `DBT/engine/ConnectionUnavailableSuite.scala:159`. | `private[db]` | Follows `Database`. |
| `Database.connect` (object) | `DB/engine/Database.scala:25` | `DB/DbInit.scala:74`. Tests: `DBT/support/Pg.scala:51`, `DBT/PoolSuite.scala:154`. | `private[db]` | Follows `Database`. |
| `ScopeKind.Read`, `ScopeKind.Write`, `ScopeKind.label` | `DB/engine/Scope.scala:5`, `:7` | Same readers as `ScopeKind`. `label` is read at `DB/engine/Scope.scala:82` to `:108` only. | `private[db]`, following the owner | None found. |
| `Structure.q`, `Structure.of`, `Structure.FieldInfo` and its fields `name`, `tpe`, `pos`, `default`, `annots` | `DB/macros/Structure.scala:5` to `:16` | `DB/macros/TableMacro.scala:15` to `:110` reads `of`, `FieldInfo`, `name`, `tpe` and `pos`. No reader was found for `default` and `annots`. `q` is read inside `Structure` only. | `private[macros]`, following the owner | None found. |
| `Freeze.defaultDbDir` | `DB/migrate/Freeze.scala:31` | `DB/migrate/Freeze.scala:33` to `:67`, `DB/migrate/Migrator.scala:61`, `DB/cli/Commands.scala:55`, `:72` | `private[db]` | It is the default argument of kind 1 methods (`Freeze.write`, `Freeze.existing`, `Migrator.status`). The default is evaluated by a method of the declaring object, so the application does not name `defaultDbDir`. |
| `Freeze.migrationsDir` | `DB/migrate/Freeze.scala:33` | `DB/migrate/Freeze.scala:42`, `:76`. Tests: `DBT/BacklogSuite.scala:243`. | `private[db]` | None found. |
| `Freeze.snapshotFile` | `DB/migrate/Freeze.scala:34` | `DB/migrate/Freeze.scala:37`, `:81` | `private` | None found. |
| `Freeze.committedSnapshot` | `DB/migrate/Freeze.scala:36` | `DB/cli/Commands.scala:58`. Tests: `DBT/migrate/MigrationSuite.scala:73`, `:85`. | `private[db]` | None found. |
| `Freeze.nextNumber` | `DB/migrate/Freeze.scala:57` | `DB/migrate/Freeze.scala:74`. Tests: `DBT/migrate/MigrationSuite.scala:75`, `:86`. | `private[migrate]` | None found. |
| `Freeze.slug` | `DB/migrate/Freeze.scala:60` | `DB/migrate/Freeze.scala:75`. Tests: `DBT/migrate/MigrationSuite.scala:117`, `:118`. | `private[migrate]` | None found. |
| `Migration.number`, `Migration.name`, `Migration.statements`, `Migration.fingerprint` (field), `Migration.filename`, `Migration.render` | `DB/migrate/Migration.scala:6` to `:13` | `DB/migrate/Freeze.scala:79`, `:80`. Tests: `DBT/migrate/MigrationSuite.scala:30`, `:34`, `:35`, `DBT/migrate/MigrateSuite.scala:96`, `DBT/BacklogSuite.scala:245`. | `private[db]`, following the owner | None found. |
| `Migration.fingerprint` (object) | `DB/migrate/Migration.scala:24` | `DB/migrate/Freeze.scala:75`, `DB/migrate/Migrator.scala:73`, `:113`. Tests: `DBT/migrate/MigrationSuite.scala:20` to `:26`, `DBT/migrate/MigrateSuite.scala:75`, `:96`, `DBT/BacklogSuite.scala:245`. | `private[db]` | None found. |
| `Migration.parse` (object) | `DB/migrate/Migration.scala:32` | `DB/migrate/Migration.scala:42` | `private` | None found. |
| `Migration.verify` (object) | `DB/migrate/Migration.scala:41` | `DB/migrate/Migrator.scala:69`. Tests: `DBT/migrate/MigrationSuite.scala:30` to `:113`, `DBT/migrate/MigrateSuite.scala:72`, `:94`, `DBT/cli/FreezeCommandSuite.scala:29`. | `private[db]` | None found. |
| `Applied.name`, `Applied.appliedAt` | `DB/migrate/Migrator.scala:8` | No reader found for either. Both are written by the constructor call at `DB/migrate/Migrator.scala:42`. | `private` | `Applied` is a kind 2 case class, so `copy` and `unapply` expose both. |
| `Applied.fingerprint` | `DB/migrate/Migrator.scala:8` | `DB/migrate/Migrator.scala:75`, `:76`. Tests: `DBT/migrate/MigrateSuite.scala:75`. | `private[migrate]` | Same as above. |
| `Migrator.ensureLedger` | `DB/migrate/Migrator.scala:21` | `DB/migrate/Migrator.scala:33`, `:98`. Tests: `DBT/schema/IntrospectSuite.scala:22`. | `private[db]` | None found. |
| `ColumnSnap.pgType`, `ColumnSnap.nullable`, `ColumnSnap.primaryKey`, `ColumnSnap.checks`, `ColumnSnap.references` | `DB/schema/Snapshot.scala:9` to `:13` | `DB/schema/Ddl.scala:22` to `:35`, `DB/schema/Differ.scala:18`, `:39`, `:64` to `:82`, `DB/schema/Change.scala:35`, `DB/schema/Snapshot.scala:18` to `:22`, `DB/internal/SnapshotJson.scala:36`. Tests under `DBT/schema/` and `DBT/support/Snaps.scala:14`. | `private[db]` | `ColumnSnap` is a kind 2 case class through `Tour`, so `copy` and `unapply` expose the fields. |
| `IndexSnap.name`, `IndexSnap.columns`, `IndexSnap.unique` | `DB/schema/Snapshot.scala:27` | `DB/Schema.scala:39`, `:76`, `:78`, `DB/schema/Differ.scala:49` to `:57`, `DB/schema/Ddl.scala:57`, `:58`, `DB/schema/Change.scala:44`. Tests: `DBT/ColsSuite.scala:43`, `DBT/demo/ColsDemo.scala:41`. | `private[db]`, following the owner | Follows `IndexSnap`. |
| `TableSnap.indexes` | `DB/schema/Snapshot.scala:37` | `DB/schema/Differ.scala:20`, `:49` to `:55`. Tests: `DBT/schema/IntrospectSuite.scala:30`, `:32`. | `private[db]` | `TableSnap` is a kind 2 case class through `Tour`, and `Tour:289` calls `copy`. |
| `SchemaSnap.render` | `DB/schema/Snapshot.scala:54` | `DB/migrate/Freeze.scala:81`, `DB/cli/Commands.scala:106`. Tests: `DBT/schema/GoldenSnapshotSuite.scala:25`, `:31`, `:39`, `DBT/schema/IntrospectSuite.scala:16`. | `private[db]` | None found. |
| `Snapshot.column` | `DB/schema/Snapshot.scala:58` | `DB/Schema.scala:38`. Tests: `DBT/BacklogSuite.scala:132`, `:148`. | `private[db]` | Follows `Snapshot`. |

### Fits no kind cleanly

Each entry gives the facts on both sides. Nothing here is decided.

1. **The five exceptions the framework throws into application code**: `EscapedScope` (`DB/capability/Capability.scala:77`), `OffThread` (`DB/capability/Capability.scala:80`), `ReentrantScope` (`DB/engine/Scope.scala:14`), `ConnectionUnavailable` (`DB/engine/ConnectionUnavailable.scala:10`), `SchemaError` (`DB/SchemaError.scala:3`), with their members `EscapedScope.msg`, `OffThread.msg`, `ReentrantScope.msg`, `SchemaError.msg` and the public constructor of `ConnectionUnavailable`.
   On one side, no example, README or docs code block spells any of them, `docs/adr/0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md:33` calls the first three "the defect shape", and the scaladoc at `DB/engine/ConnectionUnavailable.scala:5` says no handler can do anything about it. On the other side, they reach application code as thrown values and their messages are written for the application's author, so a `catch` or a test `intercept` in an application is the one way it would name them.
   Readers: `EscapedScope` at `DB/capability/Capability.scala:52` and `DBT/HandleSuite.scala:17`. `OffThread` at `DB/capability/Capability.scala:59`, `DBT/HandleSuite.scala:24`, `:29`, `DBT/demo/ScopesDemo.scala:30`. `ReentrantScope` at `DB/engine/Scope.scala:83`, `:107`, `DBT/TransactSuite.scala:67`, `DBT/ScopeSuite.scala:19` to `:63`, `DBT/demo/ScopesDemo.scala:29`. These three allow `private[db]`. `ConnectionUnavailable` at `DB/engine/Pool.scala:72`, `:90`, `DBT/PoolSuite.scala:175` to `:278`, `DBT/engine/ConnectionUnavailableSuite.scala:25` to `:156`, and `modules/eezo/src/test/scala/io/eezo/EezoAppSuite.scala:148`, so `private[eezo]`. `SchemaError` at `DB/DbApp.scala:113`, `:191`, `DB/Schema.scala` (comment at `:19`), `DB/internal/SnapshotJson.scala:15` to `:27`, `DBT/BacklogSuite.scala:260`, `DBT/DbAppSuite.scala:66`, and `modules/eezo/src/test/scala/io/eezo/EezoAppSuite.scala:187`, so `private[eezo]`.

2. **The trait `DbInit`** (`DB/DbInit.scala:29`).
   Its six public members are kind 1, and every application object that extends `DbApp` or `EezoApp` inherits from it. Its own scaladoc (`DB/DbInit.scala:13`) says "users override members here and never name the trait", and no application names it (`examples/blog/src/main/scala/CreateUser.scala:30` mentions it in a comment).
   Readers of the name: `DB/DbApp.scala:40`, `DBT/DbInitSuite.scala:13` and later, `DBT/support/Pg.scala:37` to `:61`, `DBT/PoolSuite.scala:159`, `DBT/engine/ConnectionUnavailableSuite.scala:146`. They allow `private[db]`. The public trait `DbApp` extends it, and whether Scala accepts a narrower parent there was not compiled.

3. **The Crud operations no application calls**: `updateById` (`DB/Crud.scala:51`), `delete` (`:68`), `deleteWhere` (`:97`), `deleteAll` (`:103`), `all` (`:109`).
   They are siblings of `insert`, `update`, `findById`, `query`, `where`, which are kind 1. `docs/how-to/persisting-a-model.md:219` and `docs/adr/0006-a-failure-travels-to-the-nearest-boundary-that-owns-it.md:29` name `updateById`, `delete`, `deleteWhere` and `deleteAll` in prose about what they return, not in code an application writes.
   Readers: `updateById` at `DB/Crud.scala:33`, `DB/JdbcStore.scala:100`. `delete` at `DB/JdbcStore.scala:103`. `deleteWhere`, `deleteAll` and `all` have no reader in `DB/`. All five are called from `DBT/CrudSuite.scala`, `DBT/QuerySuite.scala` and `DBT/demo/CrudDemo.scala`. The readers allow `private[db]`.

4. **The `Col` operators no application uses**: `Col.<>` (`DB/Col.scala:19`), `Col.<` (`:20`), `Col.>` (`:22`), `Col.>=` (`:23`), `Col.in` (`:25`), `Col.desc` (`:29`).
   Their siblings `===`, `<=` and `asc` are kind 1. Readers are tests only: `DBT/QuerySuite.scala:48` (`<>`, `desc`), `:56`, `:62`, `:130`, `:131` (`in`), `DBT/demo/QueryDemo.scala:67` to `:74` (`in`). No reader at all was found for `<`, `>` and `>=` by the searches listed in Method.

5. **The `Expr` combinators**: `Expr.and` on the instance (`DB/Expr.scala:28`), `Expr.or` (`:29`), `Expr.unary_!` (`:30`).
   No application combines predicates. They are the only way to write a disjunction or a negation in the DSL. Readers are tests only: `DBT/QuerySuite.scala:68` and `DBT/demo/QueryDemo.scala:78`, `:80` use `!` and `or`. No reader was found for the infix `and`.

6. **The `Query` methods no application calls**: `Query.limit` (`DB/Query.scala:22`), `Query.offset` (`:23`), `Query.selectSql` (`:33`), `Query.countSql` (`:38`).
   `limit` is read by `Query.first` (`DB/Query.scala:50`). The scaladoc at `DB/Query.scala:30` says `selectSql` is "what a log line or an explain would want". Readers otherwise are tests only: `DBT/QuerySuite.scala:22`, `:23`, `:48`, `:77`, `:86`, `:110`, `:112`, `DBT/demo/QueryDemo.scala:22`, `:60`, `:62`. `selectSql` and `countSql` return `List[Bind]`, which is a blocker for `Bind` in kind 4.

7. **`Column.withType` and the eleven `PgType` cases**: `Column.withType` (`DB/Column.scala:25`), `PgType.Text`, `PgType.Varchar`, `PgType.Int4`, `PgType.Int8`, `PgType.Bool`, `PgType.Numeric`, `PgType.Uuid`, `PgType.Date`, `PgType.Timestamptz`, `PgType.Bytea`, `PgType.Jsonb` (`DB/PgType.scala:4` to `:14`).
   No reader of `withType` was found anywhere, tests included. It is the sibling of `imap` and `withCheck`, which are kind 1, and it is the only method that takes a `PgType` from a caller. Nine of the cases are read by the givens at `DB/Column.scala:55` to `:82` and by `DBT/TableDerivationSuite.scala:56` to `:77`. No reader was found for `PgType.Varchar` and `PgType.Jsonb` outside `DB/PgType.scala`.

8. **The three descriptive members of `Column`**: `Column.pgType` (`DB/Column.scala:10`), `Column.nullable` (`:11`), `Column.checks` (`:12`).
   An application that implements `Column[A]` by hand would define them, together with `put` and `get`. No application does; both examples that write a `Column` go through `imap` (`examples/blog/src/main/scala/models/User.scala:37`, `modules/example/src/main/scala/example/Title.scala:15`), which is also what the macro's error message recommends (`DB/macros/TableMacro.scala:46`). Readers: `DB/Column.scala:18` to `:96`, `DB/ColumnDef.scala:27`. Tests: `DBT/ColsSuite.scala:28`. `ColumnDef.of` reads them inside `db`, so the expansion does not name them.

9. **The `Check` cases no application uses**: `Check.MinLen`, `Check.Positive`, `Check.Between`, `Check.Raw` (`DB/Check.scala:4` to `:8`), with their fields.
   Their sibling `Check.MaxLen` is kind 1. `Check.Raw` is read at `DBT/BacklogSuite.scala:154`. No reader was found for `MinLen`, `Positive` and `Between` outside `DB/Check.scala`. All five are read by `Check.render` (`DB/Check.scala:12` to `:16`).

10. **The two `Ref` extension methods**: `Ref.value` (`DB/Ref.scala:22`) and `Ref.asId` (`DB/Ref.scala:23`).
    No reader was found anywhere, tests included. `Ref` is an opaque type, so these are the only way for code outside `DB/Ref.scala` to get the key back out of a `Ref[T]`.

11. **`Scopes.detached`** (`DB/Scopes.scala:126`).
    No example, README or docs code block calls it. The message of `ReentrantScope` tells the application's author to write `detached { ... }` (`DB/engine/Scope.scala:94`, `:121`), and the scaladoc of `DbInit.databaseAcquireTimeout` describes its cost (`DB/DbInit.scala:55`). Readers are tests only: `DBT/PoolSuite.scala:191`, `:238`, `DBT/ScopeSuite.scala:46` to `:62`, `DBT/TransactSuite.scala:77`, `:81`, `DBT/demo/ScopesDemo.scala:135` to `:140`. It is not inline.

12. **`Schema.empty`** (`DB/Schema.scala:108`).
    No example writes it today. `docs/adr/0005-the-entry-traits-landed-five-things-their-decision-tickets-had-settled-otherwise.md:19` to `:23` records that `blog` and `hello` once wrote `override def schema: Schema = Schema.empty` as a stopgap, and `:51` records the obligation to remove it. Readers: `modules/eezo/src/test/scala/io/eezo/EezoAppSuite.scala:43`, `:202`, `:208`. They allow `private[eezo]`.

13. **The two `Decision` cases `Tour` does not write**: `Decision.Skip` (`DB/migrate/Freeze.scala:18`) and `Decision.Manual` with its field `sql` (`:19`).
    Their sibling `Decision.Accept` is kind 1 through `Tour` only. `Skip` is read at `DB/DbApp.scala:205`, `:217`, `DB/migrate/Freeze.scala:71`, `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:173`. `Manual` is read at `DB/migrate/Freeze.scala:72`, `DB/cli/Render.scala:51`, `DB/cli/RenderJson.scala:59`, and built only at `DBT/migrate/MigrationSuite.scala:111`.

14. **The twelve `Change` cases**: `CreateTable`, `DropTable`, `AddColumn`, `DropColumn`, `AlterType`, `SetNullable`, `AddCheck`, `DropCheck`, `AddForeignKey`, `DropForeignKey`, `CreateIndex`, `DropIndex` (`DB/schema/Change.scala:4` to `:15`), with their fields.
    `Tour` holds values of them as `List[Change]` and calls `describe`, `risky` and `destructive`, and never names a case. Readers: `DB/schema/Ddl.scala:19` to `:59`, `DB/schema/Differ.scala:14` to `:82`, the tests under `DBT/schema/`, `DBT/cli/`, `DBT/BacklogSuite.scala`, and `modules/eezo/src/test/scala/io/eezo/DriftGateSuite.scala:12` (`Change.DropColumn`), so the readers allow `private[eezo]`. Three cases expose snapshot types in their fields: `CreateTable(table: TableSnap)`, `AddColumn(column: ColumnSnap)`, `CreateIndex(index: IndexSnap)`.

### Method and caveats

#### What was read

Every file under `modules/db/src/main/scala` outside `cli` and `internal` was read in full, 34 files: `capability/Capability.scala`, `Check.scala`, `Col.scala`, `Column.scala`, `ColumnDef.scala`, `Crud.scala`, `DbApp.scala`, `DbInit.scala`, `Exports.scala`, `Expr.scala`, `JdbcStore.scala`, `PgType.scala`, `Query.scala`, `Ref.scala`, `Schema.scala`, `SchemaError.scala`, `Scopes.scala`, `Table.scala`, `engine/ConnectionUnavailable.scala`, `engine/Database.scala`, `engine/Pool.scala`, `engine/Run.scala`, `engine/Scope.scala`, `macros/Structure.scala`, `macros/TableMacro.scala`, `migrate/DeployCheck.scala`, `migrate/Freeze.scala`, `migrate/Migration.scala`, `migrate/Migrator.scala`, `schema/Change.scala`, `schema/Ddl.scala`, `schema/Differ.scala`, `schema/Introspect.scala`, `schema/Snapshot.scala`.

The application corpus was read with comment lines filtered out: every Scala file under `examples/reminders`, `examples/blog/src/main/scala` that imports `io.eezo.db`, `examples/blog/src/test/scala` (three suites), `examples/todo` (`Main`, `AppSchema`, `models/Todo`), and every file of `modules/example` (nine main files and `Tour`). `examples/hello` has no dependency on `eezo-db` (`examples/hello/README.md:25`). From `docs/` the code blocks of `docs/how-to/persisting-a-model.md`, `docs/failures.md`, `docs/deploying.md` and `docs/live.md` were read, and the rest of `docs/` (without `docs/research`), `README.md` and the four example READMEs were searched by name. `docs/how-to/` is untracked in git at this commit and was counted as docs.

The generator was read at `modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala:365` to `:400` and `:560` to `:595`. The generated `Routes.scala` of `blog`, `hello` and `todo` under `examples/*/target/scala-3.8.4/src_managed` were searched for `io.eezo.db`. `examples/reminders` has no generated routes.

`modules/eezo/src/main/scala/io/eezo/EezoApp.scala` was read in full and `DriftGate.scala` was searched. `modules/testkit` and `modules/cli` contain no Scala sources.

#### What was searched

Ripgrep over `modules` and `examples`, Scala files only, `target` directories excluded, one search per name or per small group of names, with word boundaries. The package of every test file under `modules/db/src/test` and `modules/eezo/src` was listed from its `package` line. Hits were attributed to the eezo name by import or by package. Names that collide with other modules were separated by hand: `Differ` (`io.eezo.live.Differ`), `Resolution` (`io.eezo.http.Boundary.Resolution`), `describe` (`io.eezo.http.Route.describe`), `Order`, `parse`, `render`, `close`.

#### Dependence on Tour

`Tour` is the only application evidence for a large part of the `migrate` and `schema` packages. If `Tour` is not counted as an application, these names have the following readers and modifiers. Readers inside `DB/` and `DBT/` are those already listed by the searches above.

| Name | Readers outside `db` | Narrowest modifier without `Tour` |
|---|---|---|
| `Decision`, `Decision.Accept` | `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:166` to `:173` | `private[eezo]` |
| `Change`, `Change.destructive`, `Change.risky`, `Change.describe` | `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:80`, `:81`, `:167`, `:194`, `:212`, `:214`, `modules/eezo/src/test/scala/io/eezo/DriftGateSuite.scala:12` | `private[eezo]` |
| `SchemaSnap`, `SchemaSnap.tables`, `Schema.snapshot` | `modules/eezo/src/main/scala/io/eezo/DriftGate.scala:85` | `private[eezo]`. `Schema.snapshot` is a member of a kind 1 class and its type is `SchemaSnap`. |
| `TableSnap`, `TableSnap.name`, `TableSnap.columns`, `ColumnSnap`, `ColumnSnap.name` | None | `private[db]`. Blockers: `SchemaSnap.tables`, `Change.CreateTable`, `Change.AddColumn`, `TableSpec.snapshot`. |
| `Resolution` and its fields, `Freeze`, `Freeze.write`, `Freeze.existing`, `Migrator` and its members, `Migrator.Status` and its cases, `Applied`, `Applied.number`, `DeployCheck`, `DeployCheck.verify`, `Ddl` and both `render`, `Differ`, `Differ.diff`, `Introspect`, `Introspect.snapshot` | None | `private[db]`. `io.eezo.db.cli` reads most of them, and it is inside `db`. |
| `Schema.ddl` | None in code. Named in prose at `docs/how-to/persisting-a-model.md:129`, `:182`. | `private[db]` |
| `ColumnDef` fields, `PgType`, `PgType.render` | None | `private[db]`. `ColumnDef` itself stays kind 3, and `Table.columns` exposes it. |
| `Table.insertSql`, `Table.selectAllSql` | None | `private[db]` |
| `Ref.apply` | None. `Ref.to` stays kind 1 through `modules/example/src/main`. | `private[db]` |

`modules/example` is compiled by the root build (`build.sbt:243`), so narrowing any name in this table breaks `Tour` unless `Tour` changes with it.

#### What could not be verified

* Nothing was compiled and sbt was not run. Every statement about what a narrower modifier would allow is derived from the list of readers, not from a build.
* `modules/db` is capture checked. Whether a narrowed name interacts with capture annotations was not examined.
* Whether `TableMacro` and `TableMacro.derive` can be narrower than public while `Table.derived` stays `inline` and public was not tested. The scaladoc at `DB/engine/Run.scala:14` to `:22` records that the same change on `Run` compiled and failed at run time, and that an incremental build hid the failure.
* Whether a `private[db]` member on the trait `Table` is accepted when the macro's anonymous class extends the trait inside the application was not tested.
* Whether Scala accepts access modifiers on individual enum cases (`Expr`, `Change`, `Decision`, `Check`, `PgType`, `ScopeKind`) was not tested.
* Whether a public trait may extend a trait with a narrower modifier (`DbApp` over `DbInit`) was not tested.
* Compiler synthesised members of case classes were not listed one by one. Where a case class is public, its `copy`, `unapply` and `apply` expose every field type, and the Blockers column says so where it matters.
* Overrides of `toString` (`DB/Col.scala:31`) and the members inherited from `Dispatch` in `core` (such as `main`) are not declarations of this module and were not sorted.
* The line numbers given for test readers are those reported by ripgrep. Test files were not read in full, so a hit inside a string literal or a comment in a test may be listed as a reader. Hits in main sources were checked against the files read.
* The searches for the symbolic operators `<`, `>` and `>=` on `Col` matched on the pattern of a lambda parameter followed by the operator. A call written in another shape would have been missed.

---

## live

Module `modules/live/src/main/scala`, package `io.eezo.live`, read at commit 756d1ed on `main`. There are no subpackages under `src/main`. `Wire`, `Patch`, `Differ` and `NotCanonical` are excluded from the sorting as instructed.

### Counts

| Kind | Top level names | Members |
|---|---|---|
| 1. Written by the application | 6 (`Component`, `Event`, `Async`, `Init`, `Live`, `Topic`) | 17 (one of them, `LiveApp.allowedOrigins`, is `protected`) |
| 2. Written through inference | 1 (`LiveApp`) | 0 |
| 3. Spliced | 0 | 0 |
| 4. Nobody's | 0 | 1 (`Live.routes`) |
| Fits no kind cleanly | 0 | 5 (`Async.apply`, `Async.post`, `Event.apply(name)`, the `Event` two argument constructor, `Topic.subscriberCount`) |
| Total sorted | 7 | 23 |

The companion `object Event` is counted together with `case class Event` as one top level name.

Already not public, so not sorted: `PageRegistry` (class and object), `Canonical`, `Origins`, `Page` (class and object), `Subscription` (class and object) are all `private[live]`. `Async` and `Init` have `private[live]` constructors. `Init.seal`, `Topic.subscribe`, `Page.post`, `Page.rebase`, `Page.resync`, `Page.attach`, `Page.detach`, `Page.pendingForTests` and `Differ.IgnoreAttr` are `private[live]`. `LiveApp.frameworkRoutes` is a `protected` override of `HttpApp.frameworkRoutes`.

### Kind 1: written by the application

| Name | Declared at | Evidence |
|---|---|---|
| `Component` | modules/live/src/main/scala/io/eezo/live/Component.scala:26 | `final class Counter extends Component[Int]` at examples/blog/src/main/scala/components/Counter.scala:14. Also docs/live.md:46. |
| `Component.init` | Component.scala:31 | Implemented at examples/blog/src/main/scala/components/Counter.scala:16. Also docs/live.md:48. |
| `Component.handle` | Component.scala:34 | Implemented at examples/blog/src/main/scala/components/Counter.scala:18. Also docs/live.md:50. |
| `Component.render` | Component.scala:37 | Implemented in every blog component, for example the view starting near examples/blog/src/main/scala/components/Counter.scala:31. Also docs/live.md:46 onward. |
| `Event` (the type) | Component.scala:44 | `def handle(event: Event, count: Int)` at examples/blog/src/main/scala/components/Counter.scala:18, imported at line 5. Also docs/live.md:44 and 50. |
| `Event.name` | Component.scala:44 | `event.name match` at examples/blog/src/main/scala/components/Counter.scala:18. Also docs/live.md:50. |
| `Event.payload` | Component.scala:44 | `event.payload.getOrElse("value", "")` at examples/blog/src/main/scala/components/Guestbook.scala:44. Also Signup.scala:39. |
| `Async` (the type) | Component.scala:70 | `final class Callout(async: Async[CalloutState])` at examples/blog/src/main/scala/components/Callout.scala:20, imported at line 6. Also docs/live.md:173. |
| `Async.get` | Component.scala:91 | `async.get("http://127.0.0.1:8080/api", Auth.bearer(s.token))` at examples/blog/src/main/scala/components/Callout.scala:30. Also docs/live.md:176. Its signature mentions `io.eezo.http.client.Reply`, which belongs to the http module. |
| `Init` (the type) | Component.scala:114 | `def init(ctx: Init[Int])` at examples/blog/src/main/scala/components/Counter.scala:16. Also docs/live.md:48. |
| `Init.subscribe` | Component.scala:120 | `ctx.subscribe(Guestbook.signed)` at examples/blog/src/main/scala/components/Guestbook.scala:37. Also Board.scala:27 and docs/live.md:129. |
| `Live` | modules/live/src/main/scala/io/eezo/live/Live.scala:42 | `import io.eezo.live.Live` at examples/blog/src/main/scala/app/counter/Index.scala:6. |
| `Live.onClick` | Live.scala:59 | examples/blog/src/main/scala/components/Counter.scala:31. Also docs/live.md:60. |
| `Live.onInput` | Live.scala:65 | examples/blog/src/main/scala/components/Guestbook.scala:87. The named parameter `debounceMillis` is spelled at Signup.scala:85. Also docs/live.md:155. |
| `Live.onChange` | Live.scala:72 | examples/blog/src/main/scala/components/Signup.scala:94. Also docs/live.md:157. |
| `Live.onSubmit` | Live.scala:78 | examples/blog/src/main/scala/components/Guestbook.scala:82. Also docs/live.md:158. |
| `Live.ignore` | Live.scala:84 | docs/live.md:163 only. No example spells it. The only code reader is modules/live/src/test/scala/io/eezo/live/DiffSuite.scala:184. |
| `Live.mount(request, component)` | Live.scala:96 | `Live.mount(request, new Counter)` at examples/blog/src/main/scala/app/counter/Index.scala:25. Also docs/live.md:96. |
| `Live.mount(request, create)` | Live.scala:104 | `Live.mount(request, new Callout(_))` at examples/blog/src/main/scala/app/callout/Index.scala:20. Also docs/live.md:171. |
| `Topic` and its public constructor | modules/live/src/main/scala/io/eezo/live/Topic.scala:21 | `val signed: Topic[Entry] = new Topic[Entry]` at examples/blog/src/main/scala/components/Guestbook.scala:31. Also Board.scala:21 and docs/live.md:123. |
| `Topic.publish` | Topic.scala:29 | `Guestbook.signed.publish(...)` at examples/blog/src/main/scala/components/Guestbook.scala:54. Also Board.scala:34 and docs/live.md:135. |
| `LiveApp.allowedOrigins` (`protected`, an override point) | modules/live/src/main/scala/io/eezo/live/LiveApp.scala:24 | `override protected def allowedOrigins: Set[String] = Set("https://app.example")` at docs/live.md:255, written on `object Main extends EezoApp`. No example overrides it. |

### Kind 2: written through inference

| Name | Declared at | Exposed by | Application call site |
|---|---|---|---|
| `LiveApp` | modules/live/src/main/scala/io/eezo/live/LiveApp.scala:14 | `trait EezoApp extends HttpApp with LiveApp with DbApp` at modules/eezo/src/main/scala/io/eezo/EezoApp.scala:31. `LiveApp` is a public parent of a trait the application extends, and `allowedOrigins` is inherited through it. | `object Main extends EezoApp` at examples/blog/src/main/scala/Main.scala:43 and examples/todo/src/main/scala/Main.scala:12. The override at docs/live.md:255 resolves to `LiveApp.allowedOrigins`. No example, README or doc spells the name `LiveApp` in code. The scaladoc at LiveApp.scala:10 says an application on `eezo-http` alone writes `extends LiveApp`, which is weaker evidence of kind 1. |

No other type declared in this module reaches the application only through inference. The types of view and handler expressions that `Live` returns (`Attr`, `Seq[Attr]`, `Html`, `Seq[Route]`) and the `Reply` that `Async.get` hands to its callback are declared in `core` and `http`, not here.

### Kind 3: spliced

| Name | Declared at | Emitted by |
|---|---|---|
| None | | The module's main sources contain no `inline` definition, no `given`, no macro and no public extension. The one extension, `matchesSafePrefix` at Live.scala:316, is `private`. The route generator at modules/sbt-plugin/src/main/scala/io/eezo/sbt/RouteGenerator.scala emits no `io.eezo.live` name. The generated files examples/blog/target/scala-3.8.4/src_managed/main/io/eezo/generated/Routes.scala, and the same file for hello and todo, mention `live` only as the application's own package `app.live.Index` (lines 49 to 53 and 119 of the blog file). |

### Kind 4: nobody's

| Name | Declared at | Readers | Narrowest modifier | Blockers |
|---|---|---|---|---|
| `Live.routes` | modules/live/src/main/scala/io/eezo/live/Live.scala:142 | Main: modules/live/src/main/scala/io/eezo/live/LiveApp.scala:27. Tests in package `io.eezo.live`: modules/live/src/test/scala/io/eezo/live/BoundPageSuite.scala:45, and modules/live/src/test/scala/io/eezo/live/LiveServerSuite.scala:40, 235, 266, 281, 289. Test in package `io.eezo`: modules/eezo/src/test/scala/io/eezo/BoundLivePageSuite.scala:61. | `private[eezo]`, because of the one reader in `modules/eezo`. Without that test it would be `private[live]`. | None found. The return type `Seq[Route]` belongs to http. The caller `LiveApp.frameworkRoutes` is an ordinary method, not inline, so nothing is spliced into the application. The scaladoc on `HttpApp.frameworkRoutes` and docs/adr do not tell applications to call it. |

### Fits no kind cleanly

**`Async.apply`** (Component.scala:78). For public: the class scaladoc at Component.scala:54 presents `async { work }` as the primary form, and `get` and `post` are described as `apply` composed with the HTTP client. It is also inherited by `Init`, so `ctx { ... }` inside `init` is the documented way to start a load at mount (Component.scala:62). Against: no example, README or doc spells it. docs/live.md shows only `async.get`. Readers are Component.scala:94 and 104 inside the class, and tests in package `io.eezo.live`: PageSuite.scala:500, 506, 538, 563 and LiveServerSuite.scala:550.

**`Async.post(url, body, contentType, headers)`** (Component.scala:96). For public: it is the twin of `Async.get`, which is kind 1, and the class scaladoc names both at Component.scala:66. Against: it has no reader anywhere in the repository, tests included. The constructor parameter of `Async` is also named `post` (Component.scala:70).

**`Event.apply(name)`**, the hand written companion overload (Component.scala:49). For public: an application test of a component would call `component.handle(Event("inc"), state)`, and `Event` is a public case class. Against: no example, doc or application test constructs an `Event`. The hit at examples/blog/src/main/scala/components/Counter.scala:10 is a comment. Readers are tests only: modules/live/src/test/scala/io/eezo/live/PageSuite.scala (25 spellings, first at lines 74 and 93), RegistrySuite.scala:126 and 160, and harness/Demo.scala:51 and 56 (package `io.eezo.live.harness`, 7 spellings).

**The `Event` two argument constructor**, compiler synthesised (Component.scala:44). For public: the same application test scenario, for an event with a payload. Against: the only reader is modules/live/src/main/scala/io/eezo/live/Wire.scala:86. No test and no application constructs an event with a payload. The fields are `String` and `Map[String, String]`, so the constructor exposes no type that would narrow. `Wire.ClientMessage.Emit(event: Event)` at Wire.scala:33 mentions `Event`, which is harmless because `Event` stays public and `Wire` narrows.

**`Topic.subscriberCount`** (Topic.scala:33). For public: the scaladoc says "For tests and metrics, not for logic", and an application's metrics or tests are application code. Against: no example, README or doc spells it. Readers are modules/live/src/test/scala/io/eezo/live/PageSuite.scala:393, 396 and 414 (package `io.eezo.live`) and modules/live/src/test/scala/io/eezo/live/harness/Demo.scala:76 (package `io.eezo.live.harness`).

### Method and caveats

**Read in full.** `LiveApp.scala`, `Component.scala`, `Live.scala`, `Topic.scala`, `Page.scala`, `PageRegistry.scala`, `Origins.scala` and `Canonical.scala` under modules/live/src/main/scala/io/eezo/live. `Wire.scala`, `Patch.scala` and `Differ.scala` were not read in full. They were searched for their declarations and for mentions of `Event`, `Component`, `Topic`, `Async` and `Init`.

**Searched.** ripgrep over the whole repository, excluding `target` directories, for `io.eezo.live`, `LiveApp`, `allowedOrigins`, `frameworkRoutes`, every `Live.` member, `Component[`, `Event(`, `event.name`, `event.payload`, `Async[`, `async.get`, `async.post`, `async {`, `async(`, `new Async`, `new Init`, `Topic[`, `new Topic`, `.publish(`, `.subscribe(`, `subscriberCount`, and for `inline`, `given` and macro syntax in the module's main sources. The generated `Routes.scala` files under `examples/*/target/scala-3.8.4/src_managed` exist for blog, hello and todo and were searched separately. No generated file exists for reminders. Hits were confirmed by import or package. Every application hit is in `examples/blog` or `docs/live.md`. `README.md`, `modules/example`, `modules/testkit`, `modules/cli`, `docs/how-to`, `docs/deploying.md`, `docs/failures.md`, and the hello, reminders and todo examples contain no spelling of a live name. No example test (`examples/*/src/test`) mentions a live name.

**Mentions of the four excluded names.** No public signature of a sorted name mentions `Wire`, `Patch`, `Differ` or `NotCanonical`. `Live.ignore` reads `Differ.IgnoreAttr` in its body only (Live.scala:84). `NotCanonical` is thrown through `Live.mount` and is named in prose at docs/live.md:299, as the text of an error an application sees at mount, not as code the application writes.

**Leaks from `private[live]` owners.** None. No public signature mentions `Page`, `PageRegistry`, `Origins`, `Origins.Normalised`, `Canonical` or `Subscription`. `Subscription` appears only in `Init.seal` and `Topic.subscribe`, both `private[live]`.

**Cascade effects.** None found in this module. If `Live.routes` narrows to `private[eezo]`, `LiveApp.frameworkRoutes` still compiles because it sits in `io.eezo.live`.

**Test classpath.** modules/eezo depends on live with `test->test` (build.sbt:146), so `BoundLivePageSuite` in package `io.eezo` also reads the test trait `LiveServerFixtures`. That trait lives in `src/test` and is outside this inventory.

**Not verified.** Nothing was compiled and sbt was not run, so every statement about what a modifier would allow comes from reading and searching, not from the compiler. `protected` members were listed for completeness although the task defines public as having no `private` or `protected` modifier. Overload resolution between the `Async` constructor parameter `post` and the method `Async.post` was not examined.

---

## A golden list of the public API: tools, precedent, and what a home made check takes

Date checked: 2026-09-28. Project facts: eezo pins Scala 3.8.4 (`project/Toolchain.scala`), builds with sbt 1.12.14, tests with munit 1.3.4, and compiles `modules/db` with `-language:experimental.captureChecking` (`build.sbt`).

### Short answer

1. No Scala tool found in this research ships a "dump the public API to a checked in file and fail on any difference" workflow; MiMa and TASTy-MiMa both compare against a previously published artifact, and eezo has none yet.
2. MiMa in its default backward direction does not report added public names; the sbt plugin reports them only with `mimaCheckDirection` set to `"forward"` or `"both"`, which runs the comparison with the two versions swapped ([README](https://github.com/scala-garden/mima/blob/main/README.md), [SbtMima.scala](https://github.com/scala-garden/mima/blob/v1.2.1/sbtplugin/src/main/scala/com/typesafe/tools/mima/plugin/SbtMima.scala)).
3. A `private[eezo]` member is public in bytecode (MiMa README, and confirmed here with `javap` on Scala 3.8.4 output), so a tool that reads only JVM access flags sees it as public; MiMa 1.2.1 reads Scala visibility and ignores such members, and TASTy based tools see the qualifier directly.
4. TASTy-MiMa 1.4.1 has no problem kind for an added concrete member, and its default tasty-query 1.6.1 refuses Scala 3.8.4 TASTy; with tasty-query overridden to 1.8.0 it ran on a capture checked 3.8.4 sample in a local experiment.
5. The golden file pattern is established precedent in Kotlin (`.api` files, `apiCheck`), Rust (`cargo-public-api` snapshot test), Go (`api/*.txt`), TypeScript (API Extractor `.api.md`) and .NET (`PublicAPI.Shipped.txt`).
6. The Scala 3 compiler repository itself keeps a golden list checked by a TASTy inspector test, for the experimental definitions of the standard library ([stdlibExperimentalDefinitions.scala](https://github.com/scala/scala3/blob/main/tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala)); no third party Scala library with a full checked in API dump was found.
7. In a local experiment, `scala3-tasty-inspector` 3.8.4 read all 71 TASTy files of eezo's capture checked `modules/db` class directory and produced a list of 430 public and protected names, with a throwaway program of about 50 lines.
8. No primary source documents the TASTy inspector's behaviour on capture checked TASTy; the only evidence is that experiment, in which capture sets did not appear in the printed signatures.

### 1. Tools that dump or pin the public API of a Scala 3 library

#### The bytecode claim, verified

The MiMa README states: "`private[foo]` is a Scala rule, not a JVM one. In bytecode these definitions are public, so a client can end up depending on one even though it cannot name it." ([Qualified private definitions](https://github.com/scala-garden/mima/blob/main/README.md#qualified-private-definitions)). It also states: "`private[foo]` definitions and nested `private` classes are public in bytecode. Java code can use them, MiMa ignores them."

Local confirmation (Scala 3.8.4, `javap -p`): a member declared `private[demo] def internalHook(n: Int): Int` was emitted as `public int internalHook(int)`, a `private[demo] val secret` got a `public java.lang.String secret()` accessor, and `private[demo] class Internal` was emitted as `public class demo.Internal`. A `protected def` was also emitted as `public`.

Tools affected: any tool that decides visibility from JVM access flags alone. Kotlin's binary-compatibility-validator is of that kind by design (it requires `ACC_PUBLIC` or `ACC_PROTECTED` and then consults Kotlin metadata, which Scala class files do not carry). Generic Java bytecode differs were not tested in this research. MiMa is not affected in this way, because it reads Scala visibility ("MiMa reads the same rules from the pickle").

#### Comparison

| Tool | Baseline it needs | `private[pkg]` | Reports additions | Status on 2026-09-28 |
| --- | --- | --- | --- | --- |
| MiMa (`sbt-mima-plugin`) | A previously published artifact (`mimaPreviousArtifacts`), or two jars or class directories on the command line | Ignored for members; a qualified private class is checked only when it escapes | Not in the default backward direction; yes in `"forward"` or `"both"` | v1.2.1 released 2026-09-18, last push 2026-09-23 |
| TASTy-MiMa, sbt-tasty-mima | A previously published artifact (`tastyMiMaPreviousArtifacts`) | Checked as package private by default; treated as private only for packages listed in `withMoreArtifactPrivatePackages` | No, except `NewAbstractMember` | tasty-mima v1.4.1 (2025-10-09), sbt-tasty-mima v1.4.0 (2025-07-24); both built against Scala 3.7.1 |
| tasty-query | None; it is a library | Exposes `ScopedPrivate(scope)` visibility | Not applicable; a custom dump would be written on top | v1.9.0 released 2026-08-27 |
| TASTy inspector | None; it is a library | Exposes `Symbol.privateWithin` | Not applicable; a custom dump would be written on top | Published with every compiler release, including 3.8.4 |
| sbt-version-policy | Previous release, through MiMa | Same as MiMa | Through MiMa forward mode, as an approximation of source compatibility | v3.3.0 released 2026-07-03 |
| scaladoc | None | Hidden by default | No machine readable dump documented | Part of the compiler |
| SemanticDB | None | Encoded as `PrivateWithinAccess` | Not applicable; a custom dump would be written on top | Specification maintained in scalameta |

#### MiMa

What it checks, verbatim: "MiMa compares the classfiles of two versions of a library. It reports changes that make code compiled against the old version fail with the new one, with a `LinkageError` such as `NoSuchMethodError` or `AbstractMethodError`." It adds: "It does not check source compatibility either" ([README](https://github.com/scala-garden/mima/blob/main/README.md)).

Baseline: `mimaPreviousArtifacts := Set("com.example" %% "my-library" % "<version>")`. The README says that `mimaReportBinaryIssues` fails "also when `mimaPreviousArtifacts` is empty, so that a project does not go unchecked by accident", unless `mimaFailOnNoPrevious := false`. The command line tool takes "a JAR or a directory containing classfiles" for each side, so a published artifact is not strictly required, but a stored old binary is.

Additions: "MiMa checks backward compatibility, that code compiled against the previous version keeps working. `mimaCheckDirection := "forward"` checks the other direction, `"both"` checks both." In the sbt plugin source the forward check is `mimaLib.collectProblems(curr, prev, excludeAnnots, forwards = true)`, the same comparison with the versions swapped. Local experiment with `mima-cli_3` 1.2.1: comparing the old sample to a new sample with one added public method printed nothing and exited 0, with `-b`, with `-f` and with no flag; swapping the two directories printed `DirectMissingMethod: method bornPublic(Int)Int in class demo.Tx does not have a correspondent in new version` and exited 1. The command line `-f` flag is a filter on one comparison ([MimaCli.scala](https://github.com/scala-garden/mima/blob/v1.2.1/cli/src/main/scala/com/typesafe/tools/mima/cli/MimaCli.scala)), not a swapped comparison.

Qualified privates: "MiMa ignores a qualified-private **member**, such as `private[foo] def`: no Scala code outside `foo` can call it." In the same experiment, removing a `private[demo]` method produced no report. Narrowing a public member to `private[foo]` is reported as `MethodNoLongerCheckedProblem`.

Scala 3 and TASTy: "MiMa checks Scala 2.12, 2.13 and 3 artifacts. Its sbt plugin supports sbt 1.x and sbt 2.0.7 or newer." and "Scala 3 compiles against TASTy, which holds more than the bytecode: exact Scala types, and the bodies of `inline` methods. TASTy-MiMa checks TASTy compatibility the way MiMa checks bytecode. Use both for a Scala 3 library."

Maintenance: the repository `lightbend-labs/mima` now resolves to [scala-garden/mima](https://github.com/scala-garden/mima); release v1.2.1 is dated 2026-09-18.

#### TASTy-MiMa and sbt-tasty-mima

What it checks, verbatim: "TASTy-MiMa can report modifications to the non-private API of a library that may cause *retypechecking errors*." and "TASTy-MiMa compares all the `.tasty` files of two released libraries" ([README](https://github.com/scalacenter/tasty-mima/blob/main/README.md)).

Additions: the README says "adding a public method to a public `final class` is not a source compatible change, although it is a TASTy-compatible change." The complete list of problem kinds is `MissingClass`, `MissingTypeMember`, `MissingTermMember`, `RestrictedVisibilityChange`, `IncompatibleKindChange`, `MissingParent`, `IncompatibleSelfTypeChange`, `RestrictedOpenLevelChange`, `AbstractClass`, `FinalMember`, `TypeArgumentCountMismatch`, `IncompatibleNameChange`, `IncompatibleTypeChange`, `NewAbstractMember`, `InternalError` ([ProblemKind.java](https://github.com/scalacenter/tasty-mima/blob/main/tasty-mima-interface/src/main/java/tastymima/intf/ProblemKind.java)). None of them is a plain addition. No direction setting was found in the sbt plugin keys.

Qualified privates: in [Analyzer.scala](https://github.com/scalacenter/tasty-mima/blob/main/tasty-mima/src/main/scala/tastymima/Analyzer.scala), a `ScopedPrivate` symbol whose scope is a package maps to `Visibility.Private` only if the package is listed in the configuration, otherwise to `Visibility.PackagePrivate`, which is checked. The configuration documents it: "Symbols that are private to any of those packages will not be checked for changes by tasty-mima." ([Config.java](https://github.com/scalacenter/tasty-mima/blob/main/tasty-mima-interface/src/main/java/tastymima/intf/Config.java)). Local experiment: with the default configuration, removing a `private[demo]` method was reported as `Problem(MissingTermMember, demo.Tx.internalHook)`, and the added public method was not reported.

Scala 3.8 status: tasty-mima v1.4.1 is built with Scala 3.7.1 and depends on tasty-query 1.6.1 ([build.sbt at v1.4.1](https://github.com/scalacenter/tasty-mima/blob/v1.4.1/build.sbt)). Local experiment against Scala 3.8.4 TASTy: the default failed with "TASTy signature has wrong version. expected: {majorVersion: 28, minorVersion: 7} found: {majorVersion: 28, minorVersion: 8}". With tasty-query 1.8.0 forced on the classpath it ran and gave the result above. sbt-tasty-mima has the key `tastyMiMaTastyQueryVersionOverride`, described as "Override the version of tasty-query used by tasty-mima" ([TastyMiMaPlugin.scala](https://github.com/scalacenter/sbt-tasty-mima/blob/v1.4.0/sbt-tasty-mima/src/main/scala/sbttastymima/TastyMiMaPlugin.scala)); that key was not exercised here. sbt-tasty-mima has open issues 38 and 39 asking for sbt 2 support, which touches eezo only through `sbt-eezo`.

#### tasty-query

"TASTy Query is a compiler-independent library to semantically analyze TASTy" ([README](https://github.com/scalacenter/tasty-query/blob/main/README.md)). It needs the full classpath, including the JRE: "TASTy Query requires that all classes, such as the JRE, must be explicitly added to the classpath." TASTy support follows compiler releases one by one in the [release notes](https://github.com/scalacenter/tasty-query/releases): v1.7.0 (2026-01-22) "Upgrade to Scala 3.8.1 and support its TASTy format", v1.8.0 (2026-04-04) "Upgrade to Scala 3.8.3", v1.9.0 (2026-08-27) "Upgrade to Scala 3.9.0 and support its TASTy". An older tasty-query refuses newer TASTy, as the experiment above shows. No issue or release note mentioning capture checking was found in the repository; the only evidence of compatibility is that tasty-query 1.8.0 loaded the capture checked sample.

#### TASTy inspector

The reference page gives the dependency as `libraryDependencies += "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value` and shows an `Inspector` with `def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit` ([TASTy Inspection](https://docs.scala-lang.org/scala3/reference/metaprogramming/tasty-inspect.html)). The entry points are `inspectTastyFiles`, `inspectTastyFilesInJar` and `inspectAllTastyFiles(tastyFiles, jars, dependenciesClasspath)`; the implementation runs the compiler with `-from-tasty` and `-Yretain-trees` ([TastyInspector.scala at 3.8.4](https://github.com/scala/scala3/blob/3.8.4/tasty-inspector/src/scala/tasty/inspector/TastyInspector.scala)). It therefore brings `scala3-compiler` onto the classpath of whatever runs it. Version 3.8.4 is on Maven Central. Section 4 covers compatibility.

#### sbt-version-policy

It "configures MiMa to check for binary or source incompatibilities" and needs the previous release. For source compatibility the README says the "plugin uses MiMa in forward mode as an approximation. This is not always correct" ([README](https://github.com/scalacenter/sbt-version-policy/blob/main/README.md)). It is a policy layer over MiMa, not a dump.

#### scaladoc

The documented settings include `-private`: "Show all types and members. Unless specified, show only public and protected types and members." ([scaladoc settings](https://docs.scala-lang.org/scala3/guides/scaladoc/settings.html)). No setting on that page produces a machine readable list of the API; the output is a documentation site. A scaladoc based golden list was not found documented anywhere first hand.

#### SemanticDB

The specification models access as a message with the cases `PrivateAccess`, `PrivateThisAccess`, `PrivateWithinAccess`, `ProtectedAccess`, `ProtectedThisAccess`, `ProtectedWithinAccess` and `PublicAccess`, and maps `private[X] def x = ???` to `PrivateWithinAccess(<X>)` ([semanticdb.md](https://github.com/scalameta/scalameta/blob/main/semanticdb/semanticdb.md)). It would need SemanticDB output enabled in the build and a custom reader. No first hand example of an API golden list built on it was found, and it was not exercised here.

### 2. The golden file approach in other ecosystems

**Kotlin binary-compatibility-validator.** It dumps the "binary API of a JVM part of a Kotlin library that is public in the sense of Kotlin visibilities" into `.api` files in the project's `api` subfolder. `apiDump` "builds the project and dumps its public API in project `api` subfolder", and `apiCheck` "builds the project and checks that project's public API is the same as golden value in project `api` subfolder. This task is automatically inserted into `check` pipeline". The workflow section says that when the API changes, "`check` task will start to fail. `apiDump` should be executed manually, the resulting diff in `.api` file should be verified: only signatures you expected to change should be changed." It has a `nonPublicMarkers` option for "effectively private API that cannot be actually private for technical reasons." The plugin is now "in maintenance mode", with new features moving to the Kotlin Gradle plugin, whose validation is experimental and uses the tasks `checkKotlinAbi` and `updateKotlinAbi` ([README](https://github.com/Kotlin/binary-compatibility-validator/blob/master/README.md), [Kotlin Gradle plugin documentation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)).

**Kotlin explicit API mode.** This is a compiler mode, not a dump. "Visibility modifiers are required for declarations if the default visibility exposes them to the public API. This helps ensure that no declarations are exposed to the public API unintentionally." ([What's new in Kotlin 1.4](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)). The API guidelines recommend it because it "forces you to explicitly state your intentions when you're designing the API for your library." ([API guidelines](https://kotlinlang.org/docs/api-guidelines-simplicity.html)). It addresses the same accident as a golden list, a name born public, at the declaration instead of at a file.

**Rust cargo-public-api.** It lists "the public API of Rust library crates" with "one line per public item", and its README gives a test that ends with `public_api.assert_eq_or_update("./tests/snapshots/public-api.txt")`. The stated benefits are to "prevent accidental changes to your public API" and "review the public API diff of deliberate changes"; a plain `cargo test` "will fail if your public API is accidentally or deliberately changed", and `UPDATE_SNAPSHOTS=yes cargo test` rewrites the snapshot. The stated cost is the toolchain: it relies on rustdoc JSON, "for which a recent version of the Rust nightly toolchain must be installed", and the README carries a compatibility matrix of tool version against nightly version ([README](https://github.com/cargo-public-api/cargo-public-api/blob/main/README.md)).

**Rust cargo-semver-checks.** It lints against a baseline version rather than a checked in file: the crate does not have to be published, but a baseline must be located, with `--baseline-version`, `--baseline-rev`, `--baseline-root` or `--baseline-rustdoc`. It states "A design goal of `cargo-semver-checks` is to not have false positives." and admits gaps, for example "breaking type changes, for example in the type of a field or function parameter". It names the cost of its input: "Rustdoc's JSON output format isn't stable, and can have breaking changes in new Rust versions." It describes the difference from the previous tool: cargo-public-api "focuses more on API diffing (showing which items have changed) and not API linting (explaining why they have changed and providing control over what counts)." ([README](https://github.com/obi1kenobi/cargo-semver-checks/blob/main/README.md)).

**Go `api/` directory.** "Each file is a list of API features, one per line. go1.txt (and similarly named files) are frozen once a version has been shipped. Each file adds new lines but does not remove any." New API goes in `api/next/`, and "each API feature line must end in "#nnnnn" giving the GitHub issue number of the proposal issue that accepted the new API. This helps with our end-of-cycle audit of new APIs." ([api/README](https://github.com/golang/go/blob/master/api/README)). The checker is a test: "This package computes the exported API of a set of Go packages. It is only a test, not a command" ([src/cmd/api/main_test.go](https://github.com/golang/go/blob/master/src/cmd/api/main_test.go)). It fails with "API differences found", and `cmd/dist` registers it as the "API check" test, with the comment "Only run the API check on fast development platforms." ([src/cmd/dist/test.go](https://github.com/golang/go/blob/master/src/cmd/dist/test.go)).

**Microsoft API Extractor.** It writes a Markdown report, by default in the `etc` folder, such as `etc/sp-core-library.api.md`. On a local build the file is updated and "The developer should commit the updated report file and include it as part of their pull request (PR). If they forget to do this, the PR validation will fail because it performs a production build (i.e. not using --local), which does not automatically update the report file." The stated benefit: "Having this synopsis in one easy-to-review report is very powerful. Turning on API Extractor for a project is often an enlightening moment." ([API report](https://api-extractor.com/pages/overview/demo_api_report/)).

**.NET PublicApiAnalyzers.** The files are `PublicAPI.Shipped.txt` and `PublicAPI.Unshipped.txt` in each project. Rule RS0016: "All public types and members should be declared in PublicAPI.txt. This draws attention to API changes in the code reviews and source control history, and helps prevent breaking changes." Rule RS0017 covers removals. Both default to severity Warning, so the build fails only where warnings are errors or the severity is raised ([rules](https://github.com/dotnet/roslyn/blob/main/src/RoslynAnalyzers/PublicApiAnalyzers/Microsoft.CodeAnalysis.PublicApiAnalyzers.md), [help](https://github.com/dotnet/roslyn/blob/main/src/RoslynAnalyzers/PublicApiAnalyzers/PublicApiAnalyzers.Help.md)). The same analyzer can track internal API in `InternalAPI.Shipped.txt`, and its help text notes that test assemblies "only create noise" there.

### 3. Scala libraries that check in a golden API dump

One first party example was found. The Scala 3 compiler repository has `tests/run-tasty-inspector/stdlibExperimentalDefinitions.scala`, 153 lines. It holds a hand maintained `Set` of fully qualified names, runs `TastyInspector.inspectTastyFilesInJar` over the standard library jar, collects every definition carrying `@experimental`, and asserts in both directions. The failure messages are "Found @experimental definition in library not listed" and "Listed @experimental definition was not found in the library", each followed by the instruction to edit the list. It pins a subset of the API chosen by an annotation, not the whole public surface, and it pins names without signatures.

No third party Scala library with a checked in dump of its whole public API was found. The searches were GitHub repository search and code search for terms such as "scala public api dump", "tasty api dump", "sbt api dump", `apiDump language:Scala`, and combinations of `TastyInspector` or `inspectTastyFiles` with "snapshot", "golden" and "public api", plus a web search. The common practice found instead is a list of accepted incompatibilities against a published baseline, which MiMa supports with files in `src/main/mima-filters`. This negative result is limited by what GitHub search indexes and should not be read as proof of absence.

### 4. What a minimal home made version takes in Scala 3.8

#### Moving parts

1. A test scoped dependency on `scala3-tasty-inspector` at the project's Scala version, which brings the compiler onto the test classpath.
2. A way for the test to learn the module's class directory and its dependency classpath. The inspector resolves types against a classpath. In the experiment, a malformed classpath made the compiler crash in the `readTasty` phase with "assertion failed: class InitialisingDataSource has non-class parent", because the PostgreSQL jar could not be found.
3. An `Inspector` that walks top level definitions, skips symbols that are `Private`, that have a non empty `privateWithin`, or that are `Synthetic` or `Artifact`, and descends into the members of each retained class.
4. A printer that emits one line per name with a signature, sorted, so the file is stable across runs.
5. A comparison with a checked in text file per module, a failure message that shows the difference, and an update switch, as cargo-public-api does with `UPDATE_SNAPSHOTS` and Kotlin does with `apiDump`.
6. Decisions that the precedents make explicitly: whether `protected` members count, whether members of a public class inherited from a private parent count, how companion objects and constructors are printed, and whether names or names with signatures are pinned.

#### Size

The throwaway program used for the experiment was about 50 lines of Scala including the file walk and the printing, without the comparison, the update switch and the build wiring. The Scala compiler's own golden list test is 153 lines, of which about 100 are the list. The experiment files were kept outside the repository and are not an implementation.

#### Compatibility with the project's Scala version

The reference states the general rule: "a Scala compiler in version 3.x1.y1 is able to read TASTy files produced by another compiler in version 3.x2.y2 if x1 >= x2", and "compilers in stable versions cannot read TASTy generated by an unstable version" ([Binary Compatibility](https://docs.scala-lang.org/scala3/reference/language-versions/binary-compatibility.html)). The inspector is the compiler reading TASTy, and the documented dependency uses `scalaVersion.value`, so an inspector at 3.8.4 reading TASTy written by 3.8.4 is inside the documented rule.

#### Compatibility with capture checked TASTy

No primary source was found that documents the TASTy inspector on capture checked TASTy. A search of `scala/scala3` issues for the inspector together with capture checking returned nothing relevant; the open inspector issues found are 10453 and 22911, neither about capture checking. What primary sources do say is adjacent: from Scala 3.8 "The standard library of Scala 3 is now compiled with enabled support for explicit-nulls and capture-checking", and "at the TASTy level, each of these introduces additional features that cannot be represented in a Scala 2 compatible way" ([State of the TASTy reader](https://www.scala-lang.org/blog/state-of-tasty-reader.html)); the 3.8 announcement says capture checking "is still an experimental feature" ([Scala 3.8 released](https://www.scala-lang.org/news/3.8/)). So every Scala 3.8 program, including the inspector itself, already reads a capture checked standard library.

Local evidence, not a guarantee:

1. A sample compiled with `-language:experimental.captureChecking` on 3.8.4, using `Conn^`, `Tx^ ?-> A` and `() ->{c} Int`, was read by the inspector at 3.8.4 without error. The dump listed the public and protected members and omitted the `private`, `private[demo]` members and the `private[demo]` class.
2. The same program, given eezo's `modules/db/target/scala-3.8.4/classes` with the core classes and the HikariCP, PostgreSQL and SLF4J jars as classpath, reported `inspected 71 tasty files, ok=true, 430 names`. The repository's `git status` was identical before and after.
3. The printed signatures did not contain capture sets. `def later(c: Conn^): () ->{c} Int` was printed as `(c: demo.Conn)scala.Function0[scala.Int]`. With the printing used in the experiment (`memberType` and `show`), a change that touches only capture annotations would not change the dump. Whether another way of printing recovers them was not investigated.

Capture checking is experimental, so its TASTy encoding carries no stability promise across compiler versions; no primary source was found that states what happens to the inspector's view of it on a compiler upgrade.

### What these facts imply for eezo

#### In favour of a golden list

1. It is the only approach found that works with no published baseline. MiMa and TASTy-MiMa both need an earlier artifact or stored binary, and eezo has no stable release.
2. It is the only approach found that reports an added public name in normal operation. MiMa reports additions only in forward mode, TASTy-MiMa has no problem kind for them, and the experiment confirmed both.
3. It reads Scala visibility, so `private[eezo]` is excluded by construction, while the bytecode says public.
4. The precedents name the benefit in the same terms as eezo's rule: "prevent accidental changes to your public API" (cargo-public-api), "This helps ensure that no declarations are exposed to the public API unintentionally" (Kotlin), "This draws attention to API changes in the code reviews and source control history" (.NET), "What is THAT doing in there?!" (API Extractor).
5. The Scala compiler team maintains a test of the same shape, which shows the technique is workable with the inspector.
6. The experiment ran on eezo's capture checked module at the pinned Scala version.

#### Against, or costs

1. Every deliberate API change needs a second edit to the golden file. Kotlin, cargo-public-api and API Extractor all describe this step as part of the normal workflow. Before a stable release, when the API moves often, this cost is paid often.
2. The check is home made. No maintained Scala tool provides it, so eezo would own the traversal rules, the printing format and their upkeep across compiler upgrades.
3. The inspector puts the whole compiler on the test classpath and depends on the reflection API's printing, which is not specified as a stable text format in any source found. A compiler upgrade could change the printed signatures and produce a diff with no API change. cargo-public-api and cargo-semver-checks report the equivalent cost for rustdoc JSON.
4. Capture sets were absent from the printed signatures, so for `modules/db` the dump as experimented pins less than the API the capture checker sees.
5. A golden list does not judge compatibility. It reports that the surface changed, not whether the change breaks anyone; cargo-semver-checks draws the same line between "API diffing" and "API linting". After a first release, MiMa and TASTy-MiMa answer the compatibility question, and TASTy-MiMa currently needs a tasty-query override to read 3.8 TASTy.
6. A golden list detects a name born public only if a reviewer reads the diff of the golden file. It converts a silent accident into a visible line in a pull request; it does not decide whether the line is wanted. The .NET documentation makes the same point about drawing attention in review.
7. Go restricts its check to "fast development platforms", and the inspector run in the experiment started a compiler instance; the time this adds to eezo's test run was not measured.

### Method and caveats

1. Sources were read first hand on 2026-09-28: raw README and source files from the owning GitHub repositories, release metadata through the GitHub API, the Scala, Kotlin and API Extractor documentation sites, and Maven Central metadata. Quotations were checked against the raw text, not against summaries.
2. Local experiments used scala-cli with Scala 3.8.4 on JDK 26, in a scratch directory outside the repository. The eezo repository was read, never written; its existing `target` class directories were used as input.
3. The experiments are single runs on one small sample and one module. They show that something worked once, not that it is supported.
4. tasty-mima with tasty-query 1.8.0 was forced through a dependency override that its authors do not document as supported for version 1.4.1. The sbt key `tastyMiMaTastyQueryVersionOverride` was read in source but not run.
5. The statement that MiMa forward mode reports added members rests on the sbt plugin source and on a swapped run of the command line tool, not on a run of the sbt plugin itself.
6. The statement that bytecode only tools misread `private[eezo]` was verified for the bytecode itself and against the documented rules of Kotlin's validator. No generic Java API differ was run.
7. Scaladoc and SemanticDB were assessed from documentation only.
8. The search for Scala libraries with a golden dump is a negative result from GitHub and web search and may have missed examples.
9. Whether Scala 3 has a compiler mode comparable to Kotlin's explicit API mode was not researched.
10. tasty-query 1.9.0 and Scala 3.9.0 exist as of this date; nothing here was tested against them.
