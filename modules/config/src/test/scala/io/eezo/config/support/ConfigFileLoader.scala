package io.eezo.config.support

import java.net.URL

/** A context class loader that answers the two names boot looks up, `io.eezo.generated.ConfigFile`
  * and `application.conf`, from a suite's fixtures, and every other name from the loader it
  * replaces.
  *
  * Boot finds both by a fixed name, so a fixture under that name on the test classpath would be
  * seen by every suite of the module, and those run side by side in one JVM. The context class
  * loader is the thread's own, so a suite that swaps it for the length of one call is seen by that
  * call and nothing else.
  */
final class ConfigFileLoader(
    configFile: Option[Class[?]],
    conf: Option[URL],
    resources: Map[String, List[URL]] = Map.empty
) extends ClassLoader(io.eezo.config.Config.loader) {

  /** Every resource a name finds, in classpath order: `conf` first for `application.conf`, then
    * whatever `resources` puts behind it, as a dependency's file would sit behind the application's
    * own.
    */
  private def found(name: String): Option[List[URL]] =
    if (name == "application.conf") Some(conf.toList ++ resources.getOrElse(name, Nil))
    else resources.get(name)

  override protected def loadClass(name: String, resolve: Boolean): Class[?] =
    if (name == ConfigFileLoader.ConfigFile)
      configFile.getOrElse(throw new ClassNotFoundException(name))
    else super.loadClass(name, resolve)

  override def getResource(name: String): URL =
    found(name).fold(super.getResource(name))(_.headOption.orNull)

  override def getResources(name: String): java.util.Enumeration[URL] =
    found(name).fold(super.getResources(name))(urls =>
      java.util.Collections.enumeration(java.util.Arrays.asList(urls*))
    )
}

object ConfigFileLoader {

  private val ConfigFile = "io.eezo.generated.ConfigFile$"

  /** Runs `body` with `loader` as this thread's context class loader, and the one before it back
    * after, also when `body` throws.
    */
  def within[A](loader: ClassLoader)(body: => A): A = {
    val thread = Thread.currentThread
    val before = thread.getContextClassLoader
    thread.setContextClassLoader(loader)
    try body
    finally thread.setContextClassLoader(before)
  }

  /** Runs `body` as an application whose build names a class: `generated`, the class of an object
    * as `classOf[Written.type]` names it, stands for the object the plugin writes, and `conf`, a
    * test resource, for its `application.conf`, absent when `None`. The class and not the object,
    * so that naming it here does not initialise it before boot does.
    */
  def generating[A](generated: Class[?], conf: Option[String])(body: => A): A =
    within(new ConfigFileLoader(Some(generated), conf.map(resource)))(body)

  /** Runs `body` as an application whose build names no class, with `conf` as its
    * `application.conf` when given.
    */
  def plain[A](conf: Option[String])(body: => A): A =
    within(new ConfigFileLoader(None, conf.map(resource)))(body)

  def resource(name: String): URL =
    Option(getClass.getClassLoader.getResource(name))
      .getOrElse(throw new IllegalArgumentException(s"no test resource $name"))
}
