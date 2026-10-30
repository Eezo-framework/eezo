package config

import io.eezo.core.{Config, Dispatch}

import scala.reflect.ClassTag

/** SPIKE, seam B: stand ins for what eezo would own.
  *
  * `Configured` is the entry trait member: mixed into `Main` or a job, it reads the Config before
  * the program runs, refuses with every offender, and installs the value. `Current` is the holder a
  * handler reads, because `Main` lives in the empty package and no handler in a named package can
  * name it; it is the shape `Installed` gives the database.
  */
object Current {
  private var held: Map[Class[?], Any] = Map.empty

  def install[S](value: S)(using tag: ClassTag[S]): Unit = held = held.updated(tag.runtimeClass, value)

  def apply[S](using tag: ClassTag[S]): S =
    held
      .getOrElse(
        tag.runtimeClass,
        throw new IllegalStateException(
          s"${tag.runtimeClass.getSimpleName} was not read: mix Configured[${tag.runtimeClass.getSimpleName}] into the entry object"
        )
      )
      .asInstanceOf[S]
}

trait Configured[S: Config: ClassTag] extends Dispatch {

  abstract override protected def program(): Unit = {
    println("[spike] Configured.program: reading the Config, before the database and the table")
    val value = Config[S]
      .read()
      .fold(refused => throw new IllegalStateException(refused.message), identity)
    Current.install(value)
    println("[spike] Configured.program: read, handing over to the program")
    super.program()
  }
}
