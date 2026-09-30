<!-- draft -->
# Deployment files

What `eezo build` stages, what `eezo deploy` writes, and every field in the generated `fly.toml`.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `target/eezo/stage/`: `lib/`, `db/`, `Dockerfile`; jar name collisions
- the Dockerfile line by line: base image, `WORKDIR`, `ENTRYPOINT` and why not `CMD`, `MaxRAMPercentage`
- `fly.toml`: `app`, `primary_region`, `[deploy] release_command`, `[http_service]` and its checks; what to change for live
- what is never written: secrets
- `eezoJavaVersion`

## Where the material is

- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/Deploy.scala`, `EezoPlugin.scala` (`eezoStage`)
- `bin/eezo` (`write_fly_toml`)
