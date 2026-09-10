# The entry traits landed five things their decision tickets had settled otherwise

**Status:** accepted

Issue #160 is the build ticket for the three entry traits, and it inherits its shape from two decision tickets: #158 settled `Dispatch`, `HttpApp`, `DbApp` and `EezoApp`, and #159 settled where `Commands`, `Render` and `RenderJson` live and which `build.sbt` arrows remain. The two commits that closed it, 9303c7e and e996f9c, differ from those records in four places: the second commit moved two decisions the first had honoured, a conflict between the tickets forced the third, and a decided signature forced the fourth. Review of PR #163 added a fifth, on the one member #158 had rejected by name. This ADR names each piece, what the ticket said, what landed, and records that the decision is to keep all five.

## The unknown command answers in JSON under `--json`

#158's resolution says the unknown command message is plain text even under `--json`, because `core` cannot see `RenderJson`. #159's move list keeps `error` on `db`'s `RenderJson`, for the `SchemaError` catch that #158 moves into `DbApp`. Commit 9303c7e did exactly that. Commit e996f9c then moved the flag and the error line into `core`: `Dispatch.json` reads the flag, `Dispatch.fail` prints `{"error": ...}` under it and `[eezo] ...` otherwise, `unknown` calls `fail`, `DbApp`'s `SchemaError` catch calls `fail`, and `RenderJson.error` is deleted from `modules/db/src/main/scala/io/eezo/db/cli/RenderJson.scala`.

The premise #158 argued from had already moved. #159 decision 2 put the JSON writer in `core` as `io.eezo.core.internal.Json`, so `core` no longer needs `RenderJson` to render one object with one string field. Once that was true, the commit message's reasoning applies: the flag and the one error shape it implies are what both edges would otherwise write twice, which is the scaladoc's own test for what `Dispatch` may hold. Keeping `unknown` as prose would have left a machine caller one stderr line it cannot parse, on the path every application shares. `DispatchSuite` pins both shapes. Kept.

## Three `test->test` arrows on `core`

#159's arrow table ends with "No `test->test` arrow remains", and 9303c7e delivered that table. e996f9c gives `http`, `db` and `eezo` a `core % "compile->compile;test->test"` dependency in `build.sbt`, so that `HttpAppSuite`, `DbAppSuite` and `EezoAppSuite` reach `Captured` in `modules/core/src/test/scala/io/eezo/core/support/Captured.scala` instead of each carrying its own copy of the stdout and stderr capture.

The arrow #159 removed was `eezo` on `db`'s tests, which existed so that `cli`'s suites could extend `DbSuite`. That arrow is gone and stays gone. The new ones point at `core` alone, which has no dependencies of its own, so they cannot pull a driver or a server onto any edge's test classpath, which was the harm #159 was guarding against. The trade is one twenty line fixture in `core` against three copies drifting apart, and the comment on the `eezo` block in `build.sbt` says so. Kept.

## `blog` and `hello` gained `override def schema: Schema = Schema.empty`

#160's done criteria say the existing `todo` and `blog` examples compile unchanged against `EezoApp`. #158 decision 5 makes `schema` and `routes` abstract, not defaulted to empty, and on `main` `EezoApp` defaulted `schema` to `Schema.empty`. The two cannot both hold: an `EezoApp` whose `schema` is abstract does not compile a `Main` that never names one, and neither `blog` nor `hello` did. `todo` names `AppSchema` and is unchanged.

#158 wins, because it is the decision and #160 is the ticket that carries it. `examples/blog/src/main/scala/Main.scala` and `examples/hello/src/main/scala/Main.scala` each gained a six line override returning `Schema.empty`, with a comment naming it a stopgap. #158 decision 6 expects `blog` to gain `derives Table` on `Post` and a real `AppSchema`, and its consequences send `hello` to `eezo-http` where `schema` does not exist; both changes belong to #161. `Schema.empty` holds the two examples compiling until #161 replaces it in `blog` and deletes it from `hello`. Kept as a stopgap, not as a shape.

## The Tour reads `TOUR_NOPAUSE` instead of `--no-pause`

`modules/example/src/test/scala/example/Tour.scala` used to override `boot(args: Array[String])` and read `--no-pause` from it. #158 decision 3 makes `boot()` take no arguments, because the empty argument list is the application and any first argument is a command, so `run --no-pause` is now an unknown command, exit 2. The Tour became a `DbApp`, the edge it has, and the switch became the environment variable `TOUR_NOPAUSE`, read from `sys.env`; the `example` block in `build.sbt` documents the spelling. It is an environment variable rather than a `-D` system property because `Test / fork` is on for the example project, and a forked JVM inherits the parent process environment but not a property given to the sbt launcher, so `-Dtour.nopause` never reached the Tour; review of PR #163 caught that. This is a consequence of decision 3, not a deviation from it, and it is the shape any program that wants a flag rather than a command will take.

