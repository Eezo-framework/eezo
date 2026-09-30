<!-- draft -->
# The dev loop

What `eezo dev` does on every save, what the browser does in response, and why a failed compile is not an outage.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the sbt plugin's watch, the route generator, the forked JVM replaced on each restart
- the reload client on every dev page, and how it is kept out of production
- the drift check at boot and the drift page
- one secret per sbt session, so a restart keeps you signed in
- the measurements: cold sbt, watch latency, JVM boot
- what `eezo dev` does not do: hot swap, state preservation

## Where the material is

- `CONTEXT.md`, Development
- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/EezoPlugin.scala`, `DevProcess.scala`; `modules/http/.../Reload.scala`
- `research/build-reload.md`
