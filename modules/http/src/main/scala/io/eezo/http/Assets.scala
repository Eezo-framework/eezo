package io.eezo.http

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import io.eezo.core.html.Url

/** The application's assets: the files it ships in its jar under `assets/` and serves as they are,
  * under `/assets/<name>`, with a version in every address computed from the file's bytes.
  *
  * The stylesheet the sbt plugin builds from `tailwind.css` is one, at `assets/tailwind.css`; a
  * file written by hand under `src/main/resources/assets` is another, and both are served the same
  * way. A layout links one through [[url]], which spells the version in, so a browser may keep the
  * file for a year and the next deploy, whose bytes differ, is a new address. The route is one of
  * `HttpApp.frameworkRoutes`, appended after the application's table, so an application that mounts
  * its own catch all under `/assets` keeps winning.
  *
  * The version is per file rather than one digest over a list, so that no list has to be kept: a
  * file the build writes and a file nobody told this object about get the same treatment. Each
  * digest is computed once per process. A process's jar never changes, and under the dev loop every
  * restart is a new process.
  *
  * What is refused: a path that is not a plain descent into `assets/` (an empty segment, `.` or
  * `..`), a file that is not there, and a file whose extension names no content type below. All
  * three are the same 404 to the client, because none of them is the client's to tell apart.
  */
object Assets {

  val Prefix: String = "/assets"

  private val ContentTypes: Map[String, String] = Map(
    "css"         -> "text/css; charset=utf-8",
    "js"          -> "text/javascript; charset=utf-8",
    "mjs"         -> "text/javascript; charset=utf-8",
    "json"        -> "application/json",
    "map"         -> "application/json",
    "webmanifest" -> "application/manifest+json",
    "txt"         -> "text/plain; charset=utf-8",
    "svg"         -> "image/svg+xml",
    "png"         -> "image/png",
    "jpg"         -> "image/jpeg",
    "jpeg"        -> "image/jpeg",
    "gif"         -> "image/gif",
    "webp"        -> "image/webp",
    "avif"        -> "image/avif",
    "ico"         -> "image/x-icon",
    "woff2"       -> "font/woff2",
    "woff"        -> "font/woff",
    "ttf"         -> "font/ttf",
    "otf"         -> "font/otf"
  )

  /** The content type for a file name, and nothing for an extension eezo does not serve. */
  def contentType(name: String): Option[String] =
    ContentTypes.get(name.drop(name.lastIndexOf('.') + 1).toLowerCase)

  /** The file's bytes, or nothing: for a path that is not a plain descent into `assets/`, and for a
    * file that is not there.
    */
  def read(path: String): Option[Array[Byte]] = {
    val segments = path.split('/').toVector
    val safe     = segments.nonEmpty && segments.forall(s => s.nonEmpty && s != "." && s != "..")
    if (!safe) None
    else
      Option(loader.getResourceAsStream(s"assets/$path")).map { in =>
        try in.readAllBytes()
        finally in.close()
      }
  }

  /** The version of one file: the first twelve hex characters of its SHA-256, or nothing when the
    * file is not there. Remembered per file after the first ask.
    */
  def version(name: String): Option[String] =
    versions.computeIfAbsent(
      name,
      _ =>
        read(name).map { bytes =>
          MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .take(6)
            .map(b => f"$b%02x")
            .mkString
        }
    )

  /** The address a page links an asset under: `/assets/<name>?v=<version>`. Absolute, because the
    * route is appended at the root and no mount moves it.
    *
    * A file that is not there is a defect, not an empty link: the page that asked was built
    * expecting the file, and a stylesheet that silently fails to load is harder to find than the
    * name in this message.
    */
  def url(name: String): Url =
    version(name) match {
      case Some(hash) => Url.Absolute(s"$Prefix/$name?v=$hash")
      case None       =>
        throw new IllegalStateException(
          s"no asset named $name: nothing at assets/$name on the classpath; " +
            "a stylesheet the plugin builds needs a tailwind.css at the project root, " +
            "and a file of your own lives under src/main/resources/assets"
        )
    }

  /** The route every entry trait serves: the catch all under `/assets`. */
  private[http] val route: Route =
    Route.Http(Method.GET, PathPattern.parse(s"$Prefix/*file"), serve)

  private val versions = new ConcurrentHashMap[String, Option[String]]

  /** The context loader first, because it is the one that sees an application's own classes and
    * resources wherever this object's class was loaded from: sbt runs a suite under a loader of its
    * own, with eezo's jars in a parent layer that cannot see the project's resources.
    */
  private def loader: ClassLoader =
    Option(Thread.currentThread.getContextClassLoader).getOrElse(getClass.getClassLoader)

  private def serve(request: Request): Response = {
    val file        = request.param[String]("file")
    val bytes       = read(file).getOrElse(throw NotFound(request.path))
    val contentType = Assets.contentType(file).getOrElse(throw NotFound(request.path))
    val caching     =
      if (request.queryParam("v").exists(version(file).contains))
        "public, max-age=31536000, immutable"
      else "public, max-age=300"
    Response(
      200,
      Seq("Content-Type" -> contentType, "Cache-Control" -> caching),
      Body.Bytes(bytes)
    )
  }
}
