# Measurement harnesses

Salvage, not results. These are the benchmark rigs three research agents built in `/tmp` on 2026-07-26 before their run was cut short by an account spend limit. Sources, scripts, and raw logs are preserved here so a re-run does not have to rebuild them; compiled output and build caches were dropped.

**Read nothing here as a finding.** Only one of the three agents survived to analyse its own numbers. The other two left rigs and raw logs with no interpretation, and some runs ended in errors that were never diagnosed. Treat all of it as a starting point for the ticket that owns the question, not as evidence.

| Directory | Built by | Status |
|---|---|---|
| `mig-bench/` | [Migration engine research](https://github.com/rcardin/eezo/issues/5) | **Analysed.** Backs the timings in `research/migrations.md`; that ticket is resolved. |
| `bench/http/` | [HTTP server](https://github.com/rcardin/eezo/issues/3) research | **Analysed.** Backs every measured claim in `research/http-server.md`; that ticket is resolved. Rebuilt on 2026-07-27: three bugs in the salvaged rigs were fixed (see that document's Appendix A.0), Vert.x and Netty were added, and rigs for pinning, WebSocket backpressure, classloader retention, and connection scale were written. Raw logs in `bench/http/logs/`. |
| `bench/` (everything else) | [DB/query layer](https://github.com/rcardin/eezo/issues/4) research | **Raw.** Compile-scaling projects for Magnum, Quill, and ScalaSql at several model counts. No findings document was ever written. |
| `zinc-lab/` | [Hot reload research](https://github.com/rcardin/eezo/issues/6) | **Analysed.** Backs every measured claim in `research/build-reload.md`; that ticket is resolved. Rebuilt on 2026-07-27: the two failed experiments were diagnosed (both were edits that left the project not compiling, see that document's Appendix A.0), the rig was rewritten as a parameterised generator plus four drivers, and the whole matrix now runs inside one warm sbt session instead of a cold `sbt -batch` process per experiment. Fourteen edits at three project sizes, plus dev-loop latency and `derives`-clause compile scaling. The 2026-07-26 logs are kept at `zinc-lab/logs/2026-07-26-salvage/` and nothing is quoted from them. |

`bench/http/run.sh` is the reusable part of the HTTP rigs: it compiles all six
boot rigs against a given `JAVA_HOME` and runs N repeats of each on fixed ports,
so boot time, same-port rebinding, and thread residue can be compared across JDK
versions. `bench/http/vtcount.sh` holds N WebSocket connections open against one
of the `ConnScale*` servers and samples its heap and thread count.
`bench/http/src/SlowClient.java` is the piece most worth reusing elsewhere: a
hand-rolled RFC 6455 client that completes the handshake and then never reads
again, which is the only reliable way to drive a server into its write
backpressure regime.

`zinc-lab/` is now a generator plus four drivers, and it is the most reusable rig
here. `gen.sh <outdir> <n_typeclass_consumers> <n_member_consumers> <n_unrelated>
<n_derives>` emits a self-contained sbt project in eezo's shape (a case class
with a `derives` clause, files that `summon` the derived instances, files that
reference one field by name, and unrelated controls), with two custom sbt
commands baked into `build.sbt` so that edits can be applied and compiles timed
from **inside a warm sbt session**. `edits.sh` holds fourteen named edits, every
one of which is required to leave the project compiling. `run.sh` runs the whole
matrix in one sbt invocation; `analyse.sh` turns the log into
`label, sources, rounds, microseconds`. `loop.sh` measures what `run.sh` cannot:
cold `sbt` process cost, `sbt ~` latency from file save to rebuild complete
(detected by a marker the build prints itself, not by counting sbt's monitor
lines, which races), and the same edits under scala-cli. `derives-scale.sh` plus
`reparse-derives.sh` measure what a `derives` clause costs the compiler as a
function of clause length and model count, using the compiler's own profiler.
`all.sh`, `all2.sh` and `all3.sh` are the batch drivers that produced the logs.

The salvaged `exp.sh` is preserved at `zinc-lab/logs/2026-07-26-salvage/exp.sh`
for provenance and should not be reused: it timed a cold `sbt -batch compile` per
experiment, which measures sbt's 1.7 s boot rather than the edit loop.

The rigs here overlap with what [the reload spike](https://github.com/rcardin/eezo/issues/10) has to measure properly. That ticket owns the real numbers.
