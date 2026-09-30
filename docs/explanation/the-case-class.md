<!-- draft -->
# The case class is the source of truth

Why one declaration derives a table, a form and seven routes, what each derivation knows, and what none of them is allowed to know.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- the claim, and the 30 minute benchmark it was designed against
- `Table`, `Form`, `Resource`: what each derives from the fields and what each refuses to know
- how Form and Resource relate: the three shapes (Form alone, both, both minus an action)
- why they are not one derivation
- `Field`: one type, one input, one column; what an absent submission means
- what derivation costs at compile time, from the measurements
- what is deliberately not derived: authorization, presentation beyond the envelope, relations

## Where the material is

- `CONTEXT.md`, Derivation
- `research/derivation-design.md`
- `modules/http/src/main/scala/io/eezo/http/Form.scala`, `Resource.scala`, `Field.scala`; `modules/db/.../Table.scala`
