package views

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

object Show {

  def money(euros: Int): String = s"$euros €"

  private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  def time(at: Instant): String = clock.format(at)
}
