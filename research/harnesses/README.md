# Measurement harnesses

Salvage, not results. These are the benchmark rigs three research agents built in `/tmp` on 2026-07-26 before their run was cut short by an account spend limit. Sources, scripts, and raw logs are preserved here so a re-run does not have to rebuild them; compiled output and build caches were dropped.

**Read nothing here as a finding.** Only one of the three agents survived to analyse its own numbers. The other two left rigs and raw logs with no interpretation, and some runs ended in errors that were never diagnosed. Treat all of it as a starting point for the ticket that owns the question, not as evidence.

| Directory | Built by | Status |
|---|---|---|
| `mig-bench/` | [Migration engine research](https://github.com/rcardin/eezo/issues/5) | **Analysed.** Backs the timings in `research/migrations.md`; that ticket is resolved. |
| `bench/` | [HTTP server](https://github.com/rcardin/eezo/issues/3) and [DB/query layer](https://github.com/rcardin/eezo/issues/4) research | **Raw.** Server boot and WebSocket rigs for Jetty, Helidon, Undertow, and the JDK server; compile-scaling projects for Magnum, Quill, and ScalaSql at several model counts. No findings document was ever written. |
| `zinc-lab/` | [Hot reload research](https://github.com/rcardin/eezo/issues/6) | **Raw, and partly failed.** An sbt project probing Zinc invalidation when a case class with a `derives` clause changes — the crux question for eezo's compile budget. `logs/C_rename_field.log` and `logs/D_separate_given.log` both end in compile errors that were never investigated, so their numbers mean nothing yet. |

`zinc-lab/exp.sh` is the reusable part: it runs a batch `sbt compile` and greps the log for source counts and total time. The experiment labels (`baseline`, `A_touch_user`, `B_add_field`, `C_rename_field`, `D_separate_given`) name the edit each run applied.

The rigs here overlap with what [the reload spike](https://github.com/rcardin/eezo/issues/10) has to measure properly. That ticket owns the real numbers.
