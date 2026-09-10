package models

import java.time.LocalDate

import io.eezo.core.Id
import io.eezo.db.Table

/** One model, one derivation. `Table` is the whole declaration of the database edge for this model:
  * the case class is the table, `AppSchema` registers it, and every drift command (`status`,
  * `sync`, `freeze`, `migrate`) works off what the compiler derived here.
  *
  * `Form` and `Resource` are the http edge's derivations, and this application does not have that
  * edge: it depends on `eezo-db`, so `io.eezo.http` is not on the classpath and `derives Form` here
  * does not compile. See README.md for the exact line and the error it produces.
  */
case class Reminder(
    id: Id[Reminder],
    text: String,
    dueOn: LocalDate,
    sent: Boolean
) derives Table

object Reminder {

  /** What the job finds on an empty table: two already due, one for tomorrow. */
  def seed(today: LocalDate): List[Reminder] = List(
    Reminder(Id.gen(), "renew the domain", today.minusDays(3), sent = false),
    Reminder(Id.gen(), "rotate the database password", today, sent = false),
    Reminder(Id.gen(), "send the invoice", today.plusDays(1), sent = false)
  )
}
