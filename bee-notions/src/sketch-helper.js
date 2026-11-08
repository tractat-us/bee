// The script every Sketch frame runs first, inlined into its srcdoc by sketch.js as
// `(<this function's source>)()`. So it must be self-contained: it may name nothing outside its
// own body, and its source must never hold a closing script tag.
//
// It speaks the MCP Apps (SEP-1865) View-to-Host envelope, JSON-RPC 2.0 over postMessage: a click
// on a `[data-choice]` element is a `ui/message` request whose text is the element's text, and
// whose `_meta` names the choice. It also keeps the brainstorming companion's page API
// (`toggleSelect`, `window.brainstorm`), so a companion screen behaves as it does on the
// companion server.

/** Runs inside the sandboxed frame. */
export function sketchHelper() {
  var CHOICE_META = 'us.tractat.bee/choice';
  var nextId = 1;

  function request(method, params) {
    // The frame's origin is opaque (no allow-same-origin), so the parent can't be named: '*'.
    window.parent.postMessage({ jsonrpc: '2.0', id: nextId++, method: method, params: params }, '*');
  }

  function choose(choice, text) {
    var meta = {};
    meta[CHOICE_META] = String(choice);
    request('ui/message', { role: 'user', content: { type: 'text', text: String(text) }, _meta: meta });
  }

  document.addEventListener('click', function (event) {
    var target = event.target && event.target.closest ? event.target.closest('[data-choice]') : null;
    if (!target) return;
    choose(target.dataset.choice, target.textContent.trim());
  });

  // The companion frame's selection helpers, as its helper.js defines them.
  window.selectedChoice = null;
  window.toggleSelect = function (el) {
    var container = el.closest('.options') || el.closest('.cards');
    var multi = container && container.dataset.multiselect !== undefined;
    if (container && !multi) {
      container.querySelectorAll('.option, .card').forEach(function (o) { o.classList.remove('selected'); });
    }
    if (multi) el.classList.toggle('selected');
    else el.classList.add('selected');
    window.selectedChoice = el.dataset.choice;
  };
  window.brainstorm = {
    send: function (event) {
      if (event && event.choice !== undefined) choose(event.choice, event.text !== undefined ? event.text : event.choice);
    },
    choice: function (value, metadata) {
      choose(value, metadata && metadata.text !== undefined ? metadata.text : value);
    },
  };

  // The frame's status pill, when the screen has one: it's on the board, not a companion server.
  document.addEventListener('DOMContentLoaded', function () {
    var status = document.querySelector('.status');
    if (status) status.textContent = 'On bee';
  });
}
