# Research: Scala 3 capture checking, maturity, syntax, diagnostics, cost

Resolves [rcardin/eezo#2](https://github.com/rcardin/eezo/issues/2). Part of #1.

Date of investigation: 2026-07-27. All version claims verified against the
[scala/scala3 releases API](https://api.github.com/repos/scala/scala3/releases)
and the repository at commit `a036a3a7` (2026-07-24) on that date.

**Empirical basis.** Unlike a pure literature review, most of the load bearing
claims here were compiled and measured locally. Toolchain: `scala-cli` 1.x on
Temurin JDK 26, Scala 3.8.4 (current stable), Apple Silicon. Every error message
marked "measured" in this document was produced on this machine, not quoted from
a third party. Reproduction instructions are in the appendix.

---

## 1. The findings that reframe the question

There are three, and each of them independently weakens the case for capture
checking as eezo's capability mechanism.

**First: capture checking does not improve the error eezo actually cares about.**
The ticket frames eezo's signature moment as "a compile error a person reads on a
projector" when a capability is missing. Capture checking has nothing to do with
that error. A missing capability is a failure of *given resolution*, which runs
in the typer, long before the `cc` phase exists. Measured, on Scala 3.8.4, with
capture checking fully enabled:

```
-- [E172] Type Error: Tx2.scala:17:36
17 |  val bad: Int = insertUser("grace")
   |                                    ^
   |No given instance of type Tx^ was found for parameter tx of method insertUser
```

That is the identical message, modulo a stray `^`, that you get with a plain
`using tx: Tx`. Capture checking bought exactly one caret. Everything capture
checking *does* buy (scope escape detection, separation checking) is a different
error class, fired at a different phase, on a different mistake. **The headline
demo does not need capture checking, and capture checking does not improve the
headline demo.**

**Second: the mechanism eezo would copy is already in production, and it is not
capture checking.** Ox, SoftwareMill's direct style concurrency library and the
closest thing the ecosystem has to eezo's stated design, expresses "this
operation needs a scope" with a plain `trait` plus `@implicitNotFound`. There is
no `^`, no `caps.Capability`, no `captureChecking` import anywhere in
[`core/src/main/scala/ox/Ox.scala`](https://github.com/softwaremill/ox/blob/master/core/src/main/scala/ox/Ox.scala).
Measured against `com.softwaremill.ox::core:1.0.0`, calling `fork` outside a
scope produces:

```
-- [E172] Type Error: OxStyle.scala:6:26
6 |    fork { println("hi") }.discard   // no scope in sight
  |                          ^
  |This operation must be run within a `supervised` or `supervisedError` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using Ox` parameter list.
```

That message is better than anything capture checking prints, because a human
wrote it. This is the ceiling of the conventional approach, and the ceiling is
high.

**Third: a capture checked library gives a non capture checked consumer nothing
at all, and says nothing about it.** Capture checking is enabled per compilation
unit. Measured: a library compiled with `-language:experimental.captureChecking`
that exports `def insertUser(name: String)(using tx: Tx^): Int`, packaged to a
jar and consumed from a module that does *not* enable capture checking, compiles
fine and reports the missing capability as:

```
[error] No given instance of type eezolib.Tx was found for parameter tx of method insertUser in object Db
```

The `^` is gone. No escape checking happens in the consumer. There is no warning
that a guarantee was dropped. So the safety property eezo would advertise
evaporates silently for every user who does not opt in, which will be all of them
by default. **Capture checking is not a property a library can give its users. It
is a property a user must adopt.**

The second order consequence of the third finding is the useful one: because
capture checking is invisible to downstream and erases to plain types, **eezo can
adopt it later without a breaking change**. That converts this from a now
decision into a later decision, which is the basis of the recommendation in §9.

---

## 2. Maturity

### 2.1 Version landscape as of 2026-07-27

Verified against the releases API, not from memory.

| Track | Latest | Published |
|---|---|---|
| **Next (current stable)** | **3.8.4** | 2026-06-05 |
| **LTS** | **3.3.8** | 2026-06-11 |
| **In flight** | 3.9.0-RC4 | 2026-07-23 |
| **`main` develops** | 3.10.0 (`developedVersion` in `project/Versions.scala`) | unreleased |

3.8.0 shipped 2026-01-22. 3.9 is the **next LTS** and is explicitly feature
frozen against 3.8: per the [3.8 release notes](https://www.scala-lang.org/news/3.8/),
3.9 LTS introduces no new experimental or preview features, so that code written
against 3.8 needs no changes for 3.9. Existing *preview* features may be promoted
to stable in 3.9. Capture checking is not a preview feature (see §2.2), so **3.9
LTS will not stabilise capture checking**.

### 2.2 Stability tier: experimental, not preview

Scala 3 has three tiers, and the distinction matters because it determines what
promises exist.

- **Stable.** Available unconditionally.
- **Preview.** Will be stabilised, may still change incompatibly, requires the
  `-preview` compiler flag. `into` and `packageObjectValues` are here.
- **Experimental.** Provisional, may be removed, no promise of any kind.

Capture checking is **experimental**, and has been for its entire life. In
[`compiler/src/dotty/tools/dotc/config/Feature.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/config/Feature.scala):

```scala
val captureChecking = experimental("captureChecking")
val separationChecking = experimental("separationChecking")
val safe = experimental("safe")
```

It has never been proposed as a SIP. It has no preview flag. There is no
published stabilisation date.

### 2.3 The flags, and one genuinely favourable surprise

Three ways to turn it on, all equivalent:

```scala
import language.experimental.captureChecking     // per file
import language.experimental.separationChecking  // implies captureChecking
import language.experimental.safe                // implies captureChecking + safe subset
```

```bash
-language:experimental.captureChecking
```

The favourable surprise is that **capture checking does not virally mark your
code experimental**. Most experimental language imports force the importing unit
into experimental mode, which means every definition needs `@experimental` or the
whole build needs `-experimental`, and that infects downstream. Capture checking
is explicitly exempted, from the same file:

```scala
val nonViralExperimentalFeatures: Set[TermName] =
  Set(captureChecking, separationChecking, safe)

/** Experimental language imports that imply that the importing unit
 *  is experimental.
 */
def experimentalAutoEnableFeatures(using Context): List[TermName] =
  defn.languageExperimentalFeatures
    .map(sym => experimental(sym.name))
    .filterNot(nonViralExperimentalFeatures.contains(_))
```

Confirmed empirically: the §1 example compiles on **stable 3.8.4** with nothing
but the language import. No `-experimental`, no nightly, no `@experimental`
annotations, and the resulting jar is consumable from ordinary stable Scala.

This is a real and underappreciated point in capture checking's favour, and it is
the technical basis for the "adopt later" escape hatch in §9.

### 2.4 What the maintainers actually say, verbatim

The stable reference documentation at
[docs.scala-lang.org/scala3/reference/experimental/cc.html](https://docs.scala-lang.org/scala3/reference/experimental/cc.html)
says:

> capture checking is still highly experimental and unstable, and it evolves
> quickly

The nightly documentation is more optimistic. From
[`overview.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/overview.md)
on `main`:

> Both extensions are experimental, which means that details can still change.
> Capture checking is by now quite mature and we expect it to be stabilized soon.
> Separation checking is still a bit more fluid at present.

"Soon" is not a date, and the same page opens by calling the whole thing
"the most important new feature of Scala", which is advocacy rather than a
schedule. Meanwhile
[`how-to-use.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/how-to-use.md)
opens its enablement instructions with:

> Use Scala 3 nightly for the latest features and fixes.

**That is the official documentation, in the section on how to enable the
feature, telling you to run a nightly compiler.** Take that as the honest signal
of where the feature actually lives.

### 2.5 Churn is high and ongoing

The vocabulary changed under the current stable release. Measured on 3.8.4, the
universal capability `cap`, which appears throughout every tutorial and
conference talk about capture checking from 2023 to 2025, no longer resolves:

```
-- [E006] Not Found Error: Tx.scala:24:20
24 |  val leaked: () ->{cap} Int = db.transact { (tx: Tx^) ?=> () => insertUser("hopper") }
   |                    ^^^
   |          Not found: capability cap - did you mean Map? or perhaps caps?
```

It was renamed to `caps.any`, with a companion `caps.fresh`. `Capability` also
became `sealed` with exactly two subtraits, so the natural spelling
`trait Tx extends caps.Capability` is now a compile error:

```
-- [E112] Syntax Error: Tx.scala:5:6
5 |trait Tx extends caps.Capability:
  |      ^
  |      Cannot extend sealed trait Capability in a different source file
```

You must pick `SharedCapability` or `ExclusiveCapability`, a distinction that did
not exist in earlier iterations and which forces a semantic decision (can two
threads hold this at once?) at the moment of declaring the trait.

Both of those are breaking changes to user facing syntax, shipped inside the
experimental window, which is exactly what "experimental" licenses. The point is
not that the changes are wrong. The point is that **eezo's public API vocabulary
would be downstream of a vocabulary that is still moving.**

---

## 3. Syntax as it stands today

Verified against the reference docs on `main` and by compilation on 3.8.4.

### 3.1 Core vocabulary

| Construct | Meaning |
|---|---|
| `A^{c1, c2}` | value of type `A` that may retain capabilities `c1`, `c2` |
| `A^` | shorthand for `A^{any}`, may retain *anything* |
| `A` (bare) | equivalent to `A^{}`, pure, retains nothing |
| `A -> B` | **pure** function, captures nothing |
| `A => B` | alias for `A ->{any} B`, impure, may capture anything |
| `A ->{c} B` | function that may capture exactly `c` |
| `caps.any` | the universal capability (formerly `cap`) |
| `caps.fresh` | the universal *result* capability |
| `caps.Capability` | sealed root; extend `SharedCapability` or `ExclusiveCapability` |
| `caps.CapSet` | carrier for capture set type parameters, `[C >: CapSet <: CapSet^]` |
| `caps.Classifier`, `.only[…]`, `.except[…]` | capability classification and projection |
| `update def` | soft modifier; method may mutate receiver state (`Stateful` classes) |
| `consume` | parameter/val modifier; caller loses access after the call |

The `->` versus `=>` distinction is the crux of the whole design and it is a
**silent semantic reinterpretation of existing syntax**. From
[`basics.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/basics.md):

> The usual function type `A => B` now stands for a function that can capture
> arbitrary capabilities. We call such functions _impure_. By contrast, the new
> single arrow function type `A -> B` stands for a function that cannot capture
> any capabilities, or otherwise said, is _pure_.

For eezo this is worth pausing on. In a capture checked file, every `=>` a user
writes is the maximally permissive type. Getting a useful guarantee requires the
user to write `->` or an explicit capture set, which means **the safe thing is not
the default thing**, and the defaults produce no errors and no safety.

### 3.2 Composition with `using`, the question eezo actually asked

The good news: **`using` parameters are capture tracked exactly like ordinary
parameters.** There is no special case, no gap, no extra ceremony. Measured:

```scala
def plainParam(tx: Tx^): () -> Int = () => tx.exec("x")
def usingParam(using tx: Tx^): () -> Int = () => tx.exec("x")
```

produces two structurally identical errors:

```
-- [E007] Type Mismatch Error: Track.scala:11:43
11 |def usingParam(using tx: Tx^): () -> Int = () => tx.exec("x")
   |                                           ^^^^^^^^^^^^^^^^^^
   |              Found:    () ->{tx} Int
   |              Required: () -> Int
   |
   |              Note that capability `tx` cannot flow into capture set {}.
```

You can also name a `using` parameter in a capture set in the result type, which
is the shape eezo would want:

```scala
def usingOk(using tx: Tx^): () ->{tx} Int = () => tx.exec("x")   // compiles
```

Context functions compose too. `def transact[A](body: Tx^ ?=> A): A` works, and
the escape check fires through it (§4.2). So the `using tx: Tx^` style eezo
imagined **does work as imagined**. The mechanism is not the problem. The
problems are cost (§5), diagnostics (§4), and the fact that it does not address
the demo (§1).

### 3.3 One sharp edge in the composition

The escape check only fires when the *expected type* is restrictive enough. This
compiles clean, with capture checking fully on:

```scala
val escape: () => Int = transact { (tx: Tx^) ?=> () => withHat("a") }
```

because `() => Int` means `() ->{any} Int`, which permits the capability to
escape. Drop the annotation, or write `() -> Int`, and the error appears. This is
correct by design, and it is also a trap: **a user who annotates their vals with
ordinary Scala types gets no checking and no indication that checking was
skipped.**

---

## 4. Diagnostic quality

This is the section that decides the ticket, so it is the longest. Sources are
the verbatim expected compiler output in
[`tests/neg-custom-args/captures/*.check`](https://github.com/scala/scala3/tree/main/tests/neg-custom-args/captures)
(254 `.check` files at commit `a036a3a7`), plus messages produced locally.

### 4.1 Missing capability: no improvement whatsoever

Covered in §1 and it bears repeating because it is the ticket's core question.
Measured, 3.8.4, capture checking on:

```
-- [E172] Type Error: Tx2.scala:17:36
17 |  val bad: Int = insertUser("grace")
   |                                    ^
   |No given instance of type Tx^ was found for parameter tx of method insertUser
```

`E172` is `MissingImplicitArgument`. It is the same code path, the same message
template, and the same phase as plain `using`. Capture checking contributes the
`^` character and nothing else. **Verdict: neutral to slightly negative**, since
`Tx^` reads worse on a projector than `Tx` to an audience that has never seen the
syntax and will spend the next ten seconds wondering what the caret means instead
of listening.

### 4.2 Scope escape: the best message capture checking has

This is the feature's genuine contribution, and the first line is genuinely good.
Measured locally, 3.8.4:

```
-- [E007] Type Mismatch Error: Tx3.scala:17:27
17 |  val leaked1 = transact { (tx: Tx^) ?=> () => insertUser("hopper") }
   |                           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   |Capability `tx` outlives its scope: it leaks into outer capture set 's1 which is owned by value leaked1.
   |The leakage occurred when trying to match the following types:
   |
   |Found:    (tx: Tx^) ?->'s2 () ->{tx} Int
   |Required: (Tx^) ?=> () ->'s1 Int
   |
   |where:    ?=> refers to a root capability created in value leaked1 when checking argument to parameter body of method transact
   |          ^   refers to the root capability caps.any
   |
   | longer explanation available when compiling with `-explain`
```

**Legibility assessment for a non expert.** The first sentence, up to the comma,
is excellent: "Capability `tx` outlives its scope". A room full of Scala
developers who have never seen capture checking would understand that, and it
would land on a projector. Everything after the comma is a liability:

- `'s1`, `'s2` are **capture set inference variables**. They are internal solver
  state leaked into user facing output. There is no explanation of what a
  primed identifier is, and nothing a user can do with the knowledge that the
  set is called `'s1`.
- `?->'s2` is an arrow with a subscripted solver variable attached. It is not
  syntax the user can write, and it does not appear in any tutorial.
- The `where:` block explains `?=>` and `^` but not `'s1`, which is the part that
  actually needs explaining.
- "root capability" is undefined jargon appearing in the first error a user ever
  sees.

The honest summary is that **the first eight words are demo quality and the
remaining twelve lines are compiler internals.** eezo cannot ship the first eight
words without the other twelve.

The same message from the dotty test suite, confirming this is the intended
stable output and not a local artifact
([`reference-cc.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/reference-cc.check)):

```
-- [E007] Type Mismatch Error: tests/neg-custom-args/captures/reference-cc.scala:44:29
44 |  val later = usingLogFile { file => () => file.write(0) } // error
   |                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^
   |Capability `file` outlives its scope: it leaks into outer capture set 's2 which is owned by value later.
   |The leakage occurred when trying to match the following types:
   |
   |Found:    (file: java.io.FileOutputStream^) ->'s3 () ->{file} Unit
   |Required: java.io.FileOutputStream^ => () ->'s2 Unit
   |
   |where:    => refers to a root capability created in value later when checking argument to parameter op of method usingLogFile
   |          ^  refers to the root capability caps.any
```

### 4.3 The error blames the wrong thing when a local is involved

Measured. Given a capability laundered through one intermediate `val`:

```scala
def leakViaLocal(using tx: Tx^): () -> Int =
  val h = () => tx.exec("d")
  h
```

the error names `h`, not `tx`:

```
-- [E007] Type Mismatch Error: Track3.scala:10:2
10 |  h
   |  ^
   |  Found:    () ->{h} Int
   |  Required: () -> Int
   |
   |  Note that capability `h` cannot flow into capture set {}.
```

The check is sound, the capability does not escape. But the diagnostic points at
the local variable rather than the transaction, and the word "transaction" never
appears. In a realistic eezo handler with three or four intermediate bindings the
user is told that `h` cannot flow into `{}` and must work backwards to discover
that they leaked a database transaction out of a request scope. **This is a
serious usability finding and it is worse than the §4.2 case, which is the case
everyone demos.**

Related: capture set inference degrades in the presence of *other* type errors in
the same method. In one measured run the compiler printed `(h : () -> Int)`, that
is, claiming the closure was pure, while an isolated compilation of the same code
correctly printed `(h : () ->{tx} Int)`. The soundness of the check is not in
question, only the accuracy of the printed type during error recovery. But a
demo is exactly the situation where several errors are on screen at once.

### 4.4 Separation checking: the worst output in the corpus

Separation checking is the part that would police "two request handlers must not
share one transaction". From
[`sep-use2.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/sep-use2.check),
verbatim expected compiler output:

```
-- Error: tests/neg-custom-args/captures/sep-use2.scala:23:8
23 |    { f(c) } // error
   |        ^
   |Separation failure: argument of type  (c : Object^)
   |to a function of type Object^ ->{c} Object^{fresh}
   |corresponds to capture-polymorphic formal parameter x$0 of type  Object^²
   |and hides capabilities  {c}.
   |Some of these overlap with the captures of the function prefix.
   |
   |  Hidden set of current argument        : {c}
   |  Hidden footprint of current argument  : {c}
   |  Capture set of function prefix        : {f, fresh}
   |  Footprint set of function prefix      : {f, c}
   |  The two sets overlap at               : {c}
   |
   |where:    ^     refers to a root capability in the type of parameter c
   |          ^²    refers to a root capability created in value x4 when checking argument to parameter x$0 of method apply
   |          fresh is a root capability associated with the result type of (x$0: Object^³): Object^{fresh}
```

Sixteen lines, five undefined terms ("capture-polymorphic", "hidden set",
"footprint", "root capability", "prefix"), superscripted carets that distinguish
three different `^` occurrences, and a five row table of set arithmetic. And from
[`sep-compose.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/sep-compose.check):

```
-- Error: tests/neg-custom-args/captures/sep-compose.scala:32:7
32 |  seq3(f)(f) // error
   |       ^
   |Separation failure: argument of type  (f : () ->{a} Unit)
   |to method seq3: (x: () => Unit)(y: () ->{a, any} Unit): Unit
   |corresponds to capture-polymorphic formal parameter x of type  () => Unit
   |and hides capabilities  {f}.
   |Some of these overlap with the captures of the second argument with type  (f : () ->{a} Unit).
   |
   |  Hidden set of current argument        : {f}
   |  Hidden footprint of current argument  : {f, a, io}
   |  Capture set of second argument        : {f}
   |  Footprint set of second argument      : {f, a, io}
   |  The two sets overlap at               : {f, a, io}
```

**This is the single worst diagnostic I found and it is disqualifying for a
conference stage.** It is not badly written; it is a faithful rendering of a
genuinely complicated judgment. That is the problem. The information content
required to explain a separation failure is larger than what fits on a slide.

### 4.5 Other message families, for completeness

Reference not included, from `reference-cc.check`:

```
-- [E223] CaptureChecking Error: tests/neg-custom-args/captures/reference-cc.scala:42:20
42 |    def f = println(c) // error
   |                    ^
   |                    Reference `c` is not included in the allowed capture set 's1
   |                    of the enclosing class A.
```

Compact and readable except for `'s1` again.

Scope extrusion into a mutable var, from
[`scope-extrusions.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/scope-extrusions.check):

```
-- [E007] Type Mismatch Error: tests/neg-custom-args/captures/scope-extrusions.scala:10:8
10 |    v = x  // error
   |        ^
   |        Found:    (x : IO)
   |        Required: IO^{any}
   |
   |        Note that capability `x` cannot flow into capture set {any}
   |        because (x : IO) in method f1 is not visible from any in variable v.
   |
   |        where:    any is a root capability classified as SharedCapability in the type of variable v
```

`consume` violation, from
[`boxed-consume.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/boxed-consume.check).
This one is actually the **best written message in the entire corpus**:

```
-- Error: tests/neg-custom-args/captures/boxed-consume.scala:16:10
16 |  println(d) // error
   |          ^
   |        Separation failure: Illegal access to (d : Object^), which was passed as a consume parameter to method g
   |        on line 15 and therefore is no longer available.
```

It names the value, names the method, cites the line number, and states the
consequence in plain English. If every capture checking message were written like
this the answer to this ticket would be different. Almost none of them are.

And the reach capability message, which shows the failure mode when the compiler
gives up:

```
-- Error: tests/neg-custom-args/captures/boxed-consume.scala:10:52
10 |  def h(consume x: (Object^, Object^)): Object^ = x._1  // error: local reach leak
   |                                                  ^^^^
   |                                      Local any in the type of parameter x leaks into capture scope of method h.
   |                                      You could try to abstract the capability in a capset variable.
```

"You could try to abstract the capability in a capset variable" is advice no
user of a web framework can act on.

### 4.6 Independent corroboration from a sophisticated user

Nicolas Rinaudo, an experienced Scala developer and author, on the official
Scala users forum in
[Capture checking being confusing again](https://users.scala-lang.org/t/capture-checking-being-confusing-again/12083):

> everything else about capture checking feels reasonable and logical, but this
> might take a while for 'regular' developers to get their head around

His confusion was over "which `cap` is implicitly captured and which needs to be
explicitly declared", triggered by a message containing
`Found: rep$_.this.Rand^{rep$_.this, s, cap}`, that is, a compiler generated
symbol name leaked into the type. Odersky's reply concedes the point directly:

> adding cap silently to new instances of capability classes is indeed confusing!

and he filed a PR to remove the behaviour. That is the right response and it is
also evidence for the finding: **the mental model was still being corrected in
response to user confusion, in public, on the official forum.** Confidence:
this is a secondary source in the sense that it is a forum thread rather than
documentation, but it is on the official Scala forum with a verbatim reply from
the language designer, so I weight it highly.

### 4.7 Diagnostics verdict

| Message class | Legibility for non expert | Fit for a projector |
|---|---|---|
| Missing capability (`E172`) | good, but identical to plain `using` | yes, and unimproved |
| `consume` after transfer | **excellent**, names value, method, line | yes |
| Scope escape, direct | first line excellent, rest is solver state | first line only |
| Scope escape, via a local | **blames the wrong identifier** | no |
| Reference not included (`E223`) | acceptable, leaks `'s1` | marginal |
| Reach capability leak | actionable only by experts | no |
| **Separation failure** | **worst in corpus, set arithmetic tables** | **no** |

The distribution is bimodal. Capture checking's *hand written* messages are good
to excellent. Its *generated* messages, which are the majority and which include
every message involving inference, print solver variables, superscripted carets,
and set algebra. eezo cannot choose which class a user will hit.

---

## 5. Compile time cost, measured

The ticket calls edit to visible reload under three seconds existential, so this
was measured rather than researched.

### 5.1 Method

40, 10, 80 and 160 file synthetic projects, each file declaring a capability
trait, a case class, a class with a `using` constructor parameter, and an object
with seven methods taking `using tx: TxN` (with `^` in the capture checked
variant, without in the baseline). Otherwise byte identical. Compiled with
`scala-cli --server=false -O -Yprofile-enabled` on Scala 3.8.4, JDK 26. The
figure reported is the sum of per phase wall clock nanoseconds reported by the
compiler's own profiler, which excludes JVM startup and scala-cli overhead and is
therefore the cleanest available measure of compiler work.

### 5.2 Results

| Files | Baseline | Capture checked | Overhead | `cc` + `setupCC` phases |
|---|---|---|---|---|
| 10 | 1182.2 ms | 1421.8 ms | **+20.3%** | 217.5 ms |
| 40 | 1861.5 ms (mean of 3) | 2269.6 ms (mean of 3) | **+21.9%** | 362.7 ms |
| 80 | 2454.1 ms | 3071.9 ms | **+25.2%** | 548.4 ms |
| 160 | 3513.0 ms | 4349.9 ms | **+23.8%** | 741.7 ms |

Run to run variance at 40 files was under 1.5% (baseline 1845.8 / 1866.0 / 1872.6;
capture checked 2267.4 / 2268.9 / 2272.5), so the effect is far outside noise.

Two structural observations. First, the overhead is **stable at roughly a fifth
to a quarter** across an order of magnitude of project size, so it is a tax rather
than a cliff. Second, there is a **fixed floor of about 200 ms** visible at 10
files, which is the capture checker's setup cost and does not amortise away on
small projects.

The `cc` phase is inserted after the pattern matcher and before `elimOpaque`,
and at 40 files it alone costs 339 ms against a 1862 ms baseline, that is, the
single new phase costs about 18% of the entire pre existing pipeline.

### 5.3 The number that actually matters is better than that

For eezo's three second reload the relevant measurement is an incremental
recompile of one changed file with a warm build server, not a clean build.
Measured, 80 file project, warm Bloop, one file touched, five runs:

| | run 1 | run 2 | run 3 | run 4 | run 5 | median |
|---|---|---|---|---|---|---|
| Baseline | 0.54 s | 0.54 s | 0.55 s | 0.52 s | 0.55 s | **0.54 s** |
| Capture checked | 0.50 s | 0.51 s | 0.51 s | 0.51 s | 0.56 s | **0.51 s** |

**Indistinguishable.** The difference is within the scala-cli round trip floor.
When only one file recompiles, one file's worth of `cc` phase is a few
milliseconds and vanishes into process overhead.

### 5.4 The exception, and it is a bad one

[scala/scala3#25975](https://github.com/scala/scala3/issues/25975), **open and
unfixed**, reports exponential compile time under capture checking's safe mode
when opaque types cross file boundaries and are used as method parameters or
case class fields:

| N opaque types | Compile time |
|---|---|
| 18 | 1.5 s |
| 20 | 3.1 s |
| 22 | 8.6 s |
| 24 | 31.0 s |
| 25 | 61.8 s |

The reporter's summary: "Each additional type roughly doubles compile time,
exponential (approximately O(2^N)) growth." Reported against 3.9.0-RC1 nightly
in May 2026. The workaround is one opaque type per file, which restores about
one second regardless of N.

This matters for eezo specifically because **opaque types are exactly what a web
framework uses for `UserId`, `RequestId`, `Email`, and friends**, and they
naturally live together in a `model.scala` and are naturally used as method
parameters and case class fields. That is precisely the shape that detonates. The
trigger requires `language.experimental.safe` rather than plain
`captureChecking`, which narrows the blast radius, but an open unfixed
exponential in the checker is the kind of thing that turns a three second reload
into a sixty second one with no warning.

### 5.5 Compile cost verdict

**Not disqualifying, with one open exponential to watch.** A 20 to 25% tax on
clean builds is real and will be felt in CI and cold starts, but the interactive
edit to reload loop, which is the constraint the ticket names, is unaffected.
The honest statement is that compile time is **not** the reason to decline
capture checking. The diagnostics are.

---

## 6. Constraints it imposes

### 6.1 Version pinning: not forced by the compiler, forced in practice

The compiler does not force a pin. Measured: capture checking works on stable
3.8.4 with only a language import, produces ordinary TASTy, and the resulting jar
is consumable from stable Scala with no flags.

Practice disagrees. The official enablement docs say to use nightly (§2.4), and
the flagship adopter obeys. TACIT's
[`build.sbt`](https://github.com/lampepfl/tacit/blob/main/build.sbt) pins to a
dated nightly:

```scala
val scala3Version = "3.10.0-RC1-bin-20260723-7c8ee3c-NIGHTLY"
ThisBuild / resolvers += Resolver.scalaNightlyRepository
```

That is a nightly from four days before this investigation, in the repository of
the group that *builds the feature*. The commented out block above it fetches the
latest nightly dynamically, which tells you how fast they expect to need to move.

**For eezo the practical constraint is: use stable and accept that you are behind
the documentation, the tutorials, and the bug fixes; or track nightly and accept
that a conference demo depends on a compiler build that did not exist last month.**
Neither is appealing for a framework whose selling point is that it is boring.

### 6.2 Interaction with `derives`, `inline`, macros, opaque types

Measured on 3.8.4, all in one capture checked file: `derives CanEqual`, an
`inline def` with `inline erasedValue` match, an `inline def` taking
`(using tx: Tx^)`, and an `opaque type` all compiled without complaint. No
special ceremony required. At the small scale eezo would exercise, these compose
fine.

The known interaction failures are narrower and real:

- [#25975](https://github.com/scala/scala3/issues/25975): opaque types plus safe
  mode, exponential compile time (§5.4). **Open.**
- [#26314](https://github.com/scala/scala3/issues/26314): "Opaque types extending
  a capability class cannot be widened". **Open.**
- [#26556](https://github.com/scala/scala3/issues/26556): "Flexible null type
  clashes with box adaptation", that is, `-Yexplicit-nulls` plus capture
  checking. **Open.** Relevant if eezo ever adopts explicit nulls.
- [#26612](https://github.com/scala/scala3/issues/26612) and
  [#26570](https://github.com/scala/scala3/issues/26570): **coverage
  instrumentation breaks capture tracking**, both open, both filed in July 2026.
  A framework that expects users to run `sbt coverage` should note this.
- [#26390](https://github.com/scala/scala3/issues/26390): `saferExceptions` plus
  `captureChecking` fails when adding exceptions to a signature. Less relevant
  since safer exceptions is being retired ([#26407](https://github.com/scala/scala3/issues/26407),
  which states checked exceptions are "not the way forward for Scala error
  handling").

### 6.3 Leakage into non consenting downstream: none, which cuts both ways

Established in §1 by measurement. A capture checked library:

- does **not** require downstream to enable anything;
- does **not** require `-experimental` downstream;
- presents `Tx^` to downstream as plain `Tx`;
- provides downstream **no capture checking at all**, silently.

For adoption risk this is the best possible answer. For the *value proposition*
it is close to the worst: eezo cannot ship the guarantee, only the opportunity.

### 6.4 Bug surface, quantified

Queried against the GitHub search API on 2026-07-27, label `area:experimental:cc`:

| | Count |
|---|---|
| Total issues ever filed | 223 |
| **Currently open** | **57** |
| Open and labelled `itype:bug` | 44 |
| Open and labelled `itype:crash` | **4** |

Four open crashes is the number to sit with. A sample of open titles from the
last three months, which conveys the character of the remaining work better than
the count does:

- "Capture leak by using existential GADT" ([#26399](https://github.com/scala/scala3/issues/26399))
- "Capture disappears during match refinement" ([#25955](https://github.com/scala/scala3/issues/25955))
- "Crash when using capability member in function params" ([#26180](https://github.com/scala/scala3/issues/26180))
- "`consume` is a silent no-op when the formal's capture set is a capture-set variable" ([#26584](https://github.com/scala/scala3/issues/26584))
- "Capture set of `this` gets widened to any under a nested scope" ([#25921](https://github.com/scala/scala3/issues/25921))
- "CC: Better errors for capture-polymorphic subtyping." ([#26261](https://github.com/scala/scala3/issues/26261))

Note the character: "capture leak", "capture disappears", "silent no-op". These
are **soundness holes in the guarantee itself**, not ergonomic complaints. A
framework that advertises a safety property should not build it on a checker with
four open crashes and several open "the check silently does nothing" reports.

---

## 7. Adoption

The honest summary is: **one flagship research project, on nightly, by the team
that builds the feature. No production library.**

**TACIT** ([lampepfl/tacit](https://github.com/lampepfl/tacit)) is the real
adopter and a genuinely impressive one. It is a safety harness for AI agents:
agent submitted Scala is compiled in capture checking safe mode so that agent
code "cannot forge access rights, cannot perform effects beyond its budget, and
cannot leak information from pure sub-computations". It won Best Paper at CAIS 26
([ACM](https://dl.acm.org/doi/10.1145/3786335.3813127),
[arXiv:2603.00991](https://arxiv.org/abs/2603.00991)). Its build pins a dated
nightly (§6.1) and it is at version `0.2.1-SNAPSHOT`.

TACIT is also the *right* use case, and noticing why is the most useful thing in
this section. TACIT's users are **AI agents**, not humans. An agent does not read
an error message on a projector; it receives a compiler diagnostic as text, fails
the compile, and retries. The set arithmetic in §4.4 costs an agent nothing.
**Capture checking's diagnostics are acceptable precisely for the audience that
does not have to read them**, and eezo's audience is the opposite of that. The
`safe.md` documentation says this out loud:

> It makes sense for agentic tooling to subject all compilations of agent-generated
> code to be compiled in _safe mode_ using this language import.

**Ox** is ruled out as an eezo dependency, and its technique is the instructive
part (§1, §8.2): Ox is the library that most looks like what eezo wants to be,
its author works at the company that publishes the main explanatory article on
capture checking, and Ox nonetheless ships `@implicitNotFound` on plain traits at
version 1.0.6. Capture checking is discussed in the Ox ecosystem as a thing that
*would* make the low level fork API safe, in the future, not as a thing that is
in the jar today.

**Gears**, also ruled out, is a research vehicle from the same lab and shares
TACIT's status: instructive, not evidence of production readiness.

The standard library itself was adjusted for capture checking in 3.8, and only
the *core traits* in `scala.caps` were stabilised, which the release notes are
explicit about. The checker was not stabilised.

I found no published, versioned, non snapshot library on Maven Central whose
public API is capture checked and which recommends its users enable capture
checking. If one exists I did not find it, and the absence of an obvious example
after targeted searching is itself evidence. **Confidence: medium-high**, since
proving a negative by search is inherently weak.

---

## 8. What the language offers instead, today

This is the realistic alternative, so it gets real measurement rather than a
list.

### 8.1 `using` plus `@implicitNotFound`: the baseline, and it is strong

`@implicitNotFound` takes a **constant** string. Measured gotcha:
`.stripMargin` is rejected, because the argument must be a compile time constant:

```
-- Error: Inf.scala:15:10
   |  @implicitNotFound requires constant expressions as a parameter
```

Use a bare triple quoted literal instead. It interpolates type parameters with
`${A}`. Here is eezo's realistic shipping diagnostic, written and measured on
3.8.4:

```scala
@implicitNotFound("""eezo: this code needs a database transaction, but none is in scope.

  Wrap the call in a transaction:

      db.transact:
        // ... your code here

  or declare that this method needs one:

      def myMethod(using Tx) = ...
""")
trait Tx:
  def exec(sql: String): Int
```

produces, verbatim:

```
-- [E172] Type Error: Inf2.scala:26:36
26 |  val bad: Int = insertUser("grace")
   |                                    ^
   |     eezo: this code needs a database transaction, but none is in scope.
   |
   |       Wrap the call in a transaction:
   |
   |           db.transact:
   |             // ... your code here
   |
   |       or declare that this method needs one:
   |
   |           def myMethod(using Tx) = ...
```

**Compare that to §4.4 and the ticket answers itself.** Multi line layout is
preserved. Code samples render. The framework's name is in the message. There is
no solver variable, no superscripted caret, no undefined jargon. Every word was
chosen by eezo rather than by the compiler.

Type parameter interpolation works too:

```scala
@implicitNotFound("eezo: no Repo[${A}] is configured. Did you call `Db.open[${A}](...)` first?")
trait Repo[A]
```

measured:

```
-- [E172] Type Error: Inf2.scala:27:38
27 |  val bad2: List[User] = findAll[User]
   |                                      ^
   |eezo: no Repo[User] is configured. Did you call `Db.open[User](...)` first?
```

`${A}` was substituted with `User`. What `@implicitNotFound` **cannot**
interpolate is the value name, the method name, or the enclosing scope, so the
message is per type, not per call site. That is the mechanism's one real limit
and it is minor.

### 8.2 The proof that this is sufficient: Ox

Ox's actual production source, verbatim from
[`ox/Ox.scala`](https://github.com/softwaremill/ox/blob/master/core/src/main/scala/ox/Ox.scala):

```scala
@implicitNotFound(
  "This operation must be run within a `supervised`, `supervisedError` or `unsupervised` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using OxUnsupervised` parameter list."
)
trait OxUnsupervised extends ResourceScope:
  private[ox] def herd: ThreadHerd
  private[ox] def supervisor: Supervisor[Nothing]
  private[ox] def parent: Option[OxUnsupervised]
  private[ox] def locals: ForkLocalMap
```

Note the shape, because eezo should copy it exactly: a **hierarchy** of
capability traits (`ResourceScope` <: `OxUnsupervised` <: `Ox` <: `OxError`),
each with its own `@implicitNotFound` naming the *specific* scope constructor
that provides it. Subtyping means a method requiring `ResourceScope` accepts an
`Ox`, and the error message a user sees is the one for the *weakest* capability
they actually failed to provide. For eezo that maps to
`DB` <: `Tx` or `Connection` <: `Tx`, with distinct messages.

### 8.3 `erased` parameters

Available under `-language:experimental.erasedDefinitions` (still experimental).
Measured: `def erasedDemo(x: Int)(using erased ev: InTx): Int` compiles and the
evidence has zero runtime representation. `@implicitNotFound` on the erased
type works normally.

Useful for **pure proofs** ("this code is inside a transaction") where nothing is
actually called on the evidence. Not useful for eezo's `using Tx`, because eezo
needs to *call* `tx.exec`, and an erased value cannot be used.

### 8.4 `NotGiven`

`scala.util.NotGiven` is stable and expresses the negative constraint. Measured:

```scala
def mustNotBeInTx(x: Int)(using ev: NotGiven[InTx]): Int = x
```

with a given `InTx` in scope yields:

```
-- [E172] Type Error: Alt.scala:23:26
   |No given instance of type scala.util.NotGiven[InTx] was found for parameter ev of method mustNotBeInTx
```

Note that the message is **not** customisable through `@implicitNotFound` on
`InTx`, because the missing given is `NotGiven[InTx]`, not `InTx`. This is a real
sharp edge: negative constraints have bad error messages and there is no clean
fix. Use sparingly, for things like "do not open a transaction inside a
transaction".

### 8.5 Opaque and phantom types

Stable, zero cost, and the right tool for *tagging* (`opaque type UserId = Long`)
rather than for capability passing. Worth noting only for the §5.4 interaction:
opaque types are the thing that detonates capture checking's safe mode.

### 8.6 Summary comparison

| | Capture checking | `using` + `@implicitNotFound` |
|---|---|---|
| Stability | experimental, no SIP, no date | **stable since 3.0** |
| Missing capability message | compiler's, fixed | **fully authored by eezo** |
| Multi line, code samples in message | no | **yes** |
| Detects capability escaping scope | **yes** | no |
| Detects aliasing / sharing | yes, via separation checking | no |
| Clean build cost | **+20 to 25%** | zero |
| Incremental reload cost | negligible | zero |
| Forces downstream opt in | no | no |
| Gives downstream the guarantee | **no** | not applicable |
| Version pin | nightly in practice | none |
| Open compiler bugs in the mechanism | 57, of which 4 crashes | effectively none |
| Production precedent | TACIT (nightly, snapshot) | **Ox 1.0.6, and every Scala library** |

---

## 9. Recommendation

**Encode `using DB` and `using Tx` with plain context parameters plus a
hierarchy of `@implicitNotFound` annotated capability traits, copying Ox's shape.
Do not enable capture checking in eezo, in eezo's tutorial, or in the Scala Days
demo. Design the API so capture checking can be added later without a breaking
change, and say so in the talk.**

Reasoning, in order of weight:

1. **Capture checking does not improve the moment eezo is built around.** A
   missing capability is a given resolution failure and prints an `E172` whether
   or not capture checking is on (§1, §4.1). The thing eezo wants on the
   projector is fully within reach of `@implicitNotFound`, and the version eezo
   can author (§8.1) is dramatically better than anything the compiler generates,
   because it can name the framework, show the fix, and span multiple lines.
   Capture checking's contribution to that specific error is one caret character
   that the audience will find distracting.

2. **The diagnostics eezo would additionally get are bimodally bad.** The good
   half is genuinely good: "Capability `tx` outlives its scope" is a great first
   line, and the `consume` message is excellent. The bad half prints solver
   variables (`'s1`), superscripted carets (`^²`), and five row set arithmetic
   tables (§4.4), and eezo cannot choose which half a user hits. Worse, the
   common realistic case, where the capability passes through an intermediate
   local, **blames the wrong identifier** (§4.3). A framework cannot ship a
   diagnostic story it does not control.

3. **The precedent points the other way.** Ox is the closest existing thing to
   eezo, built by people deeply involved in the capture checking conversation,
   and at 1.0.6 it uses `@implicitNotFound` (§7, §8.2). The one serious adopter,
   TACIT, is on a dated nightly at `0.2.1-SNAPSHOT` and its users are AI agents
   who never read the error messages (§7). That distinction is the single most
   clarifying fact in this investigation: capture checking's diagnostics are
   currently good enough for machines and not for a projector.

4. **Adopting it would buy a guarantee eezo cannot actually deliver.** Because
   capture checking is per compilation unit and erases downstream (§1, §6.3), an
   eezo user gets no checking unless they add the import themselves. eezo would
   be paying 20 to 25% of clean build time and taking on 57 open compiler issues
   in exchange for a property that is off by default for every user.

5. **Compile time is not the reason, and eezo should not claim it is.** The
   measured incremental reload cost is zero (§5.3). The clean build tax is 20 to
   25% (§5.2), which is real but survivable. Only the open exponential in
   [#25975](https://github.com/scala/scala3/issues/25975) is genuinely alarming,
   and it needs safe mode plus opaque types crossing files to trigger. If someone
   argues for capture checking, do not rebut them on speed. Rebut them on §4.

### The migration story, which is unusually good

This recommendation is cheap to reverse, which is why it is the right one now.

Because capture checking is **non viral** (§2.3) and **erases for downstream
consumers** (§6.3), adding it later is not a breaking change. Concretely, eezo
should:

1. **Ship capability traits, not type aliases.** `trait Tx`, `trait DB`, in a
   hierarchy, each with `@implicitNotFound`. A trait can later be made to extend
   `caps.SharedCapability` or `caps.ExclusiveCapability` without changing its
   name or its users' code.
2. **Take capabilities as `using` parameters, never as thread locals, globals, or
   dynamic scope.** This is the actual capability discipline, and it is what
   makes the type system upgrade possible later. It is also what makes eezo's
   claim ("capabilities visible in signatures") true today, without capture
   checking.
3. **Use context functions for scope constructors:**
   `def transact[A](body: Tx ?=> A): A`. This is exactly the shape that becomes
   `Tx^ ?=> A` under capture checking, and it works unchanged today.
4. **Keep an internal `research/harnesses` build that compiles a sample eezo app
   with `-language:experimental.captureChecking` in CI, allowed to fail.** It
   costs nothing, and it converts "should we adopt capture checking" from a
   guess into a standing measurement. When that harness goes green and stays
   green across three consecutive stable releases, revisit.
5. **Decide `SharedCapability` versus `ExclusiveCapability` for `Tx` on paper
   now**, even though nothing enforces it. A transaction is exclusive. Writing
   that down now means step 1 is a one line change later.

### What to say on stage

Do not hide this. The stronger talk is the honest one: "eezo puts capabilities in
signatures with `using`, which works in Scala today with zero flags. Scala also
has an experimental capture checker that can prove your transaction does not
escape its scope. Here is what it catches, here is why eezo does not require it
yet, and here is why eezo's API is shaped so you can turn it on the day it is
ready." That earns more credit with a Scala Days audience than claiming a
research feature is production ready, and it inoculates against the question
somebody will definitely ask.

### Confidence

**Very high (95%) that capture checking does not improve the missing capability
error.** Measured directly, both with and without capture checking, on the
current stable release, in this investigation. This is not an inference.

**Very high (92%) that `@implicitNotFound` produces a better projector message
than any capture checking output.** Both messages in §4.4 and §8.1 were produced
locally and can be put side by side on one slide. The comparison is not close.

**High (88%) on the diagnostics assessment overall.** Based on the verbatim
`.check` corpus, which is the compiler's own definition of correct output, plus
locally reproduced messages, plus an independent confirmation from an experienced
user with a conceding reply from the language designer (§4.6). The residual
uncertainty is that message quality is improving, and issues like
[#26261](https://github.com/scala/scala3/issues/26261) ("Better errors for
capture-polymorphic subtyping") show it is being worked on.

**High (85%) on the compile cost numbers.** Four project sizes, three repeats at
the reference size, sub 1.5% variance, using the compiler's own phase profiler.
The caveat is that the benchmark is synthetic and uniform; real eezo code with
heavy inline and macro use might behave differently, and the `cc` phase's
interaction with `inline` is unmeasured here.

**Medium-high (75%) that no production library ships a capture checked public
API.** Established by targeted search, which cannot prove a negative. If someone
produces a counterexample, the adoption argument weakens but arguments 1, 2 and 4
are untouched.

**Medium (65%) on the timing of the "revisit later" trigger.** The nightly docs
say stabilisation is expected "soon" and 3.9 LTS explicitly will not deliver it.
3.10 or 3.11 is a reasonable guess and it is a guess. The mitigation is that the
recommendation does not depend on the guess: the CI harness in step 4 measures
readiness rather than predicting it.

**Lower confidence (55%) on ruling out `erased` capability proofs as a
complement.** They are not usable for `Tx` itself (§8.3), but a pattern where
eezo passes a real `using tx: Tx` *and* an `erased` proof carrying phantom
information (transaction isolation level, read only versus read write) was not
explored and might be worth a look for a separate ticket.

---

## 10. What other tickets need to know

- **The demo ticket.** eezo's signature compile error should be written by hand
  as an `@implicitNotFound` string and treated as a designed artefact, iterated
  on like copy. It is the highest leverage single string in the project. Budget
  real time for it and test it on a projector at actual font size.
- **Any ticket on the module or build layout.** Keep capability traits in their
  own small module with no dependencies, so the future capture checking
  experiment is confined to one compilation unit.
- **Any ticket touching opaque types for IDs.** Note
  [#25975](https://github.com/scala/scala3/issues/25975) before adopting safe
  mode, and prefer one opaque type per file if that ever changes.
- **The Scala version ticket.** Nothing here forces a pin. eezo can target stable
  3.8.4 today and 3.9 LTS when it lands. That is a better story than nightly and
  it is available precisely because capture checking is not being adopted.
- **The deploy ticket (`research/deploy-target.md` §5).** That document flags
  `sbt stage` incremental time as the largest unmeasured term in the sixty second
  budget. This investigation partially answers it: incremental single file
  recompilation on a warm build server is roughly half a second for an 80 file
  project, and capture checking does not change it. That is a data point for the
  same budget, though a real eezo app with macros will be slower.

---

## Appendix A: reproduction

All experiments in a scratch directory outside the eezo repo. Toolchain:
`scala-cli` on Temurin JDK 26, Scala 3.8.4.

Missing capability, capture checking on:

```scala
//> using scala 3.8.4
import language.experimental.captureChecking
import caps.*
trait Tx extends SharedCapability:
  def exec(sql: String): Int
def transact[A](body: Tx^ ?=> A): A = ???
def insertUser(name: String)(using tx: Tx^): Int = tx.exec(name)
object App:
  val ok: Int = transact { insertUser("ada") }
  val bad: Int = insertUser("grace")
```

Scope escape (requires `ExclusiveCapability` and no permissive result annotation):

```scala
//> using scala 3.8.4
import language.experimental.captureChecking
import caps.*
trait Tx extends ExclusiveCapability:
  def exec(sql: String): Int
def transact[A](body: Tx^ ?=> A): A = ???
def insertUser(name: String)(using tx: Tx^): Int = tx.exec(name)
object App:
  val leaked1 = transact { (tx: Tx^) ?=> () => insertUser("hopper") }
```

Downstream leakage test:

```bash
scala-cli --power package lib --library -o lib.jar --scala 3.8.4 \
  -O -language:experimental.captureChecking
scala-cli compile app --scala 3.8.4 --jar lib.jar   # app has NO cc import
```

Timing:

```bash
scala-cli compile <dir> --server=false -O -Yprofile-enabled 2>&1 \
 | grep -E '^[A-Za-z].*,run ns = ' \
 | awk -F',run ns = ' '{split($2,a,","); t+=a[1]/1e6} END {print t" ms"}'
```

---

## Appendix B: sources

Primary, Scala project:
- [scala/scala3 releases API](https://api.github.com/repos/scala/scala3/releases) (version landscape, 2026-07-27)
- [scala/scala3](https://github.com/scala/scala3) at `a036a3a7`, specifically
  [`project/Versions.scala`](https://github.com/scala/scala3/blob/main/project/Versions.scala),
  [`compiler/src/dotty/tools/dotc/config/Feature.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/config/Feature.scala),
  [`compiler/src/dotty/tools/dotc/config/ScalaSettings.scala`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala),
  [`library/src/scala/caps/package.scala`](https://github.com/scala/scala3/blob/main/library/src/scala/caps/package.scala)
- Capture checking reference docs on `main`:
  [`overview.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/overview.md),
  [`basics.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/basics.md),
  [`how-to-use.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/how-to-use.md),
  [`safe.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/safe.md),
  [`separation-checking.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/separation-checking.md),
  [`mutability.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/mutability.md),
  [`polymorphism.md`](https://github.com/scala/scala3/blob/main/docs/_docs/reference/experimental/capture-checking/polymorphism.md)
- Verbatim diagnostics, `tests/neg-custom-args/captures/`:
  [`reference-cc.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/reference-cc.check),
  [`sep-use2.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/sep-use2.check),
  [`sep-compose.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/sep-compose.check),
  [`scope-extrusions.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/scope-extrusions.check),
  [`boxed-consume.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/boxed-consume.check),
  [`existential-mapping.check`](https://github.com/scala/scala3/blob/main/tests/neg-custom-args/captures/existential-mapping.check)
- [Scala 3.8 release notes](https://www.scala-lang.org/news/3.8/)
- [docs.scala-lang.org capture checking reference](https://docs.scala-lang.org/scala3/reference/experimental/cc.html)

Primary, issue tracker (all verified open on 2026-07-27 unless noted):
- [#25975](https://github.com/scala/scala3/issues/25975) exponential compile time, safe mode plus opaque types
- [#26314](https://github.com/scala/scala3/issues/26314) opaque types extending a capability class cannot be widened
- [#26556](https://github.com/scala/scala3/issues/26556) flexible null type clashes with box adaptation
- [#26612](https://github.com/scala/scala3/issues/26612), [#26570](https://github.com/scala/scala3/issues/26570) coverage instrumentation breaks capture tracking
- [#26584](https://github.com/scala/scala3/issues/26584) `consume` silent no-op
- [#26399](https://github.com/scala/scala3/issues/26399) capture leak via existential GADT
- [#25955](https://github.com/scala/scala3/issues/25955) capture disappears during match refinement
- [#25921](https://github.com/scala/scala3/issues/25921) capture set of `this` widened to any
- [#26180](https://github.com/scala/scala3/issues/26180) crash on capability member in function params
- [#26261](https://github.com/scala/scala3/issues/26261) better errors for capture-polymorphic subtyping
- [#26390](https://github.com/scala/scala3/issues/26390) saferExceptions plus captureChecking
- [#26407](https://github.com/scala/scala3/issues/26407) retire safer exceptions

Primary, third party projects:
- [softwaremill/ox `core/src/main/scala/ox/Ox.scala`](https://github.com/softwaremill/ox/blob/master/core/src/main/scala/ox/Ox.scala) (verbatim source; also compiled against `com.softwaremill.ox::core:1.0.0`)
- [lampepfl/tacit](https://github.com/lampepfl/tacit), [`build.sbt`](https://github.com/lampepfl/tacit/blob/main/build.sbt), README
- [Securing Agents With Tracked Capabilities](https://dl.acm.org/doi/10.1145/3786335.3813127), [arXiv:2603.00991](https://arxiv.org/abs/2603.00991)

Secondary, labelled as such inline:
- [users.scala-lang.org: Capture checking being confusing again](https://users.scala-lang.org/t/capture-checking-being-confusing-again/12083) (official Scala forum; contains a verbatim reply from Odersky, so weighted higher than an ordinary forum post)
- [What's in the Box: Ergonomic and Expressive Capture Tracking over Generic Data Structures](https://arxiv.org/pdf/2509.07609) (reach capabilities; cited only for the claim that adoption needs "minimal changes", which is an author claim about their own system)
- [SoftwareMill: Understanding Capture Checking in Scala](https://softwaremill.com/understanding-capture-checking-in-scala/)

Measured locally, not cited from anywhere: all timing tables in §5, all error
messages in §1, §2.5, §3.2, §4.1, §4.3, §8.1, §8.3, §8.4, and the downstream
leakage result in §1 and §6.3.
