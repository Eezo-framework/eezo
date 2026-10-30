package outside

import io.eezo.core.{Config, Redacted}

/** An application's case class lives outside eezo's packages, so what `derives Config` expands to
  * has to compile there too, where nothing package private to `core` can be named.
  */
class OutsideConfigSuite extends munit.FunSuite {

  test("a case class outside eezo's packages derives Config and reads with its defaults") {
    assertEquals(
      Config[OutsideConfigSuite.Keys].read(Map("API_KEY" -> "k")),
      Right(OutsideConfigSuite.Keys(Redacted("k")))
    )
  }
}

object OutsideConfigSuite {
  final case class Keys(apiKey: Redacted, retries: Int = 3) derives Config
}
