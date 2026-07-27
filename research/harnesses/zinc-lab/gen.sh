#!/bin/bash
# gen.sh <outdir> <n_typeclass_consumers> <n_member_consumers> <n_unrelated> <n_derives>
#
# Generates a self-contained sbt project that reproduces eezo's shape:
#   model/User.scala        one case class with a `derives` clause
#   model/Typeclasses.scala the Mirror based typeclasses it derives
#   model/OrderInstances.scala  a given defined in a DIFFERENT file from its case class
#   app/Consumer*.scala     files that `summon` the derived instances (typeclass consumers)
#   app/Named*.scala        files that reference one field by name (member reference consumers)
#   app/Unrelated*.scala    files that touch the model not at all (control)
#
# n_derives selects how many typeclasses User derives: 1 (Codec) or 4 (all).
set -euo pipefail

OUT=${1:?outdir}
NCONS=${2:-6}
NNAMED=${3:-6}
NUNREL=${4:-8}
NDERIVES=${5:-4}
SCALA=${SCALA_VERSION:-3.8.4}
SBTV=${SBT_VERSION:-1.12.1}

rm -rf "$OUT"
mkdir -p "$OUT/project" "$OUT/src/main/scala/model" "$OUT/src/main/scala/app"

echo "sbt.version=$SBTV" > "$OUT/project/build.properties"

cat > "$OUT/build.sbt" <<EOF
import scala.sys.process.*

ThisBuild / scalaVersion := "$SCALA"
ThisBuild / logLevel := (if (sys.props.get("lab.debug").contains("true")) Level.Debug else Level.Info)
// Defeat Zinc's "if more than X of the build is invalidated, just recompile
// everything" heuristic, so that the reported source count is the TRUE
// transitive invalidation set and not a rounding up.
ThisBuild / incOptions := (ThisBuild / incOptions).value.withRecompileAllFraction(1.0)
ThisBuild / turbo := false

lazy val root = (project in file("."))

// Apply a named edit to the sources, from inside the running sbt session, so
// that the compiler and the JVM stay warm across the whole experiment matrix.
commands += Command.args("edit", "<name>") { (st, args) =>
  val base = Project.extract(st).get(baseDirectory)
  val rc = Seq("bash", (base / "edits.sh").getAbsolutePath, args.mkString(" ")).!
  if (rc != 0) sys.error("edit failed: " + args.mkString(" "))
  st
}

// Target for \`sbt "~watched"\`. Printing the marker from a task BODY means it
// runs strictly after Compile/compile has finished, so the marker's timestamp
// is a trustworthy "rebuild complete" instant. Counting sbt's own "Monitoring
// source files" lines is not: a multi file edit makes the watcher fire more
// than once, and a transiently broken intermediate state produces a monitor
// line for a FAILED build that looks like a very fast success.
val labWatch = taskKey[Unit]("compile, then print a completion marker")
labWatch := {
  val r = (Compile / compile).result.value
  val status = r match {
    case Inc(_)   => "FAILED"
    case Value(_) => "OK"
  }
  println("===WATCH-DONE\t" + System.currentTimeMillis() + "\t" + status)
}

// Wall clock one Compile/compile, including Zinc's up to date check.
commands += Command.args("timed", "<label>") { (st, args) =>
  val label = args.mkString(" ")
  println(s"===LAB-BEGIN\t\$label")
  val t0 = System.nanoTime()
  val (st1, _) = Project.extract(st).runTask(Compile / compile, st)
  val us = (System.nanoTime() - t0) / 1000L
  println("===LAB-TIMED\t" + label + "\t" + us)
  st1
}
EOF

# ---------------------------------------------------------------- typeclasses
cat > "$OUT/src/main/scala/model/Typeclasses.scala" <<'EOF'
package model

import scala.deriving.Mirror
import scala.compiletime.{erasedValue, summonInline, constValue}

// Five independent Mirror based derivable typeclasses, standing in for eezo's
// DbCodec / Table / Form / Resource. Every `derived` is an `inline def`, which
// is how Magnum's DbCodec and every other Scala 3 derivation of this shape
// works.

trait Codec[A]:
  def encode(a: A): String
