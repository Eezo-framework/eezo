package blog

import io.eezo.auth.Password
import io.eezo.config.Config

/** The blog's own values, read from `src/main/resources/application.conf` before the blog starts.
  * The build names this class with `eezoConfigClass`. Its one section is the operator's job's, so
  * the server boots on an environment that holds nothing of the blog's.
  */
final case class BlogConfig(createUser: CreateUserConfig) derives Config

/** What [[CreateUser]] needs and nothing else does. Both fields are optional, so the server boots
  * without them; the job refuses an absent one itself.
  */
final case class CreateUserConfig(email: Option[String], password: Option[Password]) derives Config
