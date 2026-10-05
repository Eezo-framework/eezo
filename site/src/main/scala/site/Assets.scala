package site

import java.security.MessageDigest

/** The site's static files, served out of the jar's `assets/` directory.
  *
  * eezo has no static file route of its own yet, so the site carries the few lines one needs: a
  * content type by extension, a refusal of any path that tries to leave the directory, and a
  * version in every URL so that a stylesheet can be cached for a year and still change on the next
  * deploy.
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

  /** The file's bytes, or nothing: for a path that is not a plain descent into `assets/`, and for a
    * file that is not there. Both are the same 404 to the client.
    */
  def read(path: String): Option[Array[Byte]] = readUnder("assets", path)

  /** The same, under another directory of the jar: `api` for the generated scaladoc. */
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

  /** A short digest of the stylesheets and scripts, spelled into every asset URL, so that a change
    * to it is a new address and a year long cache is safe.
    */
  lazy val version: String = {
    val digest = MessageDigest.getInstance("SHA-256")
    Vector(
      "site.css",
      "api.css",
      "keys.js",
      "hljs/highlight.min.js",
      "hljs/scala.min.js",
      "hljs/nginx.min.js"
    )
      .flatMap(read)
      .foreach(digest.update)
    digest.digest().take(6).map(b => f"$b%02x").mkString
  }

  /** The address the pages link an asset under. */
  def url(name: String): String = s"/assets/$name?v=$version"
}
