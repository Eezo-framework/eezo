#!/bin/bash
# edits.sh <edit-name>
# Applies one named source edit in place. Run from the generated project root
# (sbt's `edit` command passes the project baseDirectory as cwd).
#
# Every edit must leave the project COMPILING. The 2026-07-26 run of this rig
# lost two of its five experiments because `C_rename_field` renamed User.name
# without touching the six files that read `u.name`, and `D_separate_given`
# summoned a given it had not imported. Both runs ended in `Compilation failed`,
# so their invalidation counts measured a failed compile, not an invalidation.
set -euo pipefail

cd "$(dirname "$0")"
M=src/main/scala/model
A=src/main/scala/app
U=$M/User.scala
T=$M/Typeclasses.scala

# BSD/GNU sed portable in place edit
sedi() { sed -i.bak "$@" && rm -f "${!#}.bak" 2>/dev/null || true; }

case "$1" in

  # Restore the pristine tree. rsync -c compares by checksum, so files whose
  # content never changed keep their original mtime and Zinc sees them as
  # untouched whatever stamping strategy it is using.
  reset)
    rsync -rc --delete src.orig/ src/
    ;;

  # ---- pure filesystem metadata change, no content change at all -----------
  touch_mtime)
    touch "$U"
    ;;

  # ---- content change with no API change ----------------------------------
  comment)
    echo "// nonce $(date +%s%N)" >> "$U"
    ;;

  # a change confined to a method body of the case class itself
  method_body)
    sed -i.bak 's|def label: String = "u:" + id|def label: String = "user:" + id|' "$U"; rm -f "$U.bak"
    ;;

  # ---- API changes to the model case class --------------------------------
  add_field)
    sed -i.bak 's|active: Boolean, age: Int)|active: Boolean, age: Int, nickname: String)|' "$U"; rm -f "$U.bak"
    ;;

  # rename a field that NOTHING downstream references by name
  rename_unused)
    sed -i.bak 's|active: Boolean|enabled: Boolean|' "$U"; rm -f "$U.bak"
    ;;

  # rename a field that six downstream files reference by name, and fix them
  # in the same edit, exactly as a user's editor rename refactor would
  rename_used)
    sed -i.bak 's|name: String|fullName: String|' "$U"; rm -f "$U.bak"
    for f in $A/Named*.scala; do
      sed -i.bak 's|u\.name|u.fullName|' "$f"; rm -f "$f.bak"
    done
    ;;

  # change a field's TYPE, keeping its name
  field_type)
    sed -i.bak 's|age: Int)|age: Long)|' "$U"; rm -f "$U.bak"
    ;;

  # ---- change the derives clause ------------------------------------------
  add_derive)
    sed -i.bak 's|derives Codec, Table, Form, Resource|derives Codec, Table, Form, Resource, Show|' "$U"; rm -f "$U.bak"
    ;;

  drop_derive)
    sed -i.bak 's|derives Codec, Table, Form, Resource|derives Codec, Table, Form|' "$U"
    rm -f "$U.bak"
    for f in $A/Consumer*.scala; do
      sed -i.bak 's| + summon\[Resource\[User\]\].routes.mkString||' "$f"; rm -f "$f.bak"
    done
    ;;

  # ---- the "given in a separate file from the case class" shape ------------
  order_add_field)
    sed -i.bak 's|discount: Int, note: String)|discount: Int, note: String, ref: String)|' "$U"; rm -f "$U.bak"
    ;;

  # ---- change the typeclass itself ----------------------------------------
  # body of an INLINE def that every derivation site expands
  inline_body)
    sed -i.bak 's|\.map((v, c) => c\.asInstanceOf\[Codec\[Any\]\]\.encode(v))\.mkString(",")|.map((v, c) => c.asInstanceOf[Codec[Any]].encode(v)).mkString(";")|' "$T"; rm -f "$T.bak"
    ;;

  # body of a NON inline given in the same object
  noninline_body)
    sed -i.bak 's|given Codec\[Boolean\] with { def encode(a: Boolean) = a.toString }|given Codec[Boolean] with { def encode(a: Boolean) = if a then "Y" else "N" }|' "$T"; rm -f "$T.bak"
    ;;

  # ---- controls ------------------------------------------------------------
  unrelated_body)
    sed -i.bak 's|def twice: Int = value \* 2|def twice: Int = value + value|' "$A/Unrelated0.scala"; rm -f "$A/Unrelated0.scala.bak"
    ;;

  consumer_body)
    echo "  def extra: Int = $(date +%N)" >> "$A/Consumer0.scala"
    ;;

  *)
    echo "unknown edit: $1" >&2; exit 2
    ;;
esac

echo "edit applied: $1" >&2
