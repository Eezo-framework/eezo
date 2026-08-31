package io.eezo.core.html

/** A URL a page emits, in the two kinds a mount has to tell apart.
  *
  * A `String` in an `href` is a finished address, and finished is exactly what a mounted
  * application's own links are not: `Route.under("/admin")` moves the route and the page has no way
  * to hear about it. The two cases are that difference, made a type rather than a convention, so
  * that relocation is decided where the URL is written instead of guessed where it is served.
  *
  * There is no third case for "resolved". Prefixing a [[Mounted]] keeps it [[Mounted]], because a
  * set of routes can be mounted again, and a case saying the work is done would have to be undone
  * by the next layer.
  */
enum Url {

  /** Declared here so that code holding a `Url` can read the payload without matching first. */
  def path: String

  /** An address eezo never touches: another site, a `mailto:`, or a path the author means
    * literally. It is kept verbatim rather than normalised, because a scheme is not a path and
    * `/https://eezo.io` is what normalising one would produce.
    */
  case Absolute private (path: String)

  /** An address inside this application, which travels with the prefix its routes are mounted
    * under. The payload is normalised on the way in, the way `Route.under` normalises a prefix, so
    * that `Mounted("posts")` and `Mounted("/posts")` are one value rather than two that render
    * alike.
    */
  case Mounted private (path: String)

  /** Appends one path segment, owning the slash between them so that neither caller has to. */
  def /(segment: String): Url = this match {
    case Absolute(base) => Url.Absolute(Url.join(base, segment))
    case Mounted(base)  => Url.Mounted(Url.join(base, segment))
  }

  /** This URL as seen from under `prefix`. Absolute is the answer to "not mine to move". */
  def under(prefix: String): Url = this match {
    case Absolute(_)   => this
    case Mounted(mine) => Url.Mounted(s"${Url.normalise(prefix)}$mine")
  }
}

object Url {

  /** Written out rather than left to the enum's own constructor, because the payload has to be
    * normalised before it becomes part of the value's identity.
    */
  object Absolute {
    def apply(path: String): Url.Absolute = new Url.Absolute(path)
  }

  object Mounted {
    def apply(path: String): Url.Mounted = new Url.Mounted(normalise(path))
  }

  /** One rooted spelling for a path, so equality answers the question a reader is asking.
    *
    * Only the part before the first `?` or `#` is a path; a query string or a fragment can carry
    * its own doubled slash, an encoded absolute URL among them, that means something to whatever
    * reads it and is not eezo's to collapse.
    */
  private def normalise(path: String): String = {
    val splitAt          = path.indexWhere(c => c == '?' || c == '#')
    val (segments, rest) = if (splitAt < 0) (path, "") else path.splitAt(splitAt)
    "/" + segments.split("/").iterator.filter(_.nonEmpty).mkString("/") + rest
  }

  private def join(base: String, segment: String): String =
    s"${base.stripSuffix("/")}/${segment.stripPrefix("/")}"
}
