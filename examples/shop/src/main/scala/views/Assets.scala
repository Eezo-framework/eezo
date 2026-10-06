package views

import java.security.MessageDigest

/** The shop's static files, served out of the jar's `assets/` directory: the stylesheet today.
  *
  * eezo has no static file route of its own yet, so the shop carries the few lines one needs, the
  * same lines the documentation site does: a content type by extension, a refusal of any path that
  * tries to leave the directory, and a version in every URL so that the stylesheet can be cached
  * for a year and still change on the next deploy.
  */
object Assets {

  private val ContentTypes: Map[String, String] = Map(
    "css"   -> "text/css; charset=utf-8",
    "js"    -> "text/javascript; charset=utf-8",
    "svg"   -> "image/svg+xml",
    "woff2" -> "font/woff2",
    "png"   -> "image/png",
    "ico"   -> "image/x-icon"
  )

  /** The content type for a file name, and nothing for an extension the shop does not serve. */
  def contentType(name: String): Option[String] =
    ContentTypes.get(name.drop(name.lastIndexOf('.') + 1).toLowerCase)

  /** The file's bytes, or nothing: for a path that is not a plain descent into `assets/`, and for a
    * file that is not there. Both are the same 404 to the client.
    */
  def read(path: String): Option[Array[Byte]] = {
    val segments = path.split('/').toVector
    val safe     = segments.nonEmpty && segments.forall(s => s.nonEmpty && s != "." && s != "..")
    if (!safe) None
    else
      Option(getClass.getClassLoader.getResourceAsStream(s"assets/$path")).map { in =>
        try in.readAllBytes()
        finally in.close()
      }
  }

  /** A short digest of the stylesheet, spelled into every asset URL, so that a change to it is a
    * new address and a year long cache is safe.
    */
  lazy val version: String = {
    val digest = MessageDigest.getInstance("SHA-256")
    Vector("shop.css").flatMap(read).foreach(digest.update)
    digest.digest().take(6).map(b => f"$b%02x").mkString
  }

  /** The address the pages link an asset under. */
  def url(name: String): String = s"/assets/$name?v=$version"
}
