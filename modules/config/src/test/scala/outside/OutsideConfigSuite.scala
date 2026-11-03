package outside

import com.typesafe.config.ConfigFactory

import io.eezo.config.Config
import io.eezo.core.Redacted

/** An application's case class lives outside eezo's packages, so what `derives Config` expands to
  * has to compile there too, where nothing package private to `config` can be named.
  */
class OutsideConfigSuite extends munit.FunSuite {

  test("a case class outside eezo's packages derives Config, with a section and a default") {
    val file = ConfigFactory.parseString("keys { apiKey = ${?API_KEY} }")
    assertEquals(
      Config[OutsideConfigSuite.App].read(file, Map("API_KEY" -> "k")),
      Right(OutsideConfigSuite.App(OutsideConfigSuite.Keys(Redacted("k"))))
    )
    assertEquals(
      Config.root[OutsideConfigSuite.App].config,
      Config[OutsideConfigSuite.App]
    )
  }
}

object OutsideConfigSuite {
  final case class Keys(apiKey: Redacted, retries: Int = 3) derives Config

  final case class App(keys: Keys) derives Config
}
