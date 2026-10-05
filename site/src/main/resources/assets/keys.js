/* The keyboard, which the live layer cannot hear. Ctrl-K or Cmd-K presses the header's search
 * button and Escape presses the dialog's close button, so both go through the same live events a
 * click would; the input is focused once the server's patch has put it on the page. Nothing here
 * talks to the server. */
(function () {
  if (/Mac|iPhone|iPad/.test(navigator.platform || "")) document.documentElement.classList.add("mac");
  function focusSearch(tries) {
    var input = document.querySelector("[data-search-input]");
    if (input) { input.focus(); input.select(); return; }
    if (tries > 0) setTimeout(function () { focusSearch(tries - 1); }, 50);
  }
  document.addEventListener("click", function (e) {
    if (e.target.closest && e.target.closest("[data-search-open]")) focusSearch(40);
  });
  document.addEventListener("keydown", function (e) {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === "k" || e.key === "K")) {
      e.preventDefault();
      var open = document.querySelector("[data-search-open]");
      if (document.querySelector("[data-search-input]")) focusSearch(0);
      else if (open) open.click();
    } else if (e.key === "Escape") {
      var close = document.querySelector("[data-search-close]");
      if (close) close.click();
    }
  });
})();