object Codec:
  given Codec[String] with { def encode(a: String) = a }
  given Codec[Int] with { def encode(a: Int) = a.toString }
  given Codec[Long] with { def encode(a: Long) = a.toString }
  given Codec[Boolean] with { def encode(a: Boolean) = a.toString }

  inline def summonAll[T <: Tuple]: List[Codec[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[Codec[t]] :: summonAll[ts]

  inline def derived[A](using m: Mirror.ProductOf[A]): Codec[A] =
    val elems = summonAll[m.MirroredElemTypes]
    new Codec[A]:
      def encode(a: A) =
        a.asInstanceOf[Product].productIterator.zip(elems.iterator)
          .map((v, c) => c.asInstanceOf[Codec[Any]].encode(v)).mkString(",")

trait Table[A]:
  def columns: List[String]
object Table:
  inline def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => constValue[t].asInstanceOf[String] :: labels[ts]
  inline def derived[A](using m: Mirror.ProductOf[A]): Table[A] =
    val cols = labels[m.MirroredElemLabels]
    new Table[A] { def columns = cols }

trait Form[A]:
  def html: String
object Form:
  inline def derived[A](using m: Mirror.ProductOf[A]): Form[A] =
    val cols = Table.labels[m.MirroredElemLabels]
    new Form[A] { def html = cols.map(c => s"<input name='$c'>").mkString }

trait Resource[A]:
  def routes: List[String]
object Resource:
  inline def derived[A](using m: Mirror.ProductOf[A]): Resource[A] =
    val cols = Table.labels[m.MirroredElemLabels]
    new Resource[A] { def routes = List("GET /", "POST /") ++ cols.map(c => s"GET /:id/$c") }

trait Show[A]:
  def show(a: A): String
object Show:
  inline def derived[A](using m: Mirror.ProductOf[A]): Show[A] =
    val cols = Table.labels[m.MirroredElemLabels]
    new Show[A] { def show(a: A) = cols.mkString("<", ",", ">") }
EOF

# ---------------------------------------------------------------------- model
if [ "$NDERIVES" = "1" ]; then
  DERIVES="derives Codec"
else
  DERIVES="derives Codec, Table, Form, Resource"
fi

cat > "$OUT/src/main/scala/model/User.scala" <<EOF
package model

case class User(id: Int, name: String, email: String, active: Boolean, age: Int) $DERIVES:
  def label: String = "u:" + id

case class Order(id: Int, sku: String, qty: Int, discount: Int, note: String)
EOF

cat > "$OUT/src/main/scala/model/OrderInstances.scala" <<'EOF'
package model

// The "derived instance lives in a different file from the case class" shape.
given orderCodec: Codec[Order] = Codec.derived
given orderTable: Table[Order] = Table.derived
EOF

# ------------------------------------------------------------------ consumers
if [ "$NDERIVES" = "1" ]; then
  SUMMONS='summon[Codec[User]].encode(u)'
else
  SUMMONS='summon[Codec[User]].encode(u) + summon[Table[User]].columns.mkString + summon[Form[User]].html + summon[Resource[User]].routes.mkString'
fi

i=0
while [ "$i" -lt "$NCONS" ]; do
  cat > "$OUT/src/main/scala/app/Consumer$i.scala" <<EOF
package app
import model.*
object Consumer$i:
  def run(u: User): String =
    $SUMMONS
EOF
  i=$((i+1))
done

i=0
while [ "$i" -lt "$NNAMED" ]; do
  cat > "$OUT/src/main/scala/app/Named$i.scala" <<EOF
package app
import model.User
object Named$i:
  def name(u: User): String = u.name
EOF
  i=$((i+1))
done

i=0
while [ "$i" -lt "$NUNREL" ]; do
  cat > "$OUT/src/main/scala/app/Unrelated$i.scala" <<EOF
package app
object Unrelated$i:
  def value: Int = $i
  def twice: Int = value * 2
EOF
  i=$((i+1))
done

cat > "$OUT/src/main/scala/app/OrderUse.scala" <<'EOF'
package app
import model.*
import model.given
object OrderUse:
  def run(o: Order): String =
    summon[Codec[Order]].encode(o) + summon[Table[Order]].columns.mkString(",")
EOF

cp "$(dirname "$0")/edits.sh" "$OUT/edits.sh"
chmod +x "$OUT/edits.sh"

# Pristine copy, so that `edits.sh reset` restores an exactly known state.
cp -R "$OUT/src" "$OUT/src.orig"

echo "generated $OUT: $(find "$OUT/src" -name '*.scala' | wc -l | tr -d ' ') sources, derives=$NDERIVES scala=$SCALA"
