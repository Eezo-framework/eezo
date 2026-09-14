/* eezo live: the patch applier.
 *
 * This file is the client half of the invariant in design/live.md: applying a patch list to a DOM
 * holding the old render produces the DOM of the new render. It is written to run in two places
 * unchanged: the browser (loaded by the live client, M3) and node + jsdom (the round-trip harness
 * in research/harnesses/live-roundtrip), which is why it exports onto `module.exports` when that
 * exists and onto `window.EezoLive` otherwise.
 *
 * The one rule: no patch is ever silently dropped. A patch that cannot be resolved, fails its
 * integrity check, or names an unknown op is recorded as a failure and returned to the caller,
 * who reports it to the server and asks for a resync (M4). The `if (!node) return;` shape that
 * turns addressing bugs into silence is banned here by construction: every early exit appends to
 * `failures` first.
 */
(function (exports) {
  "use strict";

  /* Walks a childNodes path from the anchor. Counting is over childNodes, all of them: the server
   * tree is canonical (fragments spliced, adjacent text merged, empty text dropped, no
   * whitespace-only nodes emitted), so tree child index equals DOM child index. Returns null when
   * the path walks off the DOM; the caller records that as a failure, never as a no-op. */
  function resolve(anchor, path) {
    var node = anchor;
    for (var i = 0; i < path.length; i++) {
      node = node.childNodes[path[i]];
      if (!node) return null;
    }
    return node;
  }

  /* Parses markup the way the server rendered it: all resulting nodes, not firstChild. A
   * <template> parses any content, including table parts, without a wrapping document. */
  function parsed(document, html) {
    var template = document.createElement("template");
    template.innerHTML = html;
    return template.content;
  }

  function setChildren(target, html) {
    target.replaceChildren(parsed(target.ownerDocument, html));
  }

  /* Applies a patch list to the DOM under `anchor`. Returns an array of failures, empty on full
   * success; each failure carries the patch index and a reason. The caller decides what a
   * non-empty return means (in the page client: report and resync). Patches after a failed one
   * are still attempted: their paths are independent addresses from the anchor, and applying what
   * can be applied leaves less of the page stale while the resync is on its way. */
  function applyPatches(anchor, patches) {
    var failures = [];
    for (var i = 0; i < patches.length; i++) {
      var patch = patches[i];
      var target = resolve(anchor, patch.path);
      if (!target) {
        failures.push({ index: i, reason: "path [" + patch.path.join(",") + "] resolves to nothing" });
        continue;
      }
      if (patch.expect !== null && patch.expect !== undefined) {
        var found = target.nodeType === 1 ? target.nodeName.toLowerCase() : "#" + target.nodeType;
        if (found !== patch.expect) {
          failures.push({ index: i, reason: "expected <" + patch.expect + "> at [" + patch.path.join(",") + "], found " + found });
          continue;
        }
      }
      switch (patch.op) {
        case "setChildren":
          if (target.nodeType !== 1) {
            failures.push({ index: i, reason: "setChildren target at [" + patch.path.join(",") + "] is not an element" });
            break;
          }
          setChildren(target, patch.html);
          break;
        default:
          failures.push({ index: i, reason: "unknown op '" + patch.op + "'" });
      }
    }
    return failures;
  }

  exports.applyPatches = applyPatches;
})(typeof module !== "undefined" && module.exports ? module.exports : (window.EezoLive = window.EezoLive || {}));
