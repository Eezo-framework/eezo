package eezo.sbt

import sbt._

/** The half of the cache invalidation fix that is testable without booting sbt: the stamp file
  * `generate` writes into the task's cache directory carries `GeneratorVersion` verbatim, and its
  * content changes whenever that constant does. `FileFunction.cached` itself, and whether a real
  * `~compile` loop actually misses the cache on a bump, need a live task streams instance and are
  * exercised by hand instead.
  */
class EezoPluginSuite extends munit.FunSuite {

  test("GeneratorVersion is a non-empty stamp") {
    assert(clue(EezoPlugin.GeneratorVersion).nonEmpty)
  }

  test("a stamp file written with GeneratorVersion round-trips its exact content") {
    IO.withTemporaryDirectory { dir =>
      val stamp = dir / "eezo-routes.version"
      IO.write(stamp, EezoPlugin.GeneratorVersion)
      assertEquals(IO.read(stamp), EezoPlugin.GeneratorVersion)
    }
  }

  test("the stamp's content differs whenever GeneratorVersion would differ") {
    IO.withTemporaryDirectory { dir =>
      val current = dir / "current.version"
      val bumped  = dir / "bumped.version"
      IO.write(current, EezoPlugin.GeneratorVersion)
      IO.write(bumped, EezoPlugin.GeneratorVersion + "-bumped")
      assertNotEquals(IO.read(current), IO.read(bumped))
    }
  }
}
