package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.Form
import io.eezo.http.Guarded
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
  */
case class Post(
    id: Id[Post],
    title: String,
    body: String,
    minutes: Int,
    published: Boolean
) derives Table,
      Form,
      Resource

object Post {

  /** All seven routes need a signed in user, reading included.
    *
    * `required` rather than `only(Create, Update, Destroy)`, because this half of the blog is the
    * editing screens: `Main.scala` mounts the derived routes under `/admin` and leaves the public
    * page at `/`, so there is no reader here to keep out of the way of. A blog whose posts are read
    * through the derived index would say `only` instead, and the declaration is the one line that
    * changes.
    *
    * This is also what mounts `/admin/login`: the declaration carries the guard's own routes, and
    * they travel into the table with it.
    */
  given Guarded[Post] = User.guard.required
}
