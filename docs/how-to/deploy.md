# Deploy

Ship an application to Fly.io with one command, from a laptop or from CI, and what to change for
a live application, a custom domain, or another host. [The deploy walkthrough](../tutorials/deploy-to-fly.md)
does a first deploy step by step with real output.

## First deploy

```bash
eezo deploy
```

On the first run, with no `fly.toml` yet, it runs `fly launch` to create the app and pick your
nearest region, then writes its own `fly.toml`: the release command, the service on port 8080,
and a health check on `/eezo/health`. The file is yours; commit it, edit it, and eezo rewrites
it only if you delete it.

If the application has migrations, the deploy stops here until a database secret exists:

```bash
fly postgres create --name myapp-db --region fra
fly postgres attach myapp-db --app <your-app>
```

`attach` sets `DATABASE_URL` on the app. Then `eezo deploy` again.

## What a deploy does

1. `eezo build` stages `target/eezo/stage/`: every runtime jar, your `db/migrations`, and a
   generated JRE-only Dockerfile. No Docker runs on your machine.
2. `fly deploy` builds the image remotely and starts a one-off machine running
   `migrate --apply` with the new image, before any serving machine is touched. A failed
   migration stops the deploy and the old version keeps serving.
3. The new machines come up, the old ones drain, and eezo waits for `/eezo/health`.

## Secrets

```bash
fly secrets set EEZO_SECRET=<a long random string>
```

Without it, every restart signs everyone out. `DATABASE_URL` is set by `attach`; `EEZO_DB_URL`,
`EEZO_DB_USER` and `EEZO_DB_PASS` work too. Secrets never go in `fly.toml`.

## From CI

```yaml
- uses: superfly/flyctl-actions/setup-flyctl@master
- run: ./bin/eezo deploy
  env:
    FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}
```

With `fly.toml` committed there's nothing to create, so the deploy is the same two steps. A
first-ever deploy from CI needs `--app <name>`, because the launch can't prompt without a
terminal. The eezo repository's own `deploy-site.yml` is a working example.

## A live application

`eezo deploy` writes `auto_stop_machines = 'stop'` and `min_machines_running = 0`, which is
right for request and response and wrong for sockets: a machine stopped for idleness closes
every live page, and each reconnect is a cold start. For a live application, set in `fly.toml`:

```toml
[http_service]
  min_machines_running = 1
```

Origins need nothing on Fly: it forwards the browser's `Host` and sends `X-Forwarded-Proto`.
Behind a proxy of your own, forward both, or list the origin in `allowedOrigins`.

## A custom domain

```bash
fly certs add example.com
```

It prints the `A` and `AAAA` records to set. The certificate issues once DNS resolves.

## Another host

The staged directory is a plain container context. Build it anywhere:

```bash
eezo build
docker build -t myapp target/eezo/stage
docker run -e DATABASE_URL=... -e EEZO_SECRET=... -p 8080:8080 myapp
```

Run the migrations first with the same image: `docker run ... myapp migrate --apply`. The
entrypoint is the application's own `main`, so any eezo command works as the container's
arguments.

## When it goes wrong

- Health checks never go green: `internal_port` in `fly.toml` must equal the app's `port`.
- Migration failed: the old version is still serving; `fly logs` has the migration's output.
- `eezo deploy` shows fewer routes than expected: `eezo routes` and its warnings, run locally.
