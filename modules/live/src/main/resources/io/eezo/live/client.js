/* eezo live: the page client.
 *
 * Served concatenated after applier.js as /eezo/live.js. Finds the mount anchor, opens the
 * socket, joins, applies patch frames, and forwards bound events.
 *
 * Recovery has two distinct shapes, and conflating them is a bug:
 *   - The server is UNREACHABLE (killed, restarting, a network blip). Every connect attempt
 *     fails before it opens. Retry steadily and forever, capped — never reload, because there
 *     is nothing to reload to. When the server returns, the next attempt opens.
 *   - The server is REACHABLE but does not want this connection: 4404 (it no longer knows the
 *     page — a restart or a reap; state is gone) and 4409 (this page id already has a live
 *     socket — a DUPLICATED tab carries the original's id in its copied HTML). Both are healed
 *     by a full reload: a fresh GET mints a fresh page id, so a duplicate becomes its own
 *     independent page and a stale tab rejoins a live server.
 *
 * A reload guard in sessionStorage breaks the one loop this could form (a reload served the same
 * id from cache): the guard is cleared only once a real session is established — the first
 * patches frame — so a connection that opens and is immediately closed with 4404/4409 cannot
 * clear it. In dev the reload script drives the same recovery; the two agree by both reloading.
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
    var watchdog = null; // fires if a connect attempt never opens (see connect())

    var GUARD = "eezoLiveReload";

    // The connection state, mirrored onto the anchor so it is visible in the Elements panel and
    // stylable ([data-eezo-state="lost"] { ... }). "connecting" until the first frame lands.
    function state(value) {
      anchor.setAttribute("data-eezo-state", value);
    }
    state("connecting");

    // A steady retry: no reload while the server is simply unreachable, so this can log the first
    // loss once and then stay quiet until it recovers.
    function retry() {
      if (attempts === 0) console.info("eezo live: connection lost, reconnecting…");
      attempts++;
      var delay = Math.min(250 * Math.pow(2, attempts - 1), 3000);
      state("lost");
      setTimeout(connect, delay);
    }

    // Reloads to recover, unless we already reloaded moments ago without establishing a session —
    // that would be a loop (a cached response serving the same page id), so stop and say why.
    function recover(reason) {
      var now = Date.now();
      try {
        var last = parseInt(sessionStorage.getItem(GUARD) || "0", 10);
        if (now - last < 3000) {
          console.error("eezo live: " + reason + " (reload suppressed to avoid a loop)");
          return;
        }
        sessionStorage.setItem(GUARD, String(now));
      } catch (e) {
        // sessionStorage unavailable (private mode, etc.): recover anyway, loop or not.
      }
      location.reload();
    }

    window.addEventListener("beforeunload", function () {
      // 1000 marks the close as intentional: the server frees the page at once, and the retry
      // loop below knows not to fire.
      leaving = true;
      if (socket && socket.readyState === 1) socket.close(1000, "leaving");
    });

    function connect() {
      socket = new WebSocket(url);

      // A connect attempt that neither opens nor closes stalls the whole loop — and this is the
      // common case while a server restarts: the OS port is bound (or the JVM is still coming up)
      // so the socket sits in CONNECTING with no event ever firing. Force the issue: if it has
      // not opened within the window, close it, which fires onclose and schedules the next retry.
      clearTimeout(watchdog);
      watchdog = setTimeout(function () {
        if (socket && socket.readyState === 0) socket.close();
      }, 4000);

      socket.onopen = function () {
        clearTimeout(watchdog);
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
          // A real session: the connection is healthy. Clear the reload guard (a 4404/4409
          // connection never reaches here, so it can never clear it), announce a recovery if this
          // followed a loss, and reset the backoff.
          try { sessionStorage.removeItem(GUARD); } catch (e) {}
          if (attempts > 0) console.info("eezo live: reconnected");
          attempts = 0;
          state("connected");
          var failures = window.EezoLive.applyPatches(anchor, frame.patches);
          if (failures.length > 0) {
            // Fail loud, then heal: the console gets every reason, the server gets a report and
            // answers with a full resync (design/live.md §1.1 on §4.3). Never a wrong DOM kept.
            var reasons = [];
            for (var i = 0; i < failures.length; i++) {
              console.error("eezo live: refused patch: " + failures[i].reason);
              if (reasons.length < 8) reasons.push(failures[i].reason);
            }
            if (socket && socket.readyState === 1) {
              socket.send(JSON.stringify({ kind: "failed", reasons: reasons }));
            }
          }
        } else if (frame.kind === "error") {
          console.error("eezo live: " + frame.message);
        }
        // pong: nothing to do; arriving was the point
      };

      socket.onclose = function (event) {
        clearTimeout(watchdog);
        if (leaving) return;
        // Server reachable, connection unwanted: reload into a fresh page id.
        if (event.code === 4404) { recover("page no longer on the server"); return; }
        if (event.code === 4409) { recover("this page is open in another tab"); return; }
        if (event.code === 4403) { console.error("eezo live: refused (origin)"); state("lost"); return; }
        // Server unreachable: retry steadily, forever, capped. Never reload — there is nothing
        // to reload to, and the next attempt that opens will recover on its own.
        retry();
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

    function sendEvent(name, payload) {
      if (socket && socket.readyState === 1) {
        socket.send(JSON.stringify({ kind: "event", name: name, payload: payload }));
      }
    }

    function bound(e, attr) {
      var el = e.target && e.target.closest ? e.target.closest("[" + attr + "]") : null;
      return el && anchor.contains(el) ? el : null;
    }

    /* A control's value as the server expects it: checkboxes and radios follow the form
     * convention ("on" when ticked, "" when not), everything else sends its value. */
    function valueOf(el) {
      if (el.type === "checkbox" || el.type === "radio") return el.checked ? "on" : "";
      return el.value == null ? "" : String(el.value);
    }

    // One delegated listener per event kind, at the document: bindings survive any patch that
    // replaces their element, including a full resync.

    document.addEventListener("click", function (e) {
      var el = bound(e, "data-eezo-click");
      if (!el) return;
      e.preventDefault();
      sendEvent(el.getAttribute("data-eezo-click"), {});
    });

    // Per-keystroke, debounced per element so one field's typing never flushes another's timer.
    var inputTimers = new WeakMap();
    document.addEventListener("input", function (e) {
      var el = bound(e, "data-eezo-input");
      if (!el) return;
      var wait = parseInt(el.getAttribute("data-eezo-debounce") || "300", 10);
      clearTimeout(inputTimers.get(el));
      inputTimers.set(el, setTimeout(function () {
        sendEvent(el.getAttribute("data-eezo-input"), { value: valueOf(el) });
      }, wait));
    });

    // Committed changes go at once: a tick, a pick, a blur-committed edit is a decision.
    document.addEventListener("change", function (e) {
      var el = bound(e, "data-eezo-change");
      if (!el) return;
      sendEvent(el.getAttribute("data-eezo-change"), { value: valueOf(el) });
    });

    document.addEventListener("submit", function (e) {
      var form = bound(e, "data-eezo-submit");
      if (!form) return;
      e.preventDefault();
      /* FormData follows the browser's own rules: named fields only, ticked checkboxes as "on",
       * the selected option's value. Files have no place on this wire and are skipped. */
      var payload = {};
      var data = new FormData(form);
      data.forEach(function (value, name) {
        if (typeof value === "string") payload[name] = value;
      });
      sendEvent(form.getAttribute("data-eezo-submit"), payload);
    });

    connect();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
