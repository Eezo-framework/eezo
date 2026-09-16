/* eezo live: the page client.
 *
 * Served concatenated after applier.js as /eezo/live.js. Finds the mount anchor, opens the
 * socket, joins, applies patch frames, and forwards bound events. Reconnects with capped
 * exponential backoff; a server that no longer knows the page (4404 - a restart, a reaped page)
 * means the state is gone, and the honest recovery is a full reload into a fresh mount. In dev
 * the reload script drives the same recovery; the two agree by both ending in location.reload().
 */
(function () {
  "use strict";
  if (window.__eezoLiveClient) return; // one client per page, however many mounts rendered
  window.__eezoLiveClient = true;

  function boot() {
    var anchor = document.querySelector("[data-eezo-page]");
    if (!anchor) {
      if (document.querySelector("[data-eezo-dead]")) {
        console.warn("eezo live: page registry at capacity; this page is a static render");
      }
      return;
    }
    var pageId = anchor.getAttribute("data-eezo-page");
    var base = anchor.getAttribute("data-eezo-base") || "/";
    var url =
      (location.protocol === "https:" ? "wss://" : "ws://") +
      location.host + "/eezo/live/" + pageId;

    var socket = null;
    var attempts = 0;
    var leaving = false;

    window.addEventListener("beforeunload", function () {
      // 1000 marks the close as intentional: the server frees the page at once, and the backoff
      // loop below knows not to fire.
      leaving = true;
      if (socket && socket.readyState === 1) socket.close(1000, "leaving");
    });

    function connect() {
      socket = new WebSocket(url);

      socket.onopen = function () {
        attempts = 0;
        socket.send(JSON.stringify({ kind: "join", base: base }));
      };

      socket.onmessage = function (message) {
        var frame;
        try {
          frame = JSON.parse(message.data);
        } catch (e) {
          console.error("eezo live: unreadable frame", e);
          return;
        }
        if (frame.kind === "patches") {
          var failures = window.EezoLive.applyPatches(anchor, frame.patches);
          for (var i = 0; i < failures.length; i++) {
            console.error("eezo live: refused patch: " + failures[i].reason);
          }
        } else if (frame.kind === "error") {
          console.error("eezo live: " + frame.message);
        }
        // pong: nothing to do; arriving was the point
      };

      socket.onclose = function (event) {
        if (leaving) return;
        if (event.code === 4404) { location.reload(); return; }
        if (event.code === 4409 || event.code === 4403) {
          console.error("eezo live: refused: " + (event.reason || event.code));
          return;
        }
        attempts++;
        if (attempts > 8) { location.reload(); return; }
        var delay = Math.min(500 * Math.pow(2, attempts - 1), 8000);
        setTimeout(connect, delay);
      };

      socket.onerror = function () {
        socket.close();
      };
    }

    // Well under the server's 5-minute idle timeout, so an idle page stays connected on
    // purpose rather than by reconnect (design/live.md §2.5).
    setInterval(function () {
      if (socket && socket.readyState === 1) socket.send(JSON.stringify({ kind: "ping" }));
    }, 30000);

    // One delegated listener at the document: bindings survive any patch that replaces their
    // element, including a full resync.
    document.addEventListener("click", function (e) {
      var el = e.target && e.target.closest ? e.target.closest("[data-eezo-click]") : null;
      if (!el || !anchor.contains(el)) return;
      e.preventDefault();
      if (socket && socket.readyState === 1) {
        socket.send(JSON.stringify({
          kind: "event",
          name: el.getAttribute("data-eezo-click"),
          payload: {}
        }));
      }
    });

    connect();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
