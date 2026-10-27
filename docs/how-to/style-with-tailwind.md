# Style with Tailwind

Put a `tailwind.css` at the project root, link the stylesheet from the layout, and the build does
the rest: the sbt plugin runs the Tailwind CLI inside every build, and the result ships in the
jar.

## What Tailwind is to an eezo application

Tailwind v4 reads your source files for the class names they use and writes only the rules those
names need. In an eezo application the class names live in Scala, beside the markup that uses
them, so the stylesheet is a function of the sources the way the route table is. The plugin
treats it that way: generated, never committed, rebuilt when the sources change.

## One file

`tailwind.css`, at the project root beside `build.sbt`:

```css
@import "tailwindcss" source("src");
```

`source("src")` points Tailwind's scan at `src/` and nothing else. Without it Tailwind scans the
whole project directory, skipping what `.gitignore` lists, which is right inside a git checkout
and reads `target/` outside one.

That is the setup. The next build sees the file, fetches the Tailwind CLI once, and writes
`assets/tailwind.css` into the jar's resources.

## The link

The layout names the stylesheet through `Assets.url`, which spells the file's version into the
address, so a browser keeps it for a year and a build that changes a class is a new address:

```scala
package views

import io.eezo.core.html.*
import io.eezo.http.{Assets, Request}

object Layout extends io.eezo.http.Layout {

  def apply(request: Request, title: Option[String], content: Html): Html =
    html(
      head(
        meta(Attrs.charset := "utf-8"),
        Tags.title(title.getOrElse("my app")),
        link(Attrs.rel := "stylesheet", Attrs.href := Assets.url("tailwind.css"))
      ),
      body(
        Attrs.cls := "bg-stone-50 text-stone-900",
        main(Attrs.cls := "mx-auto max-w-2xl p-4", content)
      )
    )
}
```

`Main` names it with `override def layout: Layout = views.Layout`, and every page comes back in
it, the derived pages, the login page and the error page included. The address is served by the
asset route every eezo application carries, `GET /assets/*file`, which reads the jar's `assets/`
directory. It is appended after your table, so a route of your own under `/assets` wins.

## Writing classes

`Attrs.cls := "..."` is the class attribute. A repeated `Attrs.cls` on one element keeps the last
value and merges nothing, so compose in one string:

```scala
button(Attrs.cls := s"rounded px-3 py-1 ${if (primary) "bg-sky-700 text-white" else "bg-stone-200"}")
```

Tailwind finds a class only where its whole name is spelled out. `"bg-" + color` yields no rule;
write the full names and choose between them.

Tailwind's preflight resets every element, so a page with no classes on it, a derived index or
the login page, shows unstyled. A `@layer base` block in `tailwind.css` gives those elements a
look once:

```css
@layer base {
  h1 { @apply mb-4 text-2xl font-semibold; }
  a { @apply text-sky-700 hover:underline; }
  button { @apply rounded bg-stone-800 px-3 py-1 text-white; }
}
```

## What happens on every save

`eezo dev` compiles on save and restarts the application. The stylesheet is built inside that
same step, as a resource generator the restart's classpath request runs, so a class you just typed into a component is styled when the
page reloads: one restart, no second process, nothing to run in another terminal. An edit to
`tailwind.css` itself restarts the loop too. The CLI runs only when a source or the stylesheet
changed; a save that changed neither is a cache hit.

A mistake in `tailwind.css`, an `@apply` of a class that does not exist, fails the build the way a
compile error does. The console shows Tailwind's diagnostics, and the previous application keeps
serving until a save fixes it.

## The CLI

The plugin fetches the standalone Tailwind CLI from its GitHub release, checks it against the
digest list published beside it, and keeps it under `~/.eezo/tools/tailwindcss-<version>/`
(`EEZO_HOME` moves the directory). One download per version per machine; every project on it
shares the copy, and nothing from npm is involved.

| setting | default | what it is |
|---|---|---|
| `eezoTailwindVersion` | `"4.3.3"` | the release the plugin fetches |
| `eezoTailwindInput` | `baseDirectory.value / "tailwind.css"` | the stylesheet compiled; absent means no Tailwind |
| `eezoTailwindCli` | `None` | a binary already on the machine, for a build with no way to GitHub |

A CI runner reaches `github.com` once per version; caching `~/.eezo/tools` across runs saves the
download. eezo's own workflow does, with `actions/cache`.

## Production

`eezo build`, `package` and `test` all ask for the classpath, which runs the same generator, so
the jar carries the stylesheet and
the deployed image needs neither Tailwind nor Node. The output is minified in every build.

## Removing it

Delete `tailwind.css` and the link. The generator does nothing without the file, and removes the
stylesheet it built.

## The worked example

[The blog](../../examples/blog/README.md) is styled this way: `tailwind.css` at its root with a
base layer for the derived pages, `views/Layout.scala` with the link, and utility classes on the
live components.
