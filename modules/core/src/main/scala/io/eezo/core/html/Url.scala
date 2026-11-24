package io.eezo.core.html

/** A URL a page emits, in the two kinds a mount has to tell apart.
  *
  * [[Absolute]] is a finished address. [[Mounted]] is an address of this application, and it takes
  * the prefix of the routes it is mounted under. A mounted url stays [[Mounted]] after a mount, so
  * a second mount moves it again.
  */
enum Url {

  /** The payload, readable without a match. */
  def path: String

  /** An address eezo never touches: another site, a `mailto:`, or a path the author means
    * literally. The payload is kept verbatim.
    */
  case Absolute private (path: String)

  /** An address inside this application, which travels with the prefix its routes are mounted
    * under. The payload is normalised on the way in by [[Url.normalise]], as `Route.under`
    * normalises a prefix, so `Mounted("posts")` and `Mounted("/posts")` are one value.
    */
  case Mounted private (path: String)

  /** Appends one path segment, owning the slash between them so that neither caller has to. */
  def /(segment: String): Url = this match {
    case Absolute(base) => Url.Absolute(Url.join(base, segment))
    case Mounted(base)  => Url.Mounted(Url.join(base, segment))
  }

  /** This URL as seen from under `prefix`. An [[Absolute]] is unchanged. A [[Mounted]] takes the
    * prefix and is normalised again, so `Mounted("/").under("/admin")` is `/admin`, not `/admin/`.
    */
  def under(prefix: String): Url = this match {
    case Absolute(_)   => this
    case Mounted(mine) => Url.Mounted(s"${Url.normalise(prefix)}$mine")
  }
}

object Url {

  object Absolute {
    def apply(path: String): Url.Absolute = new Url.Absolute(path)
  }

  object Mounted {
    def apply(path: String): Url.Mounted = new Url.Mounted(normalise(path))
  }

  /** One rooted spelling for a path, so that two spellings of one path are equal.
    *
    * Only the part before the first `?` or `#` is a path: a query string or a fragment keeps its
    * doubled slashes. `Route.under` normalises a mount prefix with this function too.
    */
  def normalise(path: String): String = {
    val splitAt          = path.indexWhere(c => c == '?' || c == '#')
    val (segments, rest) = if (splitAt < 0) (path, "") else path.splitAt(splitAt)
    "/" + segments.split("/").iterator.filter(_.nonEmpty).mkString("/") + rest
  }

  private def join(base: String, segment: String): String =
    s"${base.stripSuffix("/")}/${segment.stripPrefix("/")}"
}
