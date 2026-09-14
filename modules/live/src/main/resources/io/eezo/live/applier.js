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
 *
 * Patches are applied in frame order; the differ guarantees each path is valid at its turn
 * (updates before removals, removals highest-index-first, appends last — see Patch.scala).
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

  /* The DOM's own spelling of what a node is, matching the differ's `expect`: a lowercase tag
   * name for an element, "#text" for a text node. */
  function nameOf(node) {
    if (node.nodeType === 1) return node.nodeName.toLowerCase();
    if (node.nodeType === 3) return "#text";
    return "#" + node.nodeType;
  }

  /* Parses markup the way the server rendered it: all resulting nodes, not firstChild. A
   * <template> parses any content, including table parts, without a wrapping document. */
  function parsed(document, html) {
    var template = document.createElement("template");
    template.innerHTML = html;
    return template.content;
  }

  /* For value/checked/selected/disabled the attribute is only the initial state: once the user
   * has touched the control, the property is what the page shows, and patching the attribute
   * alone changes nothing visible (design/live.md §1.1 on §4.6). The attribute is still written
   * by the caller, so the DOM serializes correctly; this syncs the live property beside it. */
  function syncProperty(el, name, value, present) {
    if (!(name in el)) return;
    if (name === "value") el.value = present ? value : "";
    else if (name === "checked" || name === "selected" || name === "disabled") el[name] = present;
  }

  /* Applies a patch list to the DOM under `anchor`. Returns an array of failures, empty on full
   * success; each failure carries the patch index and a reason. The caller decides what a
   * non-empty return means (in the page client: report and resync). Patches after a failed one
   * are still attempted: applying what can be applied leaves less of the page stale while the
   * resync is on its way. */
  function applyPatches(anchor, patches) {
    var failures = [];

    function refuse(i, reason) {
      failures.push({ index: i, reason: reason });
    }

    for (var i = 0; i < patches.length; i++) {
      var p = patches[i];
      var target = resolve(anchor, p.path);
      var at = "[" + p.path.join(",") + "]";

      if (!target) {
        refuse(i, p.op + ": path " + at + " resolves to nothing");
        continue;
      }
      // The integrity check: setText admits one node kind by definition; everything else carries
      // the node name it expects, with null meaning "the anchor itself" (found by id, not by
      // walking, so there is nothing to verify).
      if (p.op === "setText") {
        if (target.nodeType !== 3) {
          refuse(i, "setText: expected a text node at " + at + ", found " + nameOf(target));
          continue;
        }
      } else if (p.expect !== null && p.expect !== undefined && nameOf(target) !== p.expect) {
        refuse(i, p.op + ": expected <" + p.expect + "> at " + at + ", found " + nameOf(target));
        continue;
      }

      switch (p.op) {
        case "setText":
          target.data = p.text;
          break;
        case "setAttr":
          target.setAttribute(p.name, p.value);
          syncProperty(target, p.name, p.value, true);
          break;
        case "removeAttr":
          target.removeAttribute(p.name);
          syncProperty(target, p.name, "", false);
          break;
        case "replaceNode":
          target.replaceWith(parsed(target.ownerDocument, p.html));
          break;
        case "removeNode":
          target.remove();
          break;
        case "appendChildren":
          target.append(parsed(target.ownerDocument, p.html));
          break;
        case "setChildren":
          if (target.nodeType !== 1) {
            refuse(i, "setChildren: target at " + at + " is not an element");
            break;
          }
          target.replaceChildren(parsed(target.ownerDocument, p.html));
          break;
        default:
          refuse(i, "unknown op '" + p.op + "'");
      }
    }
    return failures;
  }

  exports.applyPatches = applyPatches;
})(typeof module !== "undefined" && module.exports ? module.exports : (window.EezoLive = window.EezoLive || {}));
