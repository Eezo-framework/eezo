# The stylesheet is built by the build

**Status:** accepted

An eezo application that keeps a `tailwind.css` at its project root gets its stylesheet from the sbt plugin: `eezoTailwind`, a `Compile / resourceGenerators` task, runs the standalone Tailwind CLI once in every build that asks for the classpath, the dev loop's restart, `test`, `package` and `eezoStage`, and writes `assets/tailwind.css` into `resourceManaged`, from where it reaches the classpath, the jar and the staged deployment like any resource. The CLI is the binary Tailwind publishes with each release, fetched by the plugin into `~/.eezo/tools/tailwindcss-<version>/` (`EEZO_HOME` moves it), verified against the `sha256sums.txt` published beside it, and shared by every project on the machine; `eezoTailwindCli` names a binary of the build's own for a machine with no way to GitHub. The file is served by `GET /assets/*file`, a route `HttpApp.frameworkRoutes` appends to every application's table, under an address `Assets.url` spells the file's digest into. Nothing is committed and no second process runs: the dev loop's restart is what rebuilds the stylesheet, and an edit to `tailwind.css` itself is a watch trigger on the project, where the watch reads it; a trigger scoped to the generator task is not collected, because a generator is reached through a setting and not as a task dependency, which was measured before the scope was chosen.

The reason is the one the route table already rests on. Tailwind v4 reads the sources for the class names they use and emits the rules those names need, so in an eezo application the stylesheet is a function of the Scala sources, exactly as `Routes.scala` is a function of `app/`. A derived artifact that somebody has to remember to regenerate is stale by default; one the build derives is stale never. The plugin already owns one such generator, its cache, its watch trigger and its place in the dev loop, and the stylesheet takes the same shape.

## Considered options

**A long lived `tailwindcss --watch` beside the dev loop, as the dioxus CLI runs one.** The watcher writes the output on its own clock, so under `eezoDev` a save would restart the application on the Scala edit and again when the stylesheet landed a moment later, with the first reload unstyled; and the output has to be written into the source tree, which means committing a generated file or gitignoring one that CI and `eezo build` must then remember to produce. Rejected because the dev loop already rebuilds on every save, so a watcher only adds a second clock to race, and because eezo deliberately has no stylesheet hot swap: every change is a full restart, and the running code, stylesheet included, is always the code on disk.

**A documented `build.sbt` recipe and nothing in eezo.** estpoker, an application on eezo 0.2.0, wrote a forty line `tailwindBuild` task that does what `eezoTailwind` does and hooked it before `compile`. Rejected as the default because every application would carry the same effectful shell out, its own binary fetch with no verification, and its own drift from the next one; the recipe is the shape of this decision, not an alternative to it.

**The Tailwind npm package.** Rejected because it puts Node on every machine that builds an eezo application and in every CI runner, for one binary that Tailwind already publishes standalone.

**A CSS DSL in Scala.** Rejected because the point of utility classes is that they stay in the template, cohesive with the markup and without a scope mechanism of their own; a DSL would reproduce Tailwind's vocabulary in Scala and lose its tooling.

**Serving the stylesheet under the reserved prefix, `/eezo/tailwind.css`.** Rejected because the file is the application's, built from its sources, and because a hand written stylesheet beside it would still need the asset route the shop and the site each copied. One asset route serves both, and it is the application's address space, not the framework's.

## Consequences

The first build on a machine, and a CI runner without a cache, reaches `github.com` once per version; the failure message names `eezoTailwindCli` as the way around it. eezo's own workflow caches `~/.eezo/tools`.

Tailwind runs once per build that misses the cache, roughly a hundred milliseconds, and the cache is keyed on every file under the unmanaged source and resource directories, so a save that changes no class name still pays it. The copy into `resourceManaged` happens only when the bytes differ, so the timestamp `copyResources` reads does not move for nothing.

In a multi project build each project is its own Tailwind root: the file is found at that project's `baseDirectory`, and the CLI's working directory is that project's.

The platform table covers Linux and macOS on x64 and arm64 and Windows on x64; a musl Linux or anything else sets `eezoTailwindCli`.

The plugin now owns a version to bump, `Tailwind.DefaultVersion`, and the asset route is a public surface of `eezo-http`, `Assets`, which the shop no longer copies. The site still carries its own, wider copy, since it serves the API reference out of a second directory of the jar; folding that into `Assets` is a separate decision.

The generated file is a function of the sources only where a class name is spelled out whole. A name assembled from strings at runtime is one Tailwind never sees, which is a property of Tailwind and not of this integration, and the how-to says so.
