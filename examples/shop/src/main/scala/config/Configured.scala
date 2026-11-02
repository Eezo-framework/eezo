package config

import io.eezo.EezoApp
import io.eezo.config.Config

/** SPIKE (issue 393): a stand in for what eezo would own. The build names the class, the plugin
  * writes the root into the generated unit, and this trait reads it before the program, before the
  * database and the table, under `run` and under `dev` alike. A refusal prints every offender
  * through `fail` and exits 1. A project whose build names no class boots as before.
  */
trait Configured extends EezoApp {

  private def read(): Unit =
    Config.Root.generated.foreach { root =>
      println(s"[spike] reading ${root.resource} before the database")
      root.boot() match {
        case Left(refused) =>
          fail(
            Nil,
            s"application.conf cannot be read:\n${refused.message.linesIterator.map("  " + _).mkString("\n")}"
          )
          sys.exit(1)
        case Right(value) => println(s"[spike] read $value")
      }
    }

  abstract override protected def program(): Unit = { read(); super.program() }

  abstract override protected def devServer(): Unit = { read(); super.devServer() }
}
