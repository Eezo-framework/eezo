package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.Form
import io.eezo.http.Resource

/** One model, three derivations, three payoffs:
  *
  *   - `Table` — the case class is the database schema. `AppSchema` registers it, and every drift
  *     command (`status`, `sync`, `freeze`, `migrate`) works off what the compiler derived here.
  *   - `Form` — it renders and parses as an HTML form; `Option` is what makes a field optional,
  *     `Boolean` a checkbox.
  *   - `Resource` — the seven CRUD routes mount over it at `/todos`.
  *
  * The key must be `Id[Todo]`: `create` mints one with `Id.gen()`. It never appears in the form —
  * it travels in the path.
  */
case class Todo(
    id: Id[Todo],
    title: String,
    notes: Option[String],
    done: Boolean
) derives Table,
      Form,
      Resource
