// Injected into a Gemini share page. Defines window.__geminiExporter, which finds the
// conversation turns and converts each to clean HTML (for the PDF) and Markdown.
// Images are replaced by {{GEIMG:n}} tokens; the app downloads them and fills them in.
(function () {
  'use strict';
  if (window.__geminiExporter && window.__geminiExporter.version === 1) return;

  var USER_SELECTOR = 'user-query, .user-query-container, [data-test-id="user-query"]';
  var MODEL_SELECTOR = 'model-response, response-container, [data-test-id="model-response"]';
  var SKIP_TAGS = {
    BUTTON: 1, 'MAT-ICON': 1, SVG: 1, STYLE: 1, SCRIPT: 1, NOSCRIPT: 1, TEMPLATE: 1,
    INPUT: 1, TEXTAREA: 1, SELECT: 1, IFRAME: 1, CANVAS: 1, 'MODEL-THOUGHTS': 1
  };
  // Matched against single class names, so a wrapper like "response-has-thoughts" is kept.
  var SKIP_CLASS = /^(.*visually-hidden.*|screen-reader.*|sr-only|thoughts?(-.*)?|code-block-decoration|table-footer|response-footer.*|message-actions.*|copy-button.*)$/i;
  var KEEP_TAGS = {
    P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, STRONG: 1, B: 1,
    EM: 1, I: 1, U: 1, S: 1, DEL: 1, BLOCKQUOTE: 1, TABLE: 1, THEAD: 1, TBODY: 1, TFOOT: 1,
    TR: 1, TH: 1, TD: 1, HR: 1, BR: 1, SUP: 1, SUB: 1, MARK: 1, CAPTION: 1
  };

  // Per-run state. Markdown code is parked in these arrays behind placeholder characters
  // so the whitespace cleanup in finishMd() cannot damage it.
  var images, imageIndex, codeBlocks, inlineCodes;

  function reset() {
    images = [];
    imageIndex = new Map();
    codeBlocks = [];
    inlineCodes = [];
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function cls(el) {
    return (el.getAttribute && el.getAttribute('class')) || '';
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function isSkipped(el) {
    if (SKIP_TAGS[el.tagName]) return true;
    if (el.hasAttribute('hidden')) return true;
    var names = el.classList || [];
    for (var i = 0; i < names.length; i++) {
      if (SKIP_CLASS.test(names[i])) return true;
    }
    var st = window.getComputedStyle(el);
    return !!st && (st.display === 'none' || st.visibility === 'hidden');
  }

  function isBlock(el) {
    var d = window.getComputedStyle(el).display || '';
    return d !== '' && d.indexOf('inline') !== 0 && d !== 'contents' && d !== 'none';
  }

  function visibleWithin(node, root) {
    for (var e = node; e && e !== root; e = e.parentElement) {
      if (isSkipped(e)) return false;
    }
    return true;
  }

  function outermost(list) {
    return list.filter(function (el) {
      return !list.some(function (other) { return other !== el && other.contains(el); });
    });
  }

  function absUrl(href) {
    try { return new URL(href, location.href).href; } catch (e) { return href; }
  }

  // ---- images ----

  function imageSrc(img) {
    return img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') || '';
  }

  function isContentImage(img) {
    if (img.closest('button')) return false;
    var parent = img.parentElement;
    if (/avatar|logo|icon|favicon|profile/i.test(cls(img) + ' ' + (parent ? cls(parent) : ''))) return false;
    var src = imageSrc(img);
    if (!src) return false;
    if (/^data:image\/(gif|svg)/i.test(src) && src.length < 500) return false;
    var rect = img.getBoundingClientRect();
    var w = Math.max(img.naturalWidth || 0, rect.width || 0);
    var h = Math.max(img.naturalHeight || 0, rect.height || 0);
    // Source favicons and similar decorations are tiny; unloaded lazy images report 0.
    if (w > 0 && h > 0 && w < 48 && h < 48) return false;
    return true;
  }

  function imageToken(img) {
    var src = absUrl(imageSrc(img));
    var index = imageIndex.get(src);
    if (index === undefined) {
      index = images.length;
      images.push({ src: src, el: img });
      imageIndex.set(src, index);
    }
    return '{{GEIMG:' + index + '}}';
  }

  function blobToDataUrl(blob) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(reader.error); };
      reader.readAsDataURL(blob);
    });
  }

  // blob: URLs only live inside this page, so they have to be read here.
  async function inlineBlobImage(entry) {
    if (!/^blob:/i.test(entry.src)) return;
    try {
      var response = await fetch(entry.src);
      entry.src = await blobToDataUrl(await response.blob());
      return;
    } catch (e) { /* fall back to the canvas below */ }
    try {
      var img = entry.el;
      if (img.complete && img.naturalWidth) {
        var canvas = document.createElement('canvas');
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        canvas.getContext('2d').drawImage(img, 0, 0);
        entry.src = canvas.toDataURL('image/png');
      }
    } catch (e) { /* leave the blob URL; the app reports it as missing */ }
  }

  // ---- shared helpers ----

  function mathOf(el) {
    var tex = el.getAttribute('data-math');
    if (tex === null) return null;
    return { tex: tex, block: /math-block|display/i.test(cls(el)) || el.tagName === 'DIV' };
  }

  function codeOf(pre) {
    var code = pre.querySelector('code') || pre;
    var lang = '';
    var m = /(?:^|\s)language-([\w+#.-]+)/.exec(cls(code));
    if (m) lang = m[1];
    if (!lang) {
      var block = pre.closest('code-block, .code-block');
      var deco = block && block.querySelector('.code-block-decoration');
      if (deco) {
        var copy = deco.cloneNode(true);
        Array.prototype.forEach.call(copy.querySelectorAll('button, mat-icon'), function (n) { n.remove(); });
        var label = (copy.textContent || '').replace(/\s+/g, ' ').trim();
        if (label.length < 30) lang = label;
      }
    }
    return { lang: lang, text: (code.textContent || '').replace(/\n+$/, '') };
  }

  // ---- HTML ----

  function htmlKids(el) {
    var out = '';
    for (var n = el.firstChild; n; n = n.nextSibling) out += toHtml(n);
    return out;
  }

  function toHtml(node) {
    if (node.nodeType === 3) return esc(node.nodeValue);
    if (node.nodeType !== 1) return '';
    var el = node;
    var tag = el.tagName;
    if (isSkipped(el)) return '';
    var math = mathOf(el);
    if (math) {
      return math.block ? '<pre class="math">' + esc(math.tex) + '</pre>' : '<code class="math">' + esc(math.tex) + '</code>';
    }
    if (tag === 'IMG') return isContentImage(el) ? '<img src="' + imageToken(el) + '" alt="">' : '';
    if (tag === 'PRE') {
      var c = codeOf(el);
      return '<div class="code">' + (c.lang ? '<div class="lang">' + esc(c.lang) + '</div>' : '') +
        '<pre><code>' + esc(c.text) + '</code></pre></div>';
    }
    if (tag === 'CODE') return '<code>' + esc(el.textContent) + '</code>';
    if (tag === 'A') {
      var href = absUrl(el.getAttribute('href') || '');
      var inner = htmlKids(el);
      return /^(https?|mailto):/i.test(href) ? '<a href="' + esc(href) + '">' + inner + '</a>' : inner;
    }
    if (KEEP_TAGS[tag]) {
      var name = tag.toLowerCase();
      if (tag === 'BR' || tag === 'HR') return '<' + name + '>';
      var attrs = '';
      if (tag === 'OL' && el.getAttribute('start')) attrs += ' start="' + (parseInt(el.getAttribute('start'), 10) || 1) + '"';
      if (tag === 'TD' || tag === 'TH') {
        if (el.colSpan > 1) attrs += ' colspan="' + el.colSpan + '"';
        if (el.rowSpan > 1) attrs += ' rowspan="' + el.rowSpan + '"';
      }
      return '<' + name + attrs + '>' + htmlKids(el) + '</' + name + '>';
    }
    var content = htmlKids(el);
    return isBlock(el) ? '<div>' + content + '</div>' : content;
  }

  // ---- Markdown ----
  // \u0001 = indentation that must survive cleanup, \u0002 = hard line break,
  // \u0003n\u0003 = fenced code block n, \u0004n\u0004 = inline code n.

  function mdKids(el) {
    var out = '';
    for (var n = el.firstChild; n; n = n.nextSibling) out += toMd(n);
    return out;
  }

  function wrap(s, mark) {
    if (!s.trim()) return s;
    var lead = /^\s*/.exec(s)[0];
    var trail = /\s*$/.exec(s)[0];
    return lead + mark + s.trim() + mark + trail;
  }

  function tidy(s) {
    return s.replace(/\n{3,}/g, '\n\n').trim();
  }

  function mdList(el) {
    var ordered = el.tagName === 'OL';
    var n = parseInt(el.getAttribute('start'), 10);
    if (isNaN(n)) n = 1;
    var items = [];
    Array.prototype.forEach.call(el.children, function (li) {
      if (li.tagName !== 'LI' || isSkipped(li)) return;
      var marker = ordered ? (n++) + '. ' : '- ';
      var pad = '\u0001'.repeat(marker.length);
      var lines = tidy(mdKids(li)).split('\n').map(function (line) { return line.replace(/^ +/, ''); });
      items.push(marker + lines.map(function (line, i) {
        return i === 0 || !line ? line : pad + line;
      }).join('\n'));
    });
    return items.join('\n');
  }

  function mdTable(el) {
    var rows = Array.prototype.slice.call(el.rows || []).filter(function (r) { return !isSkipped(r); });
    if (!rows.length) return '';
    var cells = rows.map(function (r) {
      return Array.prototype.map.call(r.cells, function (c) {
        return mdKids(c).trim().replace(/\s*\n\s*/g, '<br>').replace(/\|/g, '\\|');
      });
    });
    var width = Math.max.apply(null, cells.map(function (r) { return r.length; }));
    function line(r) {
      var out = [];
      for (var i = 0; i < width; i++) out.push(r[i] || '');
      return '| ' + out.join(' | ') + ' |';
    }
    var out = [line(cells[0]), '|' + ' --- |'.repeat(width)];
    for (var i = 1; i < cells.length; i++) out.push(line(cells[i]));
    return out.join('\n');
  }

  function toMd(node) {
    if (node.nodeType === 3) return node.nodeValue.replace(/\s+/g, ' ');
    if (node.nodeType !== 1) return '';
    var el = node;
    var tag = el.tagName;
    if (isSkipped(el)) return '';
    var math = mathOf(el);
    if (math) return math.block ? '\n\n$$\n' + math.tex + '\n$$\n\n' : '$' + math.tex + '$';
    switch (tag) {
      case 'IMG':
        return isContentImage(el) ? '![](' + imageToken(el) + ')' : '';
      case 'BR':
        return '\u0002\n';
      case 'HR':
        return '\n\n---\n\n';
      case 'PRE': {
        var c = codeOf(el);
        var fence = c.text.indexOf('```') >= 0 ? '~~~~' : '```';
        codeBlocks.push(fence + c.lang.toLowerCase() + '\n' + c.text + '\n' + fence);
        return '\n\n\u0003' + (codeBlocks.length - 1) + '\u0003\n\n';
      }
      case 'CODE': {
        var t = el.textContent;
        inlineCodes.push(t.indexOf('`') >= 0 ? '`` ' + t + ' ``' : '`' + t + '`');
        return '\u0004' + (inlineCodes.length - 1) + '\u0004';
      }
      case 'STRONG': case 'B':
        return wrap(mdKids(el), '**');
      case 'EM': case 'I':
        return wrap(mdKids(el), '*');
      case 'S': case 'DEL':
        return wrap(mdKids(el), '~~');
      case 'A': {
        var inner = mdKids(el);
        var href = absUrl(el.getAttribute('href') || '');
        if (!/^https?:/i.test(href) || !inner.trim()) return inner;
        return '[' + inner.trim() + '](' + href.replace(/ /g, '%20').replace(/\)/g, '%29') + ')';
      }
      case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6':
        return '\n\n' + '#'.repeat(+tag.charAt(1)) + ' ' + mdKids(el).replace(/\s+/g, ' ').trim() + '\n\n';
      case 'P':
        return '\n\n' + mdKids(el).trim() + '\n\n';
      case 'BLOCKQUOTE':
        return '\n\n' + tidy(mdKids(el)).split('\n').map(function (l) { return '> ' + l; }).join('\n') + '\n\n';
      case 'UL': case 'OL':
        return '\n\n' + mdList(el) + '\n\n';
      case 'TABLE':
        return '\n\n' + mdTable(el) + '\n\n';
    }
    var content = mdKids(el);
    return isBlock(el) ? '\n\n' + content + '\n\n' : content;
  }

  function finishMd(s) {
    s = s.replace(/[ \t ]+/g, ' ')
      .replace(/ ?\n ?/g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
    s = s.replace(/^([>\u0001 ]*)\u0003(\d+)\u0003$/gm, function (m, prefix, n) {
      return codeBlocks[+n].split('\n').map(function (l) { return prefix + l; }).join('\n');
    });
    s = s.replace(/\u0003(\d+)\u0003/g, function (m, n) { return codeBlocks[+n]; });
    s = s.replace(/\u0004(\d+)\u0004/g, function (m, n) { return inlineCodes[+n]; });
    return s.replace(/\u0001/g, ' ').replace(/\u0002/g, '  ');
  }

  // ---- conversation structure ----

  function findTurns() {
    var nodes = outermost(Array.prototype.slice.call(document.querySelectorAll(USER_SELECTOR + ',' + MODEL_SELECTOR)));
    return nodes
      .filter(function (n) { return visibleWithin(n, document.body); })
      .map(function (n) { return { role: n.matches(USER_SELECTOR) ? 'user' : 'model', el: n }; });
  }

  // Converts the text roots of a turn, plus any images that sit outside them
  // (uploaded pictures above a prompt, generated pictures under an answer).
  function convertTurn(el, rootSelector, imagesFirst) {
    var roots = outermost(Array.prototype.slice.call(el.querySelectorAll(rootSelector))
      .filter(function (r) { return visibleWithin(r, el); }));
    if (!roots.length) return { html: toHtml(el), md: finishMd(toMd(el)) };

    var imgHtml = '';
    var imgMd = '';
    Array.prototype.forEach.call(el.querySelectorAll('img'), function (img) {
      if (roots.some(function (r) { return r.contains(img); })) return;
      if (!visibleWithin(img, el) || !isContentImage(img)) return;
      var token = imageToken(img);
      imgHtml += '<img src="' + token + '" alt="">';
      imgMd += '\n\n![](' + token + ')\n\n';
    });
    var textHtml = roots.map(toHtml).join('');
    var textMd = roots.map(toMd).join('\n\n');
    return imagesFirst
      ? { html: imgHtml + textHtml, md: finishMd(imgMd + textMd) }
      : { html: textHtml + imgHtml, md: finishMd(textMd + imgMd) };
  }

  function chatTitle() {
    var selectors = ['[data-test-id="share-title"]', '.share-title', '.conversation-title', 'h1'];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      var text = el && (el.innerText || '').trim();
      if (text && text.length <= 200 && !/^(google )?gemini$/i.test(text)) return text;
    }
    var title = (document.title || '').replace(/[‎‏]/g, '')
      .replace(/\s*[-|–—]\s*(Google )?Gemini.*$/i, '')
      .replace(/^(Google )?Gemini\s*[-|:–—]\s*/i, '')
      .trim();
    return /^(google )?gemini$/i.test(title) ? '' : title;
  }

  // Scrolls every scrollable area once so lazily loaded pictures get a real src.
  async function preload() {
    var scrollers = Array.prototype.filter.call(document.querySelectorAll('*'), function (e) {
      return e.scrollHeight > e.clientHeight + 100 && /auto|scroll/.test(window.getComputedStyle(e).overflowY);
    });
    scrollers.sort(function (a, b) { return b.scrollHeight - a.scrollHeight; });
    scrollers = scrollers.slice(0, 3);
    scrollers.push(document.scrollingElement || document.documentElement);
    for (var i = 0; i < scrollers.length; i++) {
      var s = scrollers[i];
      var start = s.scrollTop;
      var step = Math.max(200, s.clientHeight * 0.8);
      for (var y = 0, k = 0; y < s.scrollHeight && k < 80; y += step, k++) {
        s.scrollTop = y;
        await sleep(60);
      }
      s.scrollTop = start;
    }
    await sleep(300);
  }

  async function run() {
    var deadline = Date.now() + 15000;
    while (!findTurns().length && Date.now() < deadline) await sleep(500);
    await preload();
    reset();

    var turns = findTurns().map(function (t) {
      var parts = t.role === 'user'
        ? convertTurn(t.el, '.query-text', true)
        : convertTurn(t.el, 'message-content, .markdown', false);
      return { role: t.role, html: parts.html, md: parts.md };
    }).filter(function (t) { return t.md.trim() || t.html.indexOf('<img') >= 0; });

    var mode = 'chat';
    if (!turns.length) {
      mode = 'page';
      var root = document.querySelector('main') || document.body;
      turns = [{ role: 'page', html: toHtml(root), md: finishMd(toMd(root)) }];
    }
    for (var i = 0; i < images.length; i++) await inlineBlobImage(images[i]);

    return JSON.stringify({
      title: chatTitle(),
      url: location.href,
      mode: mode,
      turns: turns,
      images: images.map(function (e) { return e.src; })
    });
  }

  var api = {
    version: 1,
    running: false,
    result: null,
    error: null,
    count: function () {
      try { return findTurns().length; } catch (e) { return -1; }
    },
    start: function () {
      if (api.running) return;
      api.running = true;
      api.result = null;
      api.error = null;
      run().then(function (json) {
        api.result = json;
        api.running = false;
      }, function (e) {
        api.error = String((e && e.message) || e);
        api.running = false;
      });
    },
    // null while running, then the JSON result or "ERR:<message>".
    poll: function () {
      if (api.error) return 'ERR:' + api.error;
      return api.result;
    }
  };
  window.__geminiExporter = api;
})();
