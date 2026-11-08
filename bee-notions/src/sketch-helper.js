// The script every Sketch frame runs first, inlined into its srcdoc by sketch.js as
// `(<this function's source>)()`. So it must be self-contained: it may name nothing outside its
// own body, and its source must never hold a closing script tag.
//
// It speaks the MCP Apps (SEP-1865) View-to-Host envelope, JSON-RPC 2.0 over postMessage: a click
// on a `[data-choice]` element is a `ui/message` request whose text is the element's text, and
// whose `_meta` names the choice. It reports the document's height as a
// `ui/notifications/size-changed` notification, so the page can fit the frame to it. It also keeps
// the brainstorming companion's page API (`toggleSelect`, `window.brainstorm`), so a companion
// screen behaves as it does on the companion server.

/** Runs inside the sandboxed frame. */
export function sketchHelper() {
  var CHOICE_META = 'us.tractat.bee/choice';
  var nextId = 1;

  // WebRTC sits outside CSP (bee#49): delete its constructors before the sketch's own script runs.
  // A speed bump, not a boundary: a nested srcdoc frame has its own `RTCPeerConnection`, and a
  // closed declarative shadow root hides that frame even from a helper that removes nested frames.
  // (A nested about:blank frame is no way back: it counts as cross-origin, and throws.)
  ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel'].forEach(function (name) {
    try { delete window[name]; } catch (e) { /* not configurable here */ }
  });

  function request(method, params) {
    // The frame's origin is opaque (no allow-same-origin), so the parent can't be named: '*'.
    window.parent.postMessage({ jsonrpc: '2.0', id: nextId++, method: method, params: params }, '*');
  }

  // Each choice still waiting for the page's answer, by request id: the element clicked (or null,
  // for the page API), its choice, and whether it was selected before the click.
  var choices = {};
  var latestChoice = null;

  // Proposes a choice: the page shows it outside the frame, and records it only on the viewer's
  // Send there (bee#60). The page answers once it's sent, dismissed or replaced.
  function choose(choice, text, el, wasSelected) {
    var meta = {};
    meta[CHOICE_META] = String(choice);
    latestChoice = nextId;
    choices[nextId] = { el: el || null, choice: String(choice), wasSelected: !!wasSelected };
    request('ui/message', { role: 'user', content: { type: 'text', text: String(text) }, _meta: meta });
  }

  function isMulti(el) {
    var container = el.closest('.options') || el.closest('.cards');
    return !!container && container.dataset.multiselect !== undefined;
  }

  // A choice the page didn't send leaves the frame as it was before that click, so what the frame
  // shows selected is what was sent, plus the latest proposal. In a multiselect container, a
  // replaced or dismissed click is undone. Otherwise only a dismissed latest choice is, since
  // toggleSelect has already moved the selection to the later one. Options already sent keep theirs.
  window.addEventListener('message', function (event) {
    var reply = event.data;
    if (event.source !== window.parent || !reply || reply.jsonrpc !== '2.0' || !choices[reply.id]) return;
    var sent = choices[reply.id];
    delete choices[reply.id];
    if (!reply.error) return;
    var latest = reply.id === latestChoice;
    if (sent.el && isMulti(sent.el)) {
      var later = latest ? null : choices[latestChoice];
      // A later click on the same element takes over undoing to the state before this one.
      if (later && later.el === sent.el) later.wasSelected = sent.wasSelected;
      else sent.el.classList.toggle('selected', sent.wasSelected);
    } else if (latest) {
      var shown = sent.el ? [sent.el] : document.querySelectorAll('[data-choice]');
      Array.prototype.forEach.call(shown, function (el) {
        if (el.dataset.choice === sent.choice) el.classList.remove('selected');
      });
      window.selectedChoice = null;
    }
  });

  function choiceOf(event) {
    return event.target && event.target.closest ? event.target.closest('[data-choice]') : null;
  }
  // Capture runs before the element's own onclick (toggleSelect), so it sees the selection
  // as it was before this click.
  var selectedBefore = new WeakMap();
  document.addEventListener('click', function (event) {
    var target = choiceOf(event);
    if (target) selectedBefore.set(target, target.classList.contains('selected'));
  }, true);
  document.addEventListener('click', function (event) {
    var target = choiceOf(event);
    if (!target) return;
    choose(target.dataset.choice, target.textContent.trim(), target, selectedBefore.get(target));
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

  // The document's height, so the page can fit the frame to it: an MCP Apps
  // `ui/notifications/size-changed` notification whenever it changes, a few at most per frame's
  // worth of layout. The root's box holds the body's margins; the body's scroll height catches
  // content that overflows it.
  var reportedHeight = -1;
  var sizeTimer = null;
  // Content sized in `vh` follows the frame's height, so a fit to it changes it again, and every
  // fit would shrink the next report, down to the floor. So a resize of the frame's height alone,
  // with nothing in the document changed and its width the same, reports nothing.
  var contentChanged = true;
  var frameWidth = innerWidth;
  var frameHeight = innerHeight;
  function contentChange() {
    contentChanged = true;
    scheduleSize();
  }
  function scrolls(el) {
    var overflow = getComputedStyle(el).overflowY;
    return overflow === 'auto' || overflow === 'scroll';
  }
  // A document that hides its own overflow pins itself to the frame and scrolls inside, as the
  // companion's frame template does (`html, body { height: 100%; overflow: hidden }`, then a
  // scrolling `.main`): its root and body are always exactly the frame's height. So measure what
  // it holds: collapse the root and body for one synchronous layout, take what the body still
  // holds, add what each scrolling element inside holds past its own box, and put them back.
  // Nothing paints in between, and a ResizeObserver hears only the restored size.
  function pinnedHeight(root, body) {
    var rootStyle = root.style.cssText;
    var bodyStyle = body.style.cssText;
    [root, body].forEach(function (el) {
      el.style.setProperty('height', '0', 'important');
      el.style.setProperty('min-height', '0', 'important');
    });
    var margins = getComputedStyle(body);
    var height = body.scrollHeight + parseFloat(margins.marginTop) + parseFloat(margins.marginBottom);
    Array.prototype.forEach.call(body.querySelectorAll('*'), function (el) {
      if (scrolls(el)) height += Math.max(0, el.scrollHeight - el.clientHeight);
    });
    root.style.cssText = rootStyle;
    body.style.cssText = bodyStyle;
    // This measurement's own style changes aren't the document changing.
    changes.takeRecords();
    return height;
  }
  function documentHeight() {
    var root = document.documentElement;
    var body = document.body;
    if (!body) return Math.ceil(root.getBoundingClientRect().height);
    if (getComputedStyle(root).overflowY === 'hidden' || getComputedStyle(body).overflowY === 'hidden') {
      return Math.ceil(pinnedHeight(root, body));
    }
    return Math.ceil(Math.max(root.getBoundingClientRect().height, body.scrollHeight));
  }
  function reportSize() {
    sizeTimer = null;
    var frameOnly = !contentChanged && innerWidth === frameWidth && innerHeight !== frameHeight;
    contentChanged = false;
    frameWidth = innerWidth;
    frameHeight = innerHeight;
    if (frameOnly) return;
    var height = documentHeight();
    if (height === reportedHeight) return;
    reportedHeight = height;
    window.parent.postMessage({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { height: height } }, '*');
  }
  function scheduleSize() {
    if (sizeTimer === null) sizeTimer = setTimeout(reportSize, 50);
  }
  new ResizeObserver(scheduleSize).observe(document.documentElement);
  // A pinned root never changes size, so a ResizeObserver on it hears nothing of what it holds:
  // any change to the document re-measures too.
  var changes = new MutationObserver(contentChange);
  changes.observe(document.documentElement, { subtree: true, childList: true, attributes: true, characterData: true });
  window.addEventListener('load', contentChange);
  if (document.fonts) document.fonts.addEventListener('loadingdone', contentChange);

  // The frame's status pill, when the screen has one: it's on the board, not a companion server.
  document.addEventListener('DOMContentLoaded', function () {
    var status = document.querySelector('.status');
    if (status) status.textContent = 'On bee';
  });
}
