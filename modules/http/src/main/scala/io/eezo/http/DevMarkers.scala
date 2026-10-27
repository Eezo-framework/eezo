package io.eezo.http

import io.eezo.core.internal.Json

/** The dev loop's marker lines: what the application tells the dev console at boot, one line each,
  * on stdout.
  *
  * The console is the sbt plugin, reading the forked application's output. It wants two things the
  * boot already prints for a human, the route table and the drift, as values rather than prose:
  * `eezo dev: routes <json>` and `eezo dev: drift <json>`, the same shapes `routes --json` and
  * `db status --json` print, compacted onto one line so that one line is one event. They are
  * printed only when `EEZO_DEV_MARKERS` is set, which the plugin does for the child it forks, so
  * `sbt "run dev"` by hand stays readable. `serving on <url>` is the third marker, printed always,
  * since it was the console's last boot line before any of this.
  */
private[eezo] object DevMarkers {

  /** The variable the plugin sets on the child: any non empty value turns the markers on. */
  val EnvVar: String = "EEZO_DEV_MARKERS"

  def enabled: Boolean = enabledIn(sys.env)

  def enabledIn(env: Map[String, String]): Boolean = env.get(EnvVar).exists(_.nonEmpty)

  /** One marker line, with no newline inside it whatever `json` holds. */
  def line(name: String, json: Json): String = s"eezo dev: $name ${Json.compact(json)}"
}
