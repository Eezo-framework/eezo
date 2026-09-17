package io.eezo

/** Pins what `RouteGeneratorSuite` in `modules/sbt-plugin` cannot check: that the emitted
  * `guardFor` helper's error arm still folds to a value `compiletime.error` accepts.
  *
  * `RouteGeneratorSuite` only string matches the generated source for the literal text
  * `"scala.compiletime.error("` and the `def` signature; nothing there compiles the helper. A
  * change that stops the message from folding to a constant, such as computing `name` from
  * something the compiler cannot see through at the call site, would pass every one of those
  * assertions while turning the application side failure into "A literal string is expected as an
  * argument to compiletime.error", a message that points at generated code the user cannot edit and
  * never names the model. `compileErrors` is the only way to see that failure for real, and it
  * takes a literal source, so the body below is copied from `RouteGenerator.guardFor` rather than
  * produced by calling it.
  *
  * This lives in the umbrella rather than in `modules/sbt-plugin` itself because the helper's body
  * only names `io.eezo.http.Guarded`, and `sbt-plugin` is a cross built sbt plugin project that
  * never depends on `http`. `PasswordStorageSuite` sits beside this test for the same reason: the
  * umbrella is the one place a macro assertion about generated application code can compile.
  */
class GeneratedGuardForSuite extends munit.FunSuite {

  test("the emitted guardFor names the offending type when nothing guards it") {
    val failure = compileErrors(
      """
      object NoGuardApp {
        private inline def guardFor[A](inline name: String): io.eezo.http.Guarded[A] =
          scala.compiletime.summonFrom {
            case g: io.eezo.http.Guarded[A] => g
            case _                          =>
              scala.compiletime.error(
                "no Guarded given for " + name + ", and this application has eezo-auth on its " +
                  "classpath, so every mounted route has to say who may reach it. In its " +
                  "companion, one of:\n" +
                  "  given io.eezo.http.Guarded[T] = <yourGuard>.required\n" +
                  "  given io.eezo.http.Guarded[T] = io.eezo.http.Guarded.public"
              )
          }

        class NoGuard
        guardFor[NoGuard]("app.NoGuard")
      }
      """
    )
    assert(clue(failure).contains("no Guarded given for"), failure)
    assert(clue(failure).contains("app.NoGuard"), failure)
  }
}
