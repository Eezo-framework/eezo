/* Syntax colouring, by highlight.js, for every code block on the page: once after the page is
 * parsed, and again for any block the live layer replaces. The server patches the DOM with the
 * markup it rendered, which carries no colouring, so a block that arrives in a patch (a whole-tree
 * resync after a lost frame, say) is coloured here as the first ones were. A coloured block carries
 * the `hljs` class the library adds, which is how the ones already done are skipped. */
(function () {
  function colour(root) {
    var blocks = root.querySelectorAll("pre code:not(.hljs):not(.nohighlight)");
    for (var i = 0; i < blocks.length; i++) hljs.highlightElement(blocks[i]);
  }
  var pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      colour(document);
    });
  }
  colour(document);
  new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      if (mutations[i].addedNodes.length > 0) { schedule(); return; }
    }
  }).observe(document.body, { childList: true, subtree: true });
})();
