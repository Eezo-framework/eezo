package io.eezo.config

import io.eezo.config.support.ConfigFileLoader

/** How boot learns whether the build names a class: the object the plugin writes, looked up by its
  * fixed name through the context class loader. Only a class nobody wrote means no class; a unit
  * that is there and does not load is the build gone wrong, and boot says so instead of running
  * with nothing installed.
  */
class GeneratedSuite extends munit.FunSuite {
  import GeneratedSuite.*

  test("no ConfigFile visible to the context class loader is no class") {
    assertEquals(Config.Root.generated, None)
    ConfigFileLoader.within(new ConfigFileLoader(None, None)) {
      assertEquals(Config.Root.generated, None)
    }
  }

  test("the ConfigFile the plugin wrote is the root it holds") {
    ConfigFileLoader.generating(classOf[Written.type], conf = None) {
      assert(Config.Root.generated.exists(_ eq Written.root), Config.Root.generated)
    }
  }

  test("a ConfigFile that fails to initialise fails loudly and is not read as no class") {
    ConfigFileLoader.generating(classOf[Broken.type], conf = None) {
      // By hand rather than through `intercept`, which lets a LinkageError through as fatal.
      val thrown =
        try Left(Config.Root.generated)
        catch { case e: ExceptionInInitializerError => Right(e) }
      assertEquals(thrown.map(_.getCause.getMessage), Right("the root failed"))
    }
  }

  test("a ConfigFile that is not a Config.Root.Generated fails loudly and names the class") {
    ConfigFileLoader.generating(classOf[Stranger.type], conf = None) {
      val thrown = intercept[IllegalStateException](Config.Root.generated)
      assertEquals(
        thrown.getMessage,
        "io.eezo.generated.ConfigFile is not a Config.Root.Generated, so the plugin did not write it: " +
          "move the class that has its name, which only the plugin writes"
      )
    }
  }

  test("a class named ConfigFile with no object fails loudly and is not read as no class") {
    val loader = new ClassLoader(Config.loader) {
      override protected def loadClass(name: String, resolve: Boolean): Class[?] =
        if (name == "io.eezo.generated.ConfigFile") classOf[String]
        else if (name == "io.eezo.generated.ConfigFile$") throw new ClassNotFoundException(name)
        else super.loadClass(name, resolve)
    }
    ConfigFileLoader.within(loader) {
      val thrown = intercept[IllegalStateException](Config.Root.generated)
      assertEquals(
        thrown.getMessage,
        "io.eezo.generated.ConfigFile is not a Config.Root.Generated, so the plugin did not write it: " +
          "move the class that has its name, which only the plugin writes"
      )
    }
  }
}

object GeneratedSuite {

  final case class Till(name: String) derives Config

  object Written extends Config.Root.Generated {
    val root: Config.Root[Till] = Config.root[Till]
  }

  object Broken extends Config.Root.Generated {
    val root: Config.Root[Till] = throw new RuntimeException("the root failed")
  }

  object Stranger {
    val root: Int = 1
  }
}
