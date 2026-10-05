package io.eezo.http

import io.eezo.core.html.*

/** The one frame every HTML reply comes back in.
  *
  * One per application, named in `Main` beside the routes, so that a nav, a stylesheet or a sign
  * out form is written once and reaches every page, the derived ones, the login page and the error
  * page included, without any route asking for it. A route returns content and the frame is put
  * around it; a route that returns a whole document, an `html` element at its root, steps outside
  * the frame and goes out as written.
  *
  * `title` is the text of the first `title` element the route wrote beside its content, lifted out
  * before the call, because the title is a fact about the page and the head it belongs in is the
  * layout's. It arrives as plain text, `None` when the route wrote none. The result runs from
  * `html` down: the framework prepends the doctype, so a layout cannot forget it.
  *
  * The request is there so that the frame can depend on who is asking, a sign in link or a sign out
  * form. It is the request as it arrived, its session read, and it does not see what the reply does
  * to the session: a page that signs somebody in is framed as the anonymous request it was. A first
  * visit, an error page and a request that matched no route carry no CSRF token yet, so a frame
  * that asks for one there throws, and a frame that throws is answered as a 500 in [[plain]].
  */
trait Layout {
  def apply(request: Request, title: Option[String], content: Html): Html
}

object Layout {

  /** eezo's own frame, what an application that names none gets: nothing but the charset and the
    * title, so a page reads as the route wrote it.
    */
  val plain: Layout = (_, title, content) => document(title, content)

  /** `layout` put around one page, for the request it answers.
    *
    * The request is by name because most replies never need it: a document, a byte body and an
    * empty one are not framed, and reading the session for them would be work for nothing.
    */
  private[http] def wrap(layout: Layout, request: => Request, page: Html): Html =
    frame(page)(layout(request, _, _))

  /** [[plain]] put around one page, with no request to hand it: the frame for a reply that has
    * none, a request that could not be read, and for the error page of a layout that threw, which
    * must not be framed by the layout that just failed. It has nothing left to throw, so the reply
    * it frames always reaches the browser.
    */
  private[http] def plainly(page: Html): Html = frame(page)(document)

  /** A page whose root is an `html` element is a document already and is returned as it is: that is
    * how a page steps outside the frame, and how every page written before the frame existed keeps
    * working.
    */
  private def frame(page: Html)(build: (Option[String], Html) => Html): Html =
    if (isDocument(page)) page
    else {
      val (title, content) = lift(page)
      Html.doctype ++ build(title, content)
    }

  /** Through `Fragment` and past the doctype, which is a `Raw` and so never an element, the way the
    * reload client's walk goes: a page this calls content is never one that already holds an `html`
    * element at its root, which the layout would then nest inside a second one.
    */
  private def isDocument(page: Html): Boolean = page match {
    case Html.Element(name, _, _, _) => name == "html"
    case Html.Fragment(children)     => children.exists(isDocument)
    case _                           => false
  }

  /** The first `title` element anywhere in the page, taken out, and its text.
    *
    * Anywhere, in the order the page reads, because a route is free to wrap its content in an
    * element of its own, and a title it wrote there is still the title it meant. The text is taken
    * back out of its escaped form, since the layout writes it through a tag that escapes it again:
    * handing over the escaped text would escape it twice, and handing it over as markup would let a
    * title built from a model's data write into the head.
    */
  private def lift(page: Html): (Option[String], Html) = {
    // Whether an earlier node was the title, so a page with two lifts exactly one.
    var lifted = Option.empty[String]

    def walk(node: Html): Vector[Html] = node match {
      case Html.Element("title", _, _, children) if lifted.isEmpty =>
        lifted = Some(Html.unescape(Html.Fragment(children).render))
        Vector.empty
      case Html.Element(name, attrs, key, children) =>
        Vector(Html.Element(name, attrs, key, children.flatMap(walk)))
      case Html.Fragment(children) => Vector(Html.Fragment(children.flatMap(walk)))
      case leaf                    => Vector(leaf)
    }

    val content = walk(page) match {
      case Vector(only) => only
      case none         => Html.Fragment(none)
    }
    (lifted, content)
  }

  private def document(title: Option[String], content: Html): Html =
    html(
      head(meta(Attrs.charset := "utf-8"), title.fold(Html.empty)(Tags.title(_))),
      body(content)
    )
}
