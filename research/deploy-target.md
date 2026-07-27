# Research: deploy target, one command, under sixty seconds, real HTTPS

Resolves [rcardin/eezo#7](https://github.com/rcardin/eezo/issues/7). Part of #1.

Date of investigation: 2026-07-26. All prices and terms verified against provider
documentation on that date.

---

## 1. The finding that reframes the question

**No candidate provisions a JVM application from nothing to a working HTTPS URL in
under sixty seconds.** Not one. Cold provisioning is a three to six minute
operation everywhere, and on the managed platforms it is frequently much worse.

The sixty second claim is only achievable as a **redeploy onto already warm
infrastructure**. That is not a weakness of the claim, it is the shape of the
claim, and eezo should say so out loud rather than let the audience discover it.
The design consequence is concrete: `eezo deploy` needs a sibling command that
does the cold path (`eezo host provision` or `eezo deploy --bootstrap`), and the
sixty second promise attaches to `eezo deploy` on a host that command already
prepared.

The second finding is that **the JVM artifact, not the provider, is what most
threatens the sixty second budget**, and the deciding factor is whether the fast
path ships a container image through a registry or ships changed JARs directly to
the host. A registry round trip is two network hops through a third party. rsync
of a `sbt-native-packager` layout is one hop, direct, and only the changed
application JAR moves because the dependency JARs are byte identical between
builds.

---

## 2. Comparison table

| | **VPS + systemd + seeded proxy** | **VPS + Kamal (or equivalent)** | **Fly.io** | **Render** | **Railway** | **Docker to anything** |
|---|---|---|---|---|---|---|
| **Warm redeploy, reported** | rsync delta + `systemctl restart` + JVM boot, ~10 to 35 s (budget in §5) | "a few seconds when you don't have to rebuild"; full cycle "about 2 minutes" ([hboon](https://hboon.com/one-command-deploy-how-kamal-2-changed-how-i-ship/)) | "around 4-5 minutes" typical ([community.fly.io](https://community.fly.io/t/fly-deploy-sometimes-takes-a-long-time-why/24798)) | 2 to 5 min typical; prebuilt image reported at ~7 min ([community.render.com](https://community.render.com/t/speeding-up-prebuilt-docker-image-deployments/24850)) | minutes, builds on their infra | inherits the worst of both |
| **Cold provision** | 3 to 6 min (§5) | `kamal setup` installs Docker, boots accessories, deploys ([docs](https://kamal-deploy.org/docs/commands/setup/)) | first `fly launch` + build + push, minutes | first build, minutes, git connection required | minutes | minutes |
| **Must be pre-provisioned** | host, JRE, Postgres, proxy, DNS or IP cert | host, Docker, registry creds, DNS | Fly account, `fly.toml`, Dockerfile | account, **git provider connection**, repo | account, project | everything |
| **Pre-provisioning can be a command?** | yes, `hcloud server create` is ~15 to 30 s to SSH ([community reports](https://lowendtalk.com/discussion/184625/how-is-hetzner-cloud-able-to-deploy-servers-in-a-few-seconds-unlike-digitalocean-vultr-etc)) | yes, `kamal setup` | yes, `fly launch` | **no**, git provider connection is a browser OAuth flow | partially | no |
| **TLS first issue** | seconds once DNS resolves ([Caddy](https://caddyserver.com/docs/automatic-https#on-demand-tls): "usually only a few seconds") | same, kamal-proxy uses `acme/autocert` (§4.2) | **zero**, `*.fly.dev` is pre-terminated ([docs](https://fly.io/docs/networking/custom-domain/)) | **zero**, `*.onrender.com` | **zero**, `*.up.railway.app` | your problem |
| **Postgres** | same box, local socket, free | Kamal accessory container, same box, free ([docs](https://kamal-deploy.org/docs/configuration/accessories/)) | Managed Postgres, **$38/mo minimum** ([docs](https://fly.io/docs/mpg/)) | free 1 GB **expires in 30 days** ([docs](https://render.com/docs/free)); legacy Starter $7/mo ([docs](https://render.com/docs/postgresql-legacy-instance-types)) | template deploy, `DATABASE_URL` reference variable ([docs](https://docs.railway.com/guides/postgresql)) | separate purchase |
| **URL reaches app via** | env file written by deploy, `127.0.0.1` | `env.secret` in `.kamal/secrets` ([docs](https://kamal-deploy.org/docs/configuration/environment-variables/)) | `fly mpg attach` sets a secret | internal connection URL / `fromDatabase` | reference variable | manual |
| **Cost, smallest useful** | **€5.49/mo** Hetzner CX23, 2 vCPU / 4 GB / 40 GB ([Hetzner docs](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)) | same | ~$2.02/mo machine ([docs](https://fly.io/docs/about/pricing/)) **+ $38/mo Postgres** | $7 web + ~$6 Postgres ≈ **$13/mo** ([Render](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses)) | $5/mo Hobby ([docs](https://docs.railway.com/pricing/plans)) | varies |
| **Free tier for a first try** | none, €5.49 up front | none | **2 VM hours / 7 days, machines auto stop after 5 min** ([docs](https://fly.io/docs/about/free-trial/)) | **best**: free web + free Postgres, no card ([docs](https://render.com/docs/free)) | $5 one time, then $1/mo credit ([docs](https://docs.railway.com/pricing/plans)) | none |
| **First-run credentials** | **SSH key only** | SSH key **+ container registry account** | account, no card at signup | account **+ GitHub/GitLab/Bitbucket OAuth** | account | account + registry |
| **Artifact** | native-packager stage dir (no Docker) or image | Docker image | Docker image | Docker image or native runtime | Docker image | Docker image |
| **Lock-in** | none | none | `fly.toml`, MPG, Fly private networking | `render.yaml`, git-push model | project model | none |
| **CLI auth** | SSH agent | SSH agent + registry token | `fly auth login`, macaroon tokens ([docs](https://fly.io/docs/security/tokens/)) | API key / deploy hook / `render` CLI ([docs](https://render.com/docs/deploys)) | `railway login`, `RAILWAY_TOKEN` ([docs](https://docs.railway.com/guides/cli)) | registry creds |

---

## 3. First-run story, assessed on its own

The ticket's prior is that "an account, a token, and a payment method" is worse
than "an SSH host". The evidence partly confirms and partly contradicts this.

**Confirms it for Fly.io, on a point the prior did not anticipate.** Fly no longer
requires a credit card at signup, so on the narrow question of payment method the
prior is wrong. But the trial terms are fatal for a framework's first impression:
the trial is "2 hours of machine runtime or 7 days of access, whichever comes
first", and, decisively, "**Trial Machines are set to automatically stop after
running for 5 minutes**"
([fly.io/docs/about/free-trial](https://fly.io/docs/about/free-trial/)). A stranger
evaluating eezo would deploy, show a colleague, and find the app dead. Beyond the
trial, "All organizations (except for Linked Organizations) require a credit card
on file" ([fly.io/docs/about/pricing](https://fly.io/docs/about/pricing/)), and
the only managed Postgres option starts at **$38/month**
([fly.io/docs/mpg](https://fly.io/docs/mpg/)). Fly's own marketing acknowledges it
"no longer offers a free tier for new users"
([Render's comparison article](https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026),
a competitor source, so treat as directional).

**Contradicts it for Render, which has the best pure onboarding.** Free web
service, free 1 GB Postgres, no credit card
([render.com/docs/free](https://render.com/docs/free),
[render.com/docs/your-first-deploy](https://render.com/docs/your-first-deploy):
"This tutorial uses free Render resources. No payment is required."). But Render's
first-run friction is a *different* credential: you must "Connect your Git
provider" through account settings before you can deploy at all. That is a browser
OAuth handshake granting a third party access to your source repositories. For a
CLI-first framework this is worse than an SSH key, and it means `eezo deploy`
cannot be the first command a user runs. It also disqualifies Render on timing
independently (§4.4).

**Complicates it for the VPS.** The prior undercounts what "an SSH host" really
costs a stranger. It is a paid server *and*, historically, a domain name *and* a
DNS A record *and* propagation before ACME can succeed. Three of those four are
not commands.

Two developments substantially repair this, and they are the most useful new
findings in this survey:

1. **Let's Encrypt IP address certificates went generally available on 2026-01-15**
   ([letsencrypt.org](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)).
   A VPS can serve real, publicly trusted HTTPS on its bare IP with **no domain at
   all**. The constraint is that IP identifiers are permitted only on the
   `shortlived` profile, 160 hours (~6.7 days) validity
   ([letsencrypt.org/docs/profiles](https://letsencrypt.org/docs/profiles/)), which
   Let's Encrypt qualifies: "We recommend this profile for those who fully trust
   their automation to renew their certificates on time. This profile is not for
   everyone." Caveat on readiness: Caddy currently has an open bug issuing IP certs
   even with the `shortlived` profile requested
   ([caddyserver/caddy#7399](https://github.com/caddyserver/caddy/issues/7399)), so
   this is an emerging path, not a shipped one. Do not make it load bearing yet.
2. **Wildcard DNS services** (`1.2.3.4.sslip.io` resolves to `1.2.3.4`) let ACME
   issue a normal 90 day DNS certificate with no domain purchase
   ([sslip.io](https://sslip.io/)). The catch is real and disqualifying for the
   stage: sslip.io is not on the Public Suffix List, so Let's Encrypt rate limits
   are **shared across every user of the service**, and the quota has been
   exhausted in practice
   ([cunnie/sslip.io#108](https://github.com/cunnie/sslip.io/issues/108)). Fine as
   an onboarding convenience, unacceptable as the demo path.

**Net assessment of first run:** Render wins on credential minimalism, loses on
timing and on requiring repository access. The VPS wins on *kind* of credential
(an SSH key is a credential the user already has and already controls) and can now
reach real HTTPS without a domain purchase. Fly is the worst first run of the
three despite having no card requirement, because a five minute machine lifetime
is not a working deployment.

---

## 4. Per candidate detail

### 4.1 Plain VPS, systemd, seeded reverse proxy

**Pre-provisioning required for the fast path:** a Linux host with systemd, a JRE
(or a bundled `jlink` runtime), Postgres, a proxy already holding a valid
certificate, and a non-root user with passwordless SSH and a writable target
directory. The proof of concept at `skiff` already implements exactly this and
enumerates the same prerequisites (`skiff/docs/DEPLOY.md`: `sbt stage`, then
`rsync -az --delete target/universal/stage/ <host>:<path>/`, then write a systemd
unit and `systemctl enable --now`).

**Why this is the fastest path, specifically.** `sbt-native-packager` emits a
`bin/` + `lib/` layout where every dependency JAR is a separate file with a stable
name. Between two builds of the same project, only the application JAR changes.
`rsync -az --delete` therefore transfers a few hundred kilobytes, not a 180 to 300
MB container image. No registry, no push, no pull, one hop.

**TLS.** Caddy obtains and renews certificates with no configuration beyond a
hostname; it requires ports 80 and 443 externally reachable, an A/AAAA record, and
a persistent writable data directory
([caddyserver.com/docs/automatic-https](https://caddyserver.com/docs/automatic-https)).
First issuance is "usually only a few seconds, and only that initial handshake is
slow"
([on-demand TLS](https://caddyserver.com/docs/automatic-https#on-demand-tls)),
with the explicit warning to "be mindful of how quickly your CA is able to issue
certificates". Renewal is background and continuous with exponential backoff.
Traefik is the alternative and is comparable, but carries a documented HA
limitation that matters if eezo ever grows past one host: "it is not possible to
run multiple instances of Traefik 2.0 with Let's Encrypt enabled, because there is
no way to ensure that the correct instance of Traefik receives the challenge
request"
([doc.traefik.io](https://doc.traefik.io/traefik/reference/install-configuration/tls/certificate-resolvers/acme/)).
Caddy is the better default for a single box.

**Crucially, TLS cost is zero on the fast path.** The certificate is issued once at
provisioning time and lives in the proxy. A redeploy never touches ACME. Let's
Encrypt rate limits (50 certificates per registered domain per 7 days, 5 duplicates
per 7 days, [letsencrypt.org/docs/rate-limits](https://letsencrypt.org/docs/rate-limits/))
are therefore irrelevant to `eezo deploy` and only bite during repeated
provisioning of the same hostname, which is worth guarding against in the bootstrap
command.

**Postgres.** `apt install postgresql`, a role and database created at bootstrap,
and a `DATABASE_URL` pointing at `127.0.0.1:5432` written into the 0600
`EnvironmentFile` the systemd unit loads. No network, no cloud bill, no extra
failure domain. This is the cleanest answer in the survey to "how does Postgres
come to exist and how does its URL reach the app".

**Cost.** Hetzner CX23 (2 vCPU, 4 GB, 40 GB NVMe) at **€5.49/month** as of the
2026-06-15 adjustment
([docs.hetzner.com](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)).
The ARM CAX11 is €5.99. 4 GB is comfortably enough for a JVM heap plus Postgres on
the same box; the 256 MB to 512 MB tiers elsewhere are not.

**Lock-in and failure modes.** Lock-in is nil; the target is "a Linux box". Failure
modes are the ones you own: disk fills, the box dies and there is no replica, ACME
fails if DNS drifts, and there is no managed backup unless eezo ships one. The
honest trade is that the escape hatch is trivial and the blast radius is total.

**CLI auth.** SSH agent. Nothing to obtain, nothing to store, nothing to revoke
centrally.

### 4.2 VPS + Kamal, or a Kamal equivalent built into eezo

`kamal setup` does the cold path: "Install Docker on any server that might be
missing it (using get.docker.com): root access is needed via SSH for this", log
into the registry locally and remotely, build/push/pull the image, "Ensure
kamal-proxy is running and accepting traffic on ports 80 and 443", boot containers,
route traffic, prune
([kamal-deploy.org/docs/installation](https://kamal-deploy.org/docs/installation/)).
`kamal deploy` is the warm path: build, push, pull, boot the new container, wait
for "200 OK to GET /up", swap traffic, stop the old container
([docs](https://kamal-deploy.org/docs/commands/deploy/)). `--skip-push` exists for
when the image is already in the registry.

**TLS.** `ssl: true` in `deploy.yml`, with the documented constraint that "This
requires that we are deploying to one server and the host option is set"
([docs](https://kamal-deploy.org/docs/configuration/proxy/)). Under the hood
kamal-proxy uses Go's `golang.org/x/crypto/acme/autocert` (confirmed in
[`go.mod`](https://github.com/basecamp/kamal-proxy/blob/main/go.mod) and imported
in `internal/server/tls_on_demand.go`), whose default directory is Let's Encrypt
production. The README requires hosts be specified "to ensure that certificates are
not maliciously requested for arbitrary hostnames"
([README](https://github.com/basecamp/kamal-proxy)).

**Timing evidence.** Reported by practitioners rather than by Basecamp: full cycle
"about 2 minutes" including build, push, and zero downtime swap; redeploys "a few
seconds when you don't have to rebuild the images"; a rollback is a proxy pointer
switch taking about 10 seconds
([hboon.com](https://hboon.com/one-command-deploy-how-kamal-2-changed-how-i-ship/)).
A second practitioner account reports that after `kamal setup`, "within a few
minutes, your application is running in production with SSL certificates
provisioned automatically through Let's Encrypt"
([ivanturkovic.com](https://www.ivanturkovic.com/2026/02/06/honest-take-kamal-rails-deployment/)).
That same account enumerates the real failure modes, several of which eezo would
inherit verbatim: cross platform builds from Apple Silicon to AMD64 produce
containers that "crash immediately on the server" with cryptic errors; Docker's use
of the iptables NAT table means an exposed Postgres accessory port **bypasses UFW
rules**; and Docker builds on hosts with 2 GB RAM or less "trigger deployment
timeouts" by pushing the machine into swap. The default health check window is
"30 seconds by default", which for a JVM app is tight but survivable.

**Postgres.** Accessories are containers Kamal boots independently and does not
update on deploy, with no zero downtime handling
([docs](https://kamal-deploy.org/docs/configuration/accessories/)). The URL reaches
the app as `env.secret` sourced from `.kamal/secrets`
([docs](https://kamal-deploy.org/docs/configuration/environment-variables/)).

**First run cost versus 4.1.** Kamal's structural disadvantage for eezo is the
**container registry**. Kamal requires registry credentials
(`KAMAL_REGISTRY_PASSWORD`) before the first deploy, which is a second account and
a second token on top of the SSH key. And it puts a full image push and pull inside
the deploy path, which is precisely the thing the sixty second budget cannot
absorb over conference wifi.

### 4.3 Fly.io

**The genuine strength is TLS and URL.** Every app gets a `fly.dev` subdomain "with
automatic HTTPS/TLS setup out of the box, no configuration required"
([docs](https://fly.io/docs/networking/custom-domain/)). There is no ACME wait, no
DNS record, no propagation. `fly certs add example.com` handles custom domains via
TLS-ALPN, HTTP-01, or DNS-01. For a live demo this eliminates an entire class of
risk.

**The timing does not hold.** Users report `fly deploy` "usually around 4-5
minutes", with random excursions to 25 to 30 minutes, and the original poster is
explicit that "there's no single step that takes longer, its the whole process,
building and pushing the image, that takes longer overall". The thread's
resolution is instructive: the cause was that "Depot is having issues with their
build servers"
([community.fly.io](https://community.fly.io/t/fly-deploy-sometimes-takes-a-long-time-why/24798)).
That is a **third party build farm inside the critical path of a stage demo**, and
it is not something eezo can mitigate.

Fly's own numbers are for machine operations, not deploys: creation is slower due
to "infrastructure latency, database coordination, and image downloading" while
*starting* an already created machine is roughly 10 to 150 ms
([fly.io/blog/fly-machines](https://fly.io/blog/fly-machines/)). Fly is explicit
that this excludes your app: "your application boot time is something you should
optimize. We can't help with that (yet!)".

**Fly's docs name the JVM problem directly.** In the deployment troubleshooting
guide: "For apps with slow startup (Rails, Django, **large JVM apps**), you may
need 15-30 seconds" of health check grace period
([fly.io/docs/getting-started/troubleshooting](https://fly.io/docs/getting-started/troubleshooting/)).
That is a primary source confirming the JVM tax on this specific platform.

**Postgres.** `fly launch --db mpg` provisions Managed Postgres
([docs](https://fly.io/docs/flyctl/launch/)). Plans run "$38 (Basic) to $1,922
(Performance)" plus "$0.28 per provisioned GB monthly"
([docs](https://fly.io/docs/mpg/)), across 12 regions. The unmanaged legacy option
is effectively dead: "We are not able to provide support or guidance for unmanaged
Postgres"
([fly.io/docs/postgres](https://fly.io/docs/postgres/)). **$38/month for the
smallest database is the single worst cost number in this survey** and it is
disqualifying for "the smallest useful size".

**CLI auth.** Well designed. `fly auth login` mints a short lived broad token;
`fly tokens deploy` and `fly tokens org` produce narrowly scoped macaroons for
automation, and the docs explicitly steer you to "the token with the narrowest
access that will work"
([docs](https://fly.io/docs/security/tokens/)). If eezo ever needs a managed
target, this is the auth model to copy.

**Lock-in.** `fly.toml`, Fly private networking, and MPG. The escape hatch is
genuinely a Dockerfile, which is the mitigating factor.

### 4.4 Render

**Disqualified on timing, twice over.**

First, deploys: initial deploys typically 2 to 5 minutes, and users deploying
*prebuilt* Docker images report roughly 6 minutes to boot and start plus another
minute to finish
([community.render.com](https://community.render.com/t/speeding-up-prebuilt-docker-image-deployments/24850),
[community.render.com](https://community.render.com/t/extremely-slow-build-times/24484)).
Second, the free tier itself: "Render **spins down** a Free web service that goes
15 minutes without receiving any inbound traffic", and spin up "takes about one
minute" ([render.com/docs/free](https://render.com/docs/free)). A JVM app on top of
that adds its own boot time. A demo where the first audience member waits a minute
for a loading page is worse than no demo.

**Free tier is nonetheless the best in the survey**, and worth naming as such: 750
free instance hours per workspace per month, free 1 GB Postgres, no card. But the
Postgres expires: "**Free Render Postgres databases expire 30 days after
creation**", with a 14 day grace period before deletion. A framework whose tutorial
database evaporates in 30 days generates support load.

**Cost.** Starter web service $7/month (512 MB, 0.5 CPU,
[docs](https://render.com/docs/compute-plans)); combined with the smallest paid
Postgres, Render's own article puts the realistic floor at "about **$13/month**"
([render.com](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses)).
Postgres storage is $0.30/GB/month; persistent disk $0.25/GB/month; build minutes
$5 per 1,000.

**The structural problem for a CLI framework** is that Render's primary model is
git push. There is a `render deploys create` CLI, a Deploy Hook URL, and a REST
trigger endpoint ([docs](https://render.com/docs/deploys)), so `eezo deploy` is
implementable, but the service must first be created through a dashboard flow tied
to a connected git provider. `eezo deploy` cannot be the first command.

### 4.5 Railway

**Best of the managed trio on first run friction, worst on substance.** The trial
grants a one time $5 credit with no credit card, then the Free plan drops to $1 of
credit per month with 1 vCPU, 0.5 GB RAM, and 1 project
([docs.railway.com/pricing/plans](https://docs.railway.com/pricing/plans)). $1/month
does not run a JVM application. Usage is $10/GB/month RAM and $20/vCPU/month;
Hobby is $5/month including $5 of usage. Railway also notes that as of March 30 it
"requires the use of a post-paid card".

**0.5 GB RAM on the free plan is below the floor for a JVM.** This is the same trap
as Fly's 256 MB machines.

**CLI is good:** `railway login`, `railway login --browserless`, and `RAILWAY_TOKEN`
/ `RAILWAY_API_TOKEN` for CI, with `railway up` deploying the current directory
([docs](https://docs.railway.com/guides/cli)). Postgres is a template deploy that
exposes `DATABASE_URL`, consumed by other services through reference variables
([docs](https://docs.railway.com/guides/postgresql)).

**Lock-in** is the project/service/reference-variable model. Escape hatch is the
Dockerfile.

### 4.6 Docker to anything

This is not a target, it is a refusal to pick one, and the ticket already rules it
out by asking for a single target. It should still exist as the **escape hatch**: a
`Dockerfile` at the repo root that runs unchanged on Cloud Run, App Runner, Render,
Railway, Fly, or a laptop. That is exactly how `skiff` framed it
(`skiff/docs/DEPLOY.md`: "Skiff's deploy story is Docker-first... Pick the
platform; the build artifact is the same"). Keeping the Dockerfile is what makes
the lock-in answer for *every* candidate "none, worst case you move the image".

---

## 5. The sixty second budget, worked

### Warm redeploy, VPS, rsync path (recommended)

| Step | Cost | Notes |
|---|---|---|
| `sbt stage`, incremental, one file changed | 3 to 15 s | **the largest and least controllable term**; Scala compilation, not networking |
| `rsync -az --delete` of the stage dir | 1 to 3 s | only the app JAR differs; dependency JARs are byte identical |
| `ssh` + `systemctl restart` | < 1 s | |
| JVM boot to first request served | 2 to 10 s | assumes a lean framework, not Spring Boot |
| Health check + proxy confirms upstream | 1 to 5 s | Caddy is already holding the certificate; ACME is not touched |
| **Total** | **~10 to 35 s** | comfortable headroom under 60 s |

The honest risk is the first row. Scala compilation is the enemy of the sixty
second claim, more than any provider. `eezo deploy` should time and print each
phase so the number is defensible on stage and debuggable off it.

### Warm redeploy, container path, same host

Add an image build (cached, 10 s to 90 s depending on Dockerfile layering,
[practitioner report](https://wolf-tech.io/blog/kamal-2-production-zero-downtime-deploys-secrets)),
a registry push, and a registry pull. Even with perfect layering that is two round
trips through a third party. On conference wifi this is where sixty seconds dies.

### Cold provision, VPS

| Step | Cost |
|---|---|
| `hcloud server create` to SSH reachable | 15 to 30 s ([reports](https://lowendtalk.com/discussion/184625/how-is-hetzner-cloud-able-to-deploy-servers-in-a-few-seconds-unlike-digitalocean-vultr-etc)) |
| Install JRE, Postgres, Caddy | 1 to 3 min |
| DNS record and propagation | seconds to minutes |
| ACME issuance | "usually only a few seconds" ([Caddy](https://caddyserver.com/docs/automatic-https#on-demand-tls)) |
| **Total** | **3 to 6 min** |

This is a separate command. It should be one, and it should be idempotent.

---

## 6. The JVM artifact tax

Applies to every candidate, and it is the reason "container or JAR" is a real
decision rather than a formality.

**Image size.** `eclipse-temurin:17-jdk-alpine` is roughly 180 MB before anything
of yours is added; a `jlink` custom runtime on Alpine lands near 75 MB, and a
`java.base`-only runtime can reach about 30 MB
([Adoptium container docs](https://adoptium.net/installation/containers),
[practitioner measurements](https://medium.com/@RoussiAbdelghani/optimizing-java-base-docker-images-size-from-674mb-to-58mb-c1b7c911f622)).
Treat these as directional, but the ordering is stable: a stock JDK image is two to
six times a `jlink`ed one. Scala makes this worse than plain Java because the
standard library and dependency JARs add megabytes to the classpath before your
code exists.

**Cold start.** Community measurement puts JVM cold starts at roughly 5 to 30
seconds, and Fly's own troubleshooting docs corroborate the top of that range for
"large JVM apps"
([fly.io](https://fly.io/docs/getting-started/troubleshooting/)). Two consequences:

1. **Scale to zero is incompatible with the claim.** Render's free tier (15 minute
   spin down, ~1 minute spin up) and Fly's autostop both put a JVM cold start in
   front of a user's first request. eezo's target must keep the process warm.
2. **256 MB and 512 MB instances are not viable.** Fly's cheapest machine is
   `shared-cpu-1x` at 256 MB; Railway's free plan is 0.5 GB; Render's free and
   Starter tiers are both 512 MB. A JVM in 256 MB is at real risk of the OOM
   killer. This quietly invalidates the headline low prices on all three managed
   platforms: the *usable* JVM tier is a step or two up. Hetzner's €5.49 CX23 ships
   4 GB.

**Recommendation on artifact:** ship the `sbt-native-packager` stage directory as
the fast path, and a `Dockerfile` (multistage, `jlink` runtime) as the escape
hatch. Do not put the container in the sixty second path.

---

## 7. Recommendation

**Pick a single VPS target: any SSH reachable Linux host with systemd, Hetzner as
the documented reference. Ship eezo's own Kamal equivalent over SSH: rsync of a
native-packager layout, a systemd unit, Caddy seeded with the certificate at
provisioning time, and Postgres on the same box. Ship a Dockerfile as the escape
hatch, not as the deploy path.**

Reasoning, in order of weight:

1. **It is the only candidate that can actually make sixty seconds, and the only
   one where eezo controls every hop.** The measured budget lands at 10 to 35
   seconds with headroom (§5). Every managed alternative puts a third party build
   farm inside the critical path, and Fly's own community thread shows exactly what
   that costs when it degrades (4 to 5 minutes normally, 25 to 30 minutes when
   Depot has a bad day). On a stage, in front of several hundred people, over
   conference wifi, an uncontrollable dependency is not an acceptable risk. The
   `skiff` scope document already reached this conclusion independently: "pre
   provision the host so deploy is only a push and a restart"
   (`skiff/SCALADAYS-2026-SCOPE.md`).
2. **The first-run credential is the best kind: one the user already owns.** An SSH
   key requires no account, no token, no payment method, no OAuth grant over their
   source repositories, and no vendor relationship. Render's free tier is more
   generous but demands git provider access before the first deploy; Fly's trial
   stops your machine after five minutes; Railway's free plan gives you half a
   gigabyte, which will not hold a JVM.
3. **Cost at the smallest useful size is decisively better, and "useful" is the
   operative word.** €5.49/month buys 2 vCPU and 4 GB, enough for a JVM heap and
   Postgres together. Fly is $2/month for a machine that cannot comfortably hold a
   JVM plus $38/month for the smallest database. Render is about $13/month for a
   512 MB instance. The VPS is cheaper *and* larger.
4. **Postgres is the cleanest possible answer.** Same box, local socket, no extra
   bill, no extra failure domain, `DATABASE_URL` written into the systemd
   `EnvironmentFile` at deploy time. This is what "boring infrastructure, Postgres
   for anything needing coordination" looks like when taken seriously.
5. **Lock-in is nil.** The target is a Linux box. There are no terms to change.

**Build eezo's own SSH orchestration rather than shelling out to Kamal.** Kamal is
the right *design* to copy and the right thing to read, but adopting it directly
imports a Ruby runtime dependency, a container registry account as a second
first-run credential, and an image push/pull inside the deploy path. Read
kamal-proxy's TLS approach (`acme/autocert`, host allowlist) and Kamal's health
check and traffic swap sequence, then implement the equivalent over rsync.

**Two commands, stated plainly.**
- `eezo host provision` (or `eezo deploy --bootstrap`): idempotent, 3 to 6 minutes,
  installs the JRE, Postgres, and Caddy, creates the role and database, obtains the
  certificate, writes the systemd unit. Runs once per host.
- `eezo deploy`: 10 to 35 seconds, warm host, this is the sixty second claim.

Say the two-command shape out loud in the talk. Claiming a cold provision in sixty
seconds would be false and the audience will know it.

**Two things to nail down before committing:**
- Measure `sbt stage` incremental time on a realistic eezo project. It is the
  largest term in the budget and the only one not yet measured here.
- Decide the no-domain onboarding story. Let's Encrypt IP certificates are GA and
  would remove the last non-command prerequisite, but Caddy has an open bug
  ([#7399](https://github.com/caddyserver/caddy/issues/7399)) and the 160 hour
  lifetime demands renewal automation that actually works. sslip.io is the
  fallback, but its shared rate limit
  ([#108](https://github.com/cunnie/sslip.io/issues/108)) makes it unsafe for the
  demo. For the stage itself, use a real domain with DNS pre-pointed.

### Confidence

**High (85%) on the timing analysis and on the conclusion that no candidate does
cold provisioning in sixty seconds.** The evidence is consistent across primary
docs and independent practitioner reports, and the failure of the managed platforms
on timing is well supported.

**Medium-high (70%) on the recommendation itself.** Two things could change it.
The first is the sbt compile time, which is unmeasured and is the largest term in
the budget; if incremental staging of a realistic eezo app takes 40 seconds, the
whole analysis needs revisiting and the answer becomes "make compilation faster",
not "change providers". The second is a values judgment rather than a factual one:
if eezo weights "a stranger can try it with zero money down" above "the demo is
bulletproof", Render's free tier is the better answer and the sixty second claim
has to be softened. I weight the demo higher because it is the stated headline
claim and because a VPS the user controls is a better long term story for a
framework than a free tier that expires in 30 days.

**Lower confidence (55%) on the rsync-versus-container choice** specifically. It is
correct for the sixty second budget, but it means eezo's fast path and its escape
hatch are different code paths, and dual paths rot. A well layered container image
where only a small application layer moves could close much of the gap. Worth a
measurement before it is locked in.

---

## Appendix: sources

Primary provider and project documentation:
- [fly.io/docs/launch/deploy](https://fly.io/docs/launch/deploy/), [fly.io/docs/flyctl/launch](https://fly.io/docs/flyctl/launch/), [fly.io/docs/about/pricing](https://fly.io/docs/about/pricing/), [fly.io/docs/about/free-trial](https://fly.io/docs/about/free-trial/), [fly.io/docs/about/billing](https://fly.io/docs/about/billing/), [fly.io/docs/mpg](https://fly.io/docs/mpg/), [fly.io/docs/postgres](https://fly.io/docs/postgres/), [fly.io/docs/networking/custom-domain](https://fly.io/docs/networking/custom-domain/), [fly.io/docs/security/tokens](https://fly.io/docs/security/tokens/), [fly.io/docs/getting-started/troubleshooting](https://fly.io/docs/getting-started/troubleshooting/), [fly.io/blog/fly-machines](https://fly.io/blog/fly-machines/)
- [render.com/docs/free](https://render.com/docs/free), [render.com/docs/your-first-deploy](https://render.com/docs/your-first-deploy), [render.com/docs/deploys](https://render.com/docs/deploys), [render.com/docs/compute-plans](https://render.com/docs/compute-plans), [render.com/docs/postgresql-refresh](https://render.com/docs/postgresql-refresh), [render.com/docs/postgresql-legacy-instance-types](https://render.com/docs/postgresql-legacy-instance-types), [render.com/docs/postgresql-creating-connecting](https://render.com/docs/postgresql-creating-connecting)
- [docs.railway.com/pricing/plans](https://docs.railway.com/pricing/plans), [docs.railway.com/guides/cli](https://docs.railway.com/guides/cli), [docs.railway.com/guides/postgresql](https://docs.railway.com/guides/postgresql)
- [kamal-deploy.org/docs/installation](https://kamal-deploy.org/docs/installation/), [/commands/setup](https://kamal-deploy.org/docs/commands/setup/), [/commands/deploy](https://kamal-deploy.org/docs/commands/deploy/), [/configuration/proxy](https://kamal-deploy.org/docs/configuration/proxy/), [/configuration/accessories](https://kamal-deploy.org/docs/configuration/accessories/), [/configuration/environment-variables](https://kamal-deploy.org/docs/configuration/environment-variables/), [github.com/basecamp/kamal-proxy](https://github.com/basecamp/kamal-proxy) (README, `go.mod`, `internal/server/tls_on_demand.go`)
- [caddyserver.com/docs/automatic-https](https://caddyserver.com/docs/automatic-https), [/caddyfile/directives/tls](https://caddyserver.com/docs/caddyfile/directives/tls), [caddyserver/caddy#7399](https://github.com/caddyserver/caddy/issues/7399)
- [doc.traefik.io ACME certificate resolvers](https://doc.traefik.io/traefik/reference/install-configuration/tls/certificate-resolvers/acme/)
- [letsencrypt.org/docs/rate-limits](https://letsencrypt.org/docs/rate-limits/), [/docs/profiles](https://letsencrypt.org/docs/profiles/), [/docs/integration-guide](https://letsencrypt.org/docs/integration-guide/), [6-day and IP GA announcement](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)
- [docs.hetzner.com price adjustment](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/), [hetzner.com/cloud/cost-optimized](https://www.hetzner.com/cloud/cost-optimized/)
- [sslip.io](https://sslip.io/), [cunnie/sslip.io#108](https://github.com/cunnie/sslip.io/issues/108)
- [adoptium.net/installation/containers](https://adoptium.net/installation/containers)

Secondary, practitioner reports (used only for timings the vendors do not publish,
and labelled as such inline):
- [community.fly.io: fly deploy sometimes takes a long time](https://community.fly.io/t/fly-deploy-sometimes-takes-a-long-time-why/24798)
- [community.render.com: speeding up prebuilt Docker image deployments](https://community.render.com/t/speeding-up-prebuilt-docker-image-deployments/24850), [extremely slow build times](https://community.render.com/t/extremely-slow-build-times/24484)
- [hboon.com: one command deploy, how Kamal 2 changed how I ship](https://hboon.com/one-command-deploy-how-kamal-2-changed-how-i-ship/)
- [ivanturkovic.com: an honest take on deploying Rails with Kamal](https://www.ivanturkovic.com/2026/02/06/honest-take-kamal-rails-deployment/)
- [wolf-tech.io: Kamal 2 in production](https://wolf-tech.io/blog/kamal-2-production-zero-downtime-deploys-secrets)
- [render.com: how much does cloud application hosting cost](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses) (vendor authored)
- [Optimizing Java base Docker images 674 MB to 58 MB](https://medium.com/@RoussiAbdelghani/optimizing-java-base-docker-images-size-from-674mb-to-58mb-c1b7c911f622)
- [lowendtalk: Hetzner provisioning speed](https://lowendtalk.com/discussion/184625/how-is-hetzner-cloud-able-to-deploy-servers-in-a-few-seconds-unlike-digitalocean-vultr-etc)

Internal reference (read only): `skiff/docs/DEPLOY.md`, `skiff/SCALADAYS-2026-SCOPE.md`.
