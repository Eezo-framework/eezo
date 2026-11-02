package config

import io.eezo.EezoApp
import io.eezo.config.Config

/** SPIKE (issue 393): a stand in for what eezo would own. The entry object names its one class in
  * `config`, and the Config is read before the program, before the database and the table, under
  * `run` and under `dev` alike. A refusal prints every offender through `fail` and exits 1.
  */
trait Configured extends EezoApp {

  protected def config: Config.Root[?]

  private def read(): Unit = {
    println(s"[spike] reading application.conf (digest ${config.digest.take(8)}) before the database")
    config.boot() match {
      case Left(refused) =>
        fail(Nil, s"application.conf cannot be read:\n${refused.message.linesIterator.map("  " + _).mkString("\n")}")
        sys.exit(1)
      case Right(value) => println(s"[spike] read $value")
    }
  }

  abstract override protected def program(): Unit = { read(); super.program() }

  abstract override protected def devServer(): Unit = { read(); super.devServer() }
}
