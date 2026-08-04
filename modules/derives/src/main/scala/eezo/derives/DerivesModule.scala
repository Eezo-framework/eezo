package eezo.derives

/** Derivation: the `derives` mechanism and its tier order, then `DbCodec`, `Table`, `Form` and
  * `Resource` on top of it.
  *
  * It sits above `db`, `http` and `live` because those are what it derives into. The contract
  * lands in #39 and the implementation in #61 and #68 to #71. This marker exists only so the
  * module has something to compile; delete it when the first real type arrives.
  */
object DerivesModule
