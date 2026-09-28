package elsewhere

/** Written from a package outside `io.eezo` on purpose: that is where an application lives, and
  * `import io.eezo.http.*` is what one writes, so this is the one place the size helper staying the
  * framework's own can be seen failing to leak.
  */
class MiBVisibilitySuite extends munit.FunSuite {

  test("an application importing io.eezo.http.* cannot write 1.MiB") {
    // Settled when this file compiles, and the incremental build never recompiles it for a change
    // to a name that only a string mentions: after editing the modifier, trust a clean build only.
    val errors = compileErrors("import io.eezo.http.*\nval size: Long = 1.MiB")
    assert(
      clue(errors).contains("value MiB is not a member of Int"),
      "1.MiB compiled outside io.eezo"
    )
  }
}
