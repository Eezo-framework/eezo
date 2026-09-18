package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.Action
import io.eezo.http.Form
import io.eezo.http.Owned
import io.eezo.http.Resource

/** The whole of the blog's application code, beside its entry point.
  *
  * One model, three derivations, one per thing the application does with it. `Table` is the
  * database edge's: the case class is the table, `AppSchema` registers it, and the generated route
  * table picks `JdbcStore` for a model that carries one, so the seven routes read and write rows in
  * Postgres. `Form` and `Resource` are the http edge's: the model renders and parses as an HTML
  * form, and the seven CRUD routes mount over it. This application has both edges, so all three
  * compile; `examples/hello` and `examples/reminders` each have one, and show the derivation of the
  * missing edge failing.
  *
  * The key has to be `Id[Post]`, because `create` mints one with `Id.gen()` and no other key type
  * in eezo can be generated. It is never rendered into the form: it travels in the path.
  *
  * `author` is the owner, and it is a field like any other: the same `Id[User]` a `Ref` would hold,
  * stored in a column of the same name. It is never on a page. The derived form has no input for it,
  * the index no column and the show page no row, because the value is filled from who is signed in
  * rather than from anything a browser sends.
  */
case class Post(
    id: Id[Post],
    author: Id[User],
    title: String,
    body: String,
    minutes: Int,
    published: Boolean
) derives Table,
      Form,
      Resource

object Post {

  /** All seven routes need a signed in user, and five of them are the author's own.
    *
    * One line says both, and the two halves are different questions. `required` is who has to be
    * signed in: this half of the blog is the editing screens, `Main.scala` mounts them under
    * `/admin` and leaves the public page at `/`, so there is no reader here to keep out of the way
    * of. `except(Index, Show)` is whose rows each route reads: every author sees the whole blog and
    * edits only what they wrote. A model nobody but its owner may even see would end in `.all`.
    *
    * The model type is written on `required` because a selector's parameter type cannot be inferred
    * from a chain whose expected type arrives at its end. The field is written once, as a selector,
    * so renaming `author` moves the column with it instead of leaving a string behind.
    *
    * This is also what mounts `/admin/login`: the declaration carries the guard's own routes, and
    * they travel into the table with it.
    */
  given Owned[Post, User] =
    User.guard.required[Post].owning(_.author).except(Action.Index, Action.Show)
}
