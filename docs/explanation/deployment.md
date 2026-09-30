<!-- draft -->
# Deployment

What one command stages, what the platform is trusted with, and why sixty seconds is a redeploy and not a first deploy.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the staged layout: every runtime jar, the migrations, a JRE-only Dockerfile
- why the image is built remotely and no Docker runs on the laptop
- migration ordering delegated to the platform's release command
- Fly.io as the first target: what the survey found and what it ruled out
- the sixty second claim and its shape
- what a second target would need

## Where the material is

- `research/deploy-target.md`
- `bin/eezo`, `modules/sbt-plugin/src/main/scala/io/eezo/sbt/Deploy.scala`
- `docs/tutorials/deploy-to-fly.md`
