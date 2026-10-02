# Deployment

What one command stages, what the platform is trusted with, and why a redeploy is short and a
first deploy isn't. [Deploying](../how-to/deploy.md) is the recipe; this is the shape behind
it.

## What gets staged

`eezo build` writes `target/eezo/stage/`:

```
target/eezo/stage/
  lib/          every runtime jar, your own code packaged as one of them
  db/           your migrations and schema.json, copied as they are
  Dockerfile    generated every time; never edited
```

The Dockerfile is short. A JRE image for the Java version in `eezoJavaVersion`, the two
directories copied in, and an entrypoint:

```dockerfile
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY lib lib
COPY db db
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "lib/*", "Main"]
```

No sbt in the image, no build tooling, no source. `MaxRAMPercentage` instead of a fixed heap,
because the platform decides the machine size. And `ENTRYPOINT`, not `CMD`, so that
arguments appended by the platform become arguments to your own `main`: `migrate --apply` is
the same dispatch every other command goes through, and no arguments means `boot`, which
serves.

`db/` is copied because the release command below resolves migrations against the working
directory. An image without it would report no pending migrations against an empty database,
and serve over a schema that was never created.

## The image is built remotely

`fly deploy` takes the staged directory as a build context and builds the image on Fly's
builder. Docker never runs on your laptop, and the deploy works from a machine that doesn't
have it. The staged directory is also a plain container context, so `docker build
target/eezo/stage` works anywhere you'd rather build it yourself.

## Migrations run before traffic moves

The generated `fly.toml` carries one line that does the ordering:

```toml
[deploy]
  release_command = 'migrate --apply'
```

Fly runs that in a one-off machine, with the new image, before any serving machine is updated.
A nonzero exit stops the deployment and the old version keeps serving. So the schema converges
before new code takes traffic, and the guarantee comes from the platform's release step rather
than from a script of eezo's that would have to be right about ordering, retries and
rollbacks. `eezo deploy` streams Fly's output, because the migration's output is in
it and that's what a failed deploy needs on screen.

## Why Fly first

A deploy target has to offer four things for the command to stay one command: an app created
from a terminal, a release step that runs the new image before traffic switches, a secret
store the application reads from the environment, and a Postgres that attaches as one
`DATABASE_URL`. Fly has all four, and `fly launch` picks the nearest region, which eezo
shouldn't reimplement. `eezo deploy` runs `fly launch` once, then replaces the config it wrote
with eezo's own: the release command, the service on the app's port, and a health check on
`/eezo/health`. The file is yours after that.

> **Daniel:** the platforms you surveyed and what ruled each one out, if you want that on the
> page.

## A first deploy and a redeploy

The first deploy is the slow one, and most of it is you: creating the app, creating and
attaching a Postgres, setting `EEZO_SECRET`. The deploy itself stops until a database secret
exists, because an application with migrations will run `migrate --apply` and needs somewhere
to run it.

A redeploy is the one that's short. `fly.toml` is committed, so nothing is created; the command
stages the jars, hands the directory to the remote builder, waits for the release command, and
polls `/eezo/health` until the new machine answers. The build is the long part, and it's a
layer cache away from fast.

> **Daniel:** the measured time for a redeploy, and what it was measured on.

## What a second target would need

Nothing in the staged directory names Fly. A second target is a second `deploy` branch in the
launcher, and it needs the same four things: a way to run `migrate --apply` with the new image
before traffic moves, a secret store that becomes environment variables, a health check
pointed at `/eezo/health`, and an image built from the staged context. A platform without a
release step can still run the migrations as a separate container from the same image, before
the deploy, which is what [deploying](../how-to/deploy.md) shows for a plain Docker host.

## Where to go next

The command's exact flags and the generated files are in the how-to. The migrations the
release step runs are [schema and migrations](schema-and-migrations.md).
