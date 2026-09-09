# The entry traits landed four things their decision tickets had settled otherwise

**Status:** accepted

Issue #160 is the build ticket for the three entry traits, and it inherits its shape from two decision tickets: #158 settled `Dispatch`, `HttpApp`, `DbApp` and `EezoApp`, and #159 settled where `Commands`, `Render` and `RenderJson` live and which `build.sbt` arrows remain. The two commits that closed it, 9303c7e and e996f9c, differ from those records in four places: the second commit moved two decisions the first had honoured, a conflict between the tickets forced the third, and a decided signature forced the fourth. This ADR names each piece, what the ticket said, what landed, and records that the decision is to keep all four.

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

## Considered options

**Restore the two tickets' letter in e996f9c's two places.** Rejected. Putting `error` back on `db`'s `RenderJson` would make `http` write a second one the day its `routes` arm has anything to refuse, and would leave the unknown command as the one line that ignores `--json`. Deleting the `test->test` arrows would restore three copies of `Captured`.

**Default `schema` to `Schema.empty` on `EezoApp` so #160's "unchanged" holds.** Rejected, because it reopens #158 decision 5, and an application on the umbrella without a schema is exactly the mistake that decision wants the compiler to catch. Two explicit stopgaps in two example files cost less than a silent default in the framework.

## Consequences

#158 and #159 are closed and their comments still read as the record; this ADR is the correction on both. #158's unknown command paragraph is superseded by `Dispatch.fail`'s scaladoc, and #159's arrow table gains `core % "compile->compile;test->test"` on the `http`, `db` and `eezo` rows.

#161 inherits two obligations: replace `Schema.empty` in `blog` with a real `AppSchema`, and drop the override from `hello` when it moves to `eezo-http`. If #161 ships without either, the stopgap outlives the ticket it was written for, and this ADR is where to look for why it exists.

Recorded against [issue #160](https://github.com/Eezo-framework/eezo/issues/160), the decisions of [issue #158](https://github.com/Eezo-framework/eezo/issues/158) and [issue #159](https://github.com/Eezo-framework/eezo/issues/159), and commits 9303c7e and e996f9c.
