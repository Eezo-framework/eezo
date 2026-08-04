package eezo.cli

/** The `eezo` command: `new`, `dev`, `routes`, `g`, `db` and `deploy`.
  *
  * It depends on every other module because it drives all of them. The implementation lands in #85
  * to #88. This marker exists only so the module has something to compile; delete it when the first
  * real type arrives.
  */
object CliModule
