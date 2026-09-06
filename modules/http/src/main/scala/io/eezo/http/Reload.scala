package io.eezo.http

import io.eezo.core.html.Html
import io.eezo.core.html.Tags.*

/** Reload: the browser's side of the dev loop.
  *
  * The dev server adds a small script to every page it serves. The script keeps a WebSocket open to
  * the server; the restart that follows a save closes it; the script then polls until the new
  * server answers and refreshes the page. Whole page, no state kept. This is the reload contract
  * (issue 153), and `Eezo` is its only caller: `inject` from the handler's one completion site, so
  * every response passes it, and `listenerFor` from the WebSocket creator, before the user's table.
  * Both read `config.dev` here, so the dev gate has one owner.
  *
  * The endpoint sits under `Eezo.ReservedPrefix`. Today it is the only framework route dispatched
  * here; the dev server's drift page mounts its actions under the same prefix as ordinary routes.
  */
private[http] object Reload {

  /** The reserved endpoint. WebSocket only: an HTTP GET on it is an ordinary 404. */
  private[http] val path: String = s"${Eezo.ReservedPrefix}/reload"

  /** The client, inline. About thirty lines held here rather than served from a second reserved
    * path, so there is no resource to locate and no caching question.
    */
  private val client: String =
    s"""(function () {
      |  var url = (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "$path";
      |  var lost = false;
      |  function connect() {
      |    var socket = new WebSocket(url);
      |    socket.onopen = function () {
      |      if (lost) location.reload();
      |    };
      |    socket.onclose = function () {
      |      lost = true;
      |      setTimeout(connect, 200);
      |    };
      |    socket.onerror = function () {
      |      socket.close();
      |    };
      |  }
      |  connect();
      |})();""".stripMargin

  /** What the server does with a reload connection: nothing. The client needs only the open and
    * close events, and the restart is what produces the close.
    */
  private val listener: WsListener = new WsListener {}

  /** The listener for an upgrade at `requested`: the reload listener when that is the reserved
    * endpoint and `dev` is on, `None` otherwise so the caller falls through to the user's table.
    */
  private[http] def listenerFor(requested: String, config: Config): Option[WsListener] =
    if (config.dev && requested == path) Some(listener) else None

  /** The script tag every dev server page carries. */
  private[http] val tag: Html = script(Html.raw(client))

  /** The tag, appended to a page when `dev` is on.
    *
    * Structural, not by string search: the tree is walked, descending through `Fragment` so that
    * `Html.doctype ++ html(...)` works, and the tag becomes the last child of the first `body`
    * element. No `body`, no script: a fragment is not a document, and neither is `Html.raw`.
    * `Body.Bytes` is never touched, even with an HTML content type, because it is user encoded.
    */
  private[http] def inject(response: Response, config: Config): Response =
    response.body match {
      case Body.Html(page) if config.dev => response.copy(body = Body.Html(appendToBody(page)))
      case _                             => response
    }

  /** The tree with the tag appended to its first `body`, or the tree unchanged when it has none. */
  private def appendToBody(page: Html): Html = {
    // Whether an earlier node already took the tag, so a page with two `body` elements, which no
    // parser accepts anyway, gets exactly one script rather than one per body.
    var done = false

    page.transform {
      case Html.Element("body", attrs, key, children) if !done =>
        done = true
        Html.Element("body", attrs, key, children :+ tag)
      case node => node
    }
  }
}
