<!-- draft -->
# Deploy

Ship an application to Fly.io with one command, from a laptop or from CI, and what to change for a live application or another host.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- `eezo deploy`: what it creates on the first run, what `fly.toml` carries, and what it refuses to continue without
- attaching Postgres and the `DATABASE_URL` secret
- the migration ordering: `release_command`, the one-off machine, the old version kept serving on failure
- in CI: `FLY_API_TOKEN`, `--app` on a first deploy, no TTY
- a live application: `min_machines_running = 1`, `wss://`, origins behind a proxy
- a custom domain: `fly certs add`, the DNS records
- another host: what `eezo build` stages and the Dockerfile it writes; running the image anywhere
- reading deploy logs and the release machine's output

## Where the material is

- `docs/tutorials/deploy-to-fly.md`
- `docs/tutorials/a-live-page.md` §8
- `bin/eezo` (`deploy`, `write_fly_toml`)
- `modules/sbt-plugin/src/main/scala/io/eezo/sbt/Deploy.scala`, `EezoPlugin.scala` (`eezoStage`)
- `site/fly.toml` and `.github/workflows/deploy-site.yml`, a deployment without a database
