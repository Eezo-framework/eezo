package site

/** The API reference's files, served out of the jar's `api/` directory, where the build copied the
  * scaladoc `sbt unidoc` generated.
  *
  * The site's own assets, the stylesheet, the scripts, the fonts and the icons, are the framework's
  * to serve since eezo 0.4.0: `io.eezo.http.Assets` reads them out of `assets/` and versions every
  * address, and its route is one of the framework routes every entry trait mounts. What stays here
  * is the reading under another directory, with the content types scaladoc's output needs.
  */
object Assets {

  private val ContentTypes: Map[String, String] = Map(
    "html"        -> "text/html; charset=utf-8",
    "css"         -> "text/css; charset=utf-8",
    "js"          -> "text/javascript; charset=utf-8",
    "svg"         -> "image/svg+xml",
    "woff2"       -> "font/woff2",
    "woff"        -> "font/woff",
    "ttf"         -> "font/ttf",
    "eot"         -> "application/vnd.ms-fontobject",
    "json"        -> "application/json",
    "map"         -> "application/json",
    "png"         -> "image/png",
    "ico"         -> "image/x-icon",
    "txt"         -> "text/plain; charset=utf-8",
    "webmanifest" -> "application/manifest+json"
  )

  /** The content type for a file name, and nothing for an extension the site does not serve. */
  def contentType(name: String): Option[String] =
    ContentTypes.get(name.drop(name.lastIndexOf('.') + 1).toLowerCase)

  /** The file's bytes under a directory of the jar, or nothing: for a path that is not a plain
    * descent into it, and for a file that is not there. Both are the same 404 to the client.
    */
  def readUnder(root: String, path: String): Option[Array[Byte]] = {
    val segments = path.split('/').toVector
    val safe     = segments.nonEmpty && segments.forall(s => s.nonEmpty && s != "." && s != "..")
    if (!safe) None
    else
      Option(getClass.getClassLoader.getResourceAsStream(s"$root/$path")).map { in =>
        try in.readAllBytes()
        finally in.close()
      }
  }
}
