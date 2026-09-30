<!-- draft -->
# The sbt plugin

`sbt-eezo`: the tasks and settings it adds, the source it generates, and the sbt versions it supports.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- enabling it: `enablePlugins(EezoPlugin)`, `noTrigger`
- tasks
  - `eezoGenerateRoutes`
  - `eezoDev`, `eezoRestart`, `eezoStop`
  - `eezoRoutes`, `eezoStatus`, `eezoSync`, `eezoMigrate`, `eezoFreeze`
  - `eezoStage`
- settings: `eezoJavaVersion`
- what is inherited from `run`: fork, working directory, `javaOptions`, `connectInput`
- the generated file: location, the `// from` comments, `storeFor`, the `Guarded` demand when `eezo-auth` is named
- sbt 1 and sbt 2, the floors, and why it is cross built from one source

## Where the material is

- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/EezoPlugin.scala`, `RouteGenerator.scala`
- `docs/adr/0002-sbt-eezo-is-cross-built-for-sbt-1-and-sbt-2.md`
- `project/Toolchain.scala`
