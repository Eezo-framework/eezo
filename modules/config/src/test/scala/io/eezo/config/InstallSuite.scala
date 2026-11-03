package io.eezo.config

import io.eezo.core.Redacted

/** The installed value is global to the JVM, so the classes here are installed by this suite and no
  * other, and suites that run beside it in one JVM never see or move them.
  */
class InstallSuite extends munit.FunSuite {
  import InstallSuite.*

  private val value = Root(Payments(Redacted("k")), 3)

  test("current with nothing installed throws, and the message names the fix") {
    val thrown = intercept[IllegalStateException](Config[Lonely].current)
    assertNoDiff(
      thrown.getMessage,
      "Config[Lonely].current was asked for before a Lonely was installed. Boot installs the one " +
        "it reads from application.conf when the build names the class with eezoConfigClass; a " +
        "suite wraps its call in Config[Lonely].installed(value) { ... }."
    )
  }

  test("installing the root makes every section current under its own Config") {
    Config[Root].installed(value) {
      assertEquals(Config[Root].current, value)
      assertEquals(Config[Payments].current, value.payments)
    }
  }

  test("install makes the root and every section current, with nothing to give back") {
    val kept = Kept(Shelf("top"), 2)
    Config[Kept].install(kept)
    assertEquals(Config[Kept].current, kept)
    assertEquals(Config[Shelf].current, kept.shelf)
  }

  test("installed gives back what was there before, sections included, also when body throws") {
    val outer = Root(Payments(Redacted("outer")), 1)
    Config[Root].installed(outer) {
      intercept[RuntimeException] {
        Config[Root].installed(value)(throw new RuntimeException("body failed"))
      }
      assertEquals(Config[Root].current, outer)
      assertEquals(Config[Payments].current, outer.payments)
    }
    intercept[IllegalStateException](Config[Payments].current)
  }

  test("a value installed on one thread is current on a virtual thread started after") {
    Config[Root].installed(value) {
      var seen: Option[Root] = None
      val thread             = Thread.ofVirtual().start(() => seen = Some(Config[Root].current))
      thread.join()
      assertEquals(seen, Some(value))
    }
  }
}

object InstallSuite {

  final case class Payments(key: Redacted) derives Config

  final case class Root(payments: Payments, retries: Int) derives Config

  final case class Lonely(name: String) derives Config

  /** Installed for good by one test, so no other test reads it. */
  final case class Shelf(label: String) derives Config

  final case class Kept(shelf: Shelf, count: Int) derives Config
}