## `program` is a hook on `Dispatch`, and `EezoApp` overrides it

#158 decision 1 says the ancestor holds `main`, `commands` and `usage` and nothing else: "No `around`, no `failures`, no `program`. A lifecycle hook in `core` with one implementor was rejected (map note 9)." Its composition paragraph then explains how the program composes without one: each edge owns a `case Nil` arm, each writes `own orElse super.commands`, so in `EezoApp extends HttpApp with DbApp` the database edge's arm shadows the http edge's, and "the order `HttpApp with DbApp` is load-bearing and `EezoApp` is written once". 9303c7e and e996f9c landed exactly that.

Review of PR #163 found what that leaves open. `object Main extends DbApp with HttpApp` compiled on the umbrella, and on it `sbt run` booted the server with no `Database` installed and `dev` served with no drift check, because the same two arms shadow the other way round and nothing but the scaladoc said which order was meant. `dev` composed by a different mechanism, the `devServer` override, so "run the http edge under the database" was said twice in two shapes and enforced in neither.

What landed instead: `Dispatch` declares an abstract `protected def program(): Unit` and owns the one `case Nil => program(); 0`; `HttpApp` implements it as `boot()`, `DbApp` as `withDatabase(boot())`, neither with `override`; `EezoApp` inherits two concrete `program`s and has to override it, and writes `withDatabase(boot())` there, beside `devServer`. A trait or object that stacks the two edges by hand, in either order, is refused by the compiler with "inherits conflicting members" until it says which program it means. `DispatchSuite` pins the refusal on two fake edges and `EezoAppSuite` pins it on the real ones in both orders. The `override` omission is not style: an implementation marked `override` is one a later mixin's silently replaces, which is the hazard the member exists to close, and `Dispatch.program`'s scaladoc says so.

Map note 9 rejected the hook because it would have had one implementor. It has two by construction, one per edge, and the umbrella is a third that chooses between them, so the note is cleared rather than overruled. `DbApp`'s `SchemaError` catch now covers the chain after its own arms as well, so a `SchemaError` out of the program still exits 1 with the one line, as it did on `main` where the whole dispatch sat under one catch. Kept.

## Considered options

**Restore the two tickets' letter in e996f9c's two places.** Rejected. Putting `error` back on `db`'s `RenderJson` would make `http` write a second one the day its `routes` arm has anything to refuse, and would leave the unknown command as the one line that ignores `--json`. Deleting the `test->test` arrows would restore three copies of `Captured`.

**Keep the two `Nil` arms and pin the order with a test instead of a hook.** Rejected. A test on `EezoApp` proves the umbrella's own line and nothing about a user's `Main extends DbApp with HttpApp`, which is the case the review named; only the compiler sees that code. Naming the edge from `EezoApp` (`super[DbApp].commands(Nil)`) was tried first in 288b2e3 and rejected for the same reason: it fixed the umbrella's dependence on its own mixin order and left a hand stacking compiling, while adding a third composition idiom next to `orElse` chaining and the `devServer` hook.

**Default `schema` to `Schema.empty` on `EezoApp` so #160's "unchanged" holds.** Rejected, because it reopens #158 decision 5, and an application on the umbrella without a schema is exactly the mistake that decision wants the compiler to catch. Two explicit stopgaps in two example files cost less than a silent default in the framework.

## Consequences

#158 and #159 are closed and their comments still read as the record; this ADR is the correction on both. #158's unknown command paragraph is superseded by `Dispatch.fail`'s scaladoc, its decision 1 gains `program` and its composition paragraph is superseded by `EezoApp`'s scaladoc, and #159's arrow table gains `core % "compile->compile;test->test"` on the `http`, `db` and `eezo` rows.

#161 inherits two obligations: replace `Schema.empty` in `blog` with a real `AppSchema`, and drop the override from `hello` when it moves to `eezo-http`. If #161 ships without either, the stopgap outlives the ticket it was written for, and this ADR is where to look for why it exists.

Recorded against [issue #160](https://github.com/Eezo-framework/eezo/issues/160), the decisions of [issue #158](https://github.com/Eezo-framework/eezo/issues/158) and [issue #159](https://github.com/Eezo-framework/eezo/issues/159), and commits 9303c7e and e996f9c.
