package models

import io.eezo.core.Id
import io.eezo.http.Form
import io.eezo.http.Resource

/** The whole of skeleton two's application code, beside its `@main`.
  *
  * `derives Form` makes the model render and parse as an HTML form; `derives Resource` mounts the
  * seven CRUD routes over it. Neither needs `derives Table`: the route path is computed from the
  * class name, so a model with no database behind it still serves.
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
) derives Form,
      Resource
