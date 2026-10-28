/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Ported from Chimahon (GPL-3.0) assets/dictionary. Emits the Yomitan DOM contract
 * documented in docs/design/yomitan-spec/05-rendering.md (see 02-dictionary-ui.md §3.5).
 * The `chima` prefix was renamed to `komikun` for the fork.
 */
(function() {
  'use strict';

  function resolveKanjiMediaSrc(mediaMap, dictName, path) {
    if (!path || !mediaMap) return null;
    var candidates = [path, path.replace(/\\/g, '/')];
    if (path.startsWith('./')) candidates.push(path.slice(2));
    if (path.startsWith('/')) candidates.push(path.slice(1));
    for (var i = 0; i < candidates.length; i++) {
      var key = (dictName || '') + '\u0000' + candidates[i];
      if (Object.prototype.hasOwnProperty.call(mediaMap, key)) {
        return mediaMap[key];
      }
    }
    return null;
  }

  // ── SVG whitelist ──────────────────────────────────────────────────────────────────────────
  // Kanji definitions come from third-party dictionaries and are shown as inline SVG in a
  // JS-enabled WebView with native bridges attached, so the markup is rebuilt from the whitelist
  // below instead of being assigned as HTML: `innerHTML` does not run <script> but it does fire
  // inline handlers (e.g. `<svg onload=…>`) and loads <foreignObject>. The Kotlin payload boundary
  // (`KanjiSvgSanitizer.kt`) filters too; this copy is the one that holds if the renderer is loaded
  // on its own or served from a cache.
  var SVG_ALLOWED_ELEMENTS = {
    svg: 1, g: 1, defs: 1, symbol: 1, use: 1, path: 1, rect: 1, circle: 1, ellipse: 1, line: 1,
    polyline: 1, polygon: 1, text: 1, tspan: 1, title: 1, desc: 1, clippath: 1, mask: 1, pattern: 1,
    lineargradient: 1, radialgradient: 1, stop: 1
  };
  var SVG_ALLOWED_ATTRIBUTES = {
    xmlns: 1, 'xmlns:xlink': 1, 'xml:space': 1, version: 1, viewbox: 1, preserveaspectratio: 1,
    width: 1, height: 1, x: 1, y: 1,
    x1: 1, x2: 1, y1: 1, y2: 1, dx: 1, dy: 1, cx: 1, cy: 1, r: 1, rx: 1, ry: 1, d: 1, points: 1,
    transform: 1, rotate: 1, fill: 1, 'fill-opacity': 1, 'fill-rule': 1, stroke: 1,
    'stroke-width': 1, 'stroke-linecap': 1, 'stroke-linejoin': 1, 'stroke-dasharray': 1,
    'stroke-dashoffset': 1, 'stroke-opacity': 1, 'stroke-miterlimit': 1, opacity: 1,
    'clip-path': 1, 'clip-rule': 1, mask: 1, 'marker-start': 1, 'marker-mid': 1, 'marker-end': 1,
    'paint-order': 1, 'vector-effect': 1, display: 1, visibility: 1, 'stop-color': 1,
    'stop-opacity': 1, offset: 1, gradientunits: 1, gradienttransform: 1, spreadmethod: 1,
    'text-anchor': 1, 'dominant-baseline': 1, 'alignment-baseline': 1, 'baseline-shift': 1,
    'font-family': 1, 'font-size': 1, 'font-weight': 1, 'font-style': 1, 'font-variant': 1,
    'letter-spacing': 1, 'word-spacing': 1, id: 1, 'class': 1, href: 1, 'xlink:href': 1
  };
  var SVG_LOCAL_URL = /^url\(\s*#[-A-Za-z0-9_.:]+\s*\)$/;
  var SVG_IDENTIFIER = /^[-A-Za-z0-9_.:]+$/;

  // Parsing as `image/svg+xml` (rather than assigning HTML) needs the SVG namespace to be declared:
  // an unnamespaced root is not an SVGSVGElement, and an undeclared `xlink:` prefix is a parse error.
  var SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
  var XLINK_NAMESPACE = 'http://www.w3.org/1999/xlink';

  function isSafeSvgAttributeValue(name, value) {
    var text = String(value);
    var compact = text.replace(/\s+/g, '').toLowerCase();
    if (compact.indexOf('javascript:') !== -1) return false;
    if (compact.indexOf('vbscript:') !== -1) return false;
    if (compact.indexOf('data:text/html') !== -1) return false;
    if (text.indexOf('&#') !== -1) return false;
    if (name === 'href' || name === 'xlink:href') return text.trim().charAt(0) === '#';
    if (name === 'id') return SVG_IDENTIFIER.test(text.trim());
    if (/url\(/i.test(text)) return SVG_LOCAL_URL.test(text.trim());
    return true;
  }

  function sanitizeSvgAttributes(node) {
    var attributes = [];
    for (var i = 0; i < node.attributes.length; i++) attributes.push(node.attributes[i]);
    for (var a = 0; a < attributes.length; a++) {
      var attribute = attributes[a];
      var name = String(attribute.name).toLowerCase();
      if (!Object.prototype.hasOwnProperty.call(SVG_ALLOWED_ATTRIBUTES, name) ||
          !isSafeSvgAttributeValue(name, attribute.value)) {
        node.removeAttributeNode(attribute);
      }
    }
  }

  function sanitizeSvgSubtree(node) {
    var children = [];
    for (var i = 0; i < node.childNodes.length; i++) children.push(node.childNodes[i]);
    for (var c = 0; c < children.length; c++) {
      var child = children[c];
      if (child.nodeType === 3) continue;
      if (child.nodeType !== 1) {
        node.removeChild(child);
        continue;
      }
      var name = String(child.nodeName).toLowerCase();
      if (!Object.prototype.hasOwnProperty.call(SVG_ALLOWED_ELEMENTS, name)) {
        node.removeChild(child);
        continue;
      }
      sanitizeSvgAttributes(child);
      sanitizeSvgSubtree(child);
    }
  }

  // True when [node] is the SVG root the whitelist expects, i.e. it actually landed in the SVG
  // namespace (a root without `xmlns` parses into no namespace at all).
  function isSvgRootElement(node) {
    return !!node && String(node.nodeName).toLowerCase() === 'svg' &&
      node.namespaceURI === SVG_NAMESPACE;
  }

  function parseSvgRoot(markup) {
    if (typeof DOMParser !== 'function') return null;
    try {
      var doc = new DOMParser().parseFromString(markup, 'image/svg+xml');
      if (doc && doc.documentElement && !doc.getElementsByTagName('parsererror').length) {
        return doc.documentElement;
      }
    } catch (e) {}
    return null;
  }

  // Declares the root namespaces the markup relies on but does not declare itself, so a re-parse can
  // land in the SVG (and xlink) namespace instead of failing.
  function withSvgNamespaces(markup) {
    var text = String(markup);
    var rootTag = /<svg(?=[\s/>])[^>]*>/i.exec(text);
    if (!rootTag) return text;
    var tag = rootTag[0];
    var patched = tag;
    if (/xmlns\s*=/i.test(patched)) {
      patched = patched.replace(/(xmlns\s*=\s*)("[^"]*"|'[^']*')/i, '$1"' + SVG_NAMESPACE + '"');
    } else {
      patched = patched.replace(/^<svg/i, '<svg xmlns="' + SVG_NAMESPACE + '"');
    }
    if (/xlink:/i.test(text) && !/xmlns:xlink\s*=/i.test(patched)) {
      patched = patched.replace(/\/?>$/, function(end) {
        return ' xmlns:xlink="' + XLINK_NAMESPACE + '"' + end;
      });
    }
    return text.slice(0, rootTag.index) + patched + text.slice(rootTag.index + tag.length);
  }

  function appendSanitizedSvg(container, markup) {
    var root = parseSvgRoot(markup);
    if (!root || !isSvgRootElement(root)) {
      // Re-parse once with the root namespace declarations injected; only give up after that.
      root = parseSvgRoot(withSvgNamespaces(markup));
    }
    if (!root || !isSvgRootElement(root)) {
      container.textContent = markup;
      return;
    }
    sanitizeSvgAttributes(root);
    sanitizeSvgSubtree(root);
    container.appendChild(document.importNode(root, true));
  }

  function renderKanjiContentOrMedia(container, rawText, dictName, mediaMap) {
    if (rawText == null) return;
    var text = String(rawText).trim();
    if (text.startsWith('<svg') && text.endsWith('</svg>')) {
      appendSanitizedSvg(container, text);
      return;
    }
    if (text.startsWith('data:image/')) {
      var img = document.createElement('img');
      img.src = text;
      img.className = 'gloss-image';
      img.style.maxWidth = '100%';
      container.appendChild(img);
      return;
    }
    var path = '';
    if ((text.startsWith('{') || text.startsWith('[')) && (text.includes('"img"') || text.includes('"image"'))) {
      try {
        var parsed = JSON.parse(text);
        var node = Array.isArray(parsed) ? parsed[0] : parsed;
        if (node && (node.tag === 'img' || node.type === 'image')) {
          path = node.path || node.src || '';
        }
      } catch (e) {}
    } else if (/\.(svg|png|jpg|jpeg|gif|webp)$/i.test(text) && !text.includes('\n')) {
      path = text;
    }

    if (path) {
      var link = document.createElement('span');
      link.className = 'gloss-image-link';
      link.dataset.path = path;
      link.dataset.dictName = dictName || '';
      link.dataset.imageLoadState = 'unloaded';

      var linkImg = document.createElement('img');
      linkImg.className = 'gloss-image';
      linkImg.loading = 'lazy';
      linkImg.style.maxWidth = '100%';

      var resolved = resolveKanjiMediaSrc(mediaMap, dictName, path);
      if (resolved) {
        linkImg.src = resolved;
        link.dataset.imageLoadState = 'loaded';
      }
      link.appendChild(linkImg);
      container.appendChild(link);
      return;
    }

    container.textContent = text;
  }

  function createEntryArticle(character, entry, mediaMap) {
    var article = document.createElement('article');
    article.className = 'entry kanji-entry';

    // ── Glyph ──
    var glyphRow = document.createElement('div');
    glyphRow.className = 'entry-body-section entry-headword-row';

    var glyph = document.createElement('span');
    glyph.className = 'kanji-glyph';
    glyph.textContent = character;
    glyphRow.appendChild(glyph);

    var iconGroup = document.createElement('div');
    iconGroup.className = 'entry-icon-group';
    glyphRow.appendChild(iconGroup);
    article.appendChild(glyphRow);

    // ── Body ──
    var body = document.createElement('div');
    body.className = 'entry-body';
    article.appendChild(body);

    // ── Dictionary tag ──
    var tagList = document.createElement('div');
    tagList.className = 'kanji-tag-list';
    var tag = document.createElement('span');
    tag.className = 'tag';
    tag.dataset.category = 'dictionary';
    var inner = document.createElement('span');
    inner.className = 'tag-label';
    var content = document.createElement('span');
    content.className = 'tag-label-content';
    content.textContent = entry.dictName || 'Unknown';
    inner.appendChild(content);
    tag.appendChild(inner);
    tagList.appendChild(tag);
    body.appendChild(tagList);

    // ── Definitions (meanings) ──
    var definitions = Array.isArray(entry.definitions) ? entry.definitions : [];
    if (definitions.length > 0) {
      var glossHeader = document.createElement('div');
      glossHeader.className = 'kanji-section-header';
      glossHeader.textContent = 'Meanings';
      body.appendChild(glossHeader);

      var glossList = document.createElement('ol');
      glossList.className = 'kanji-gloss-list';
      for (var di = 0; di < definitions.length; di++) {
        var li = document.createElement('li');
        li.className = 'kanji-gloss-item';
        var gc = document.createElement('div');
        gc.className = 'kanji-gloss-content';
        renderKanjiContentOrMedia(gc, definitions[di], entry.dictName, mediaMap);
        li.appendChild(gc);
        glossList.appendChild(li);
      }
      body.appendChild(glossList);
    }

    // ── Readings ──
    var onyomi = Array.isArray(entry.onyomi) ? entry.onyomi : (typeof entry.onyomi === 'string' && entry.onyomi ? entry.onyomi.trim().split(/\s+/) : []);
    var kunyomi = Array.isArray(entry.kunyomi) ? entry.kunyomi : (typeof entry.kunyomi === 'string' && entry.kunyomi ? entry.kunyomi.trim().split(/\s+/) : []);
    if (onyomi.length > 0 || kunyomi.length > 0) {
      var readingsHeader = document.createElement('div');
      readingsHeader.className = 'kanji-section-header';
      readingsHeader.textContent = 'Readings';
      body.appendChild(readingsHeader);

      var readingsContainer = document.createElement('div');
      readingsContainer.className = 'kanji-readings';
      body.appendChild(readingsContainer);

      if (onyomi.length > 0) {
        var onyomiSection = document.createElement('dl');
        onyomiSection.className = 'kanji-readings-chinese';
        for (var oi = 0; oi < onyomi.length; oi++) {
          var reading = document.createElement('span');
          reading.className = 'kanji-reading';
          reading.textContent = onyomi[oi];
          onyomiSection.appendChild(reading);
          if (oi < onyomi.length - 1) {
            onyomiSection.appendChild(document.createTextNode(', '));
          }
        }
        readingsContainer.appendChild(onyomiSection);
      }

      if (kunyomi.length > 0) {
        var kunyomiSection = document.createElement('dl');
        kunyomiSection.className = 'kanji-readings-japanese';
        for (var ki = 0; ki < kunyomi.length; ki++) {
          var reading = document.createElement('span');
          reading.className = 'kanji-reading';
          reading.textContent = kunyomi[ki];
          kunyomiSection.appendChild(reading);
          if (ki < kunyomi.length - 1) {
            kunyomiSection.appendChild(document.createTextNode(', '));
          }
        }
        readingsContainer.appendChild(kunyomiSection);
      }
    }

    // ── Stats ──
    var stats = entry.stats;
    var statEntries = typeof stats === 'object' && stats !== null ? Object.entries(stats) : [];
    if (statEntries.length > 0) {
      var statsHeader = document.createElement('div');
      statsHeader.className = 'kanji-section-header';
      statsHeader.textContent = 'Statistics';
      body.appendChild(statsHeader);

      var statsTable = document.createElement('table');
      statsTable.className = 'kanji-info-table';
      var tbody = document.createElement('tbody');
      statsTable.appendChild(tbody);
      for (var si = 0; si < statEntries.length; si++) {
        var stat = statEntries[si];
        var tr = document.createElement('tr');
        tr.className = 'kanji-info-table-item';
        var nameCell = document.createElement('td');
        nameCell.className = 'kanji-info-table-item-header';
        nameCell.textContent = stat[0];
        var valueCell = document.createElement('td');
        valueCell.className = 'kanji-info-table-item-value';
        renderKanjiContentOrMedia(valueCell, stat[1], entry.dictName, mediaMap);
        tr.appendChild(nameCell);
        tr.appendChild(valueCell);
        tbody.appendChild(tr);
      }
      body.appendChild(statsTable);
    }

    return article;
  }

  window.KanjiRenderer = {
    render: function(kanjiResult, mediaMap) {
      if (!kanjiResult) return;
      var container = document.getElementById('entries');
      if (!container) return;

      var character = kanjiResult.character || '';
      var entries = Array.isArray(kanjiResult.entries) ? kanjiResult.entries : [];

      for (var ei = 0; ei < entries.length; ei++) {
        container.appendChild(createEntryArticle(character, entries[ei], mediaMap));
      }
    },

    renderEntry: function(kanjiData, character, mediaMap) {
      return createEntryArticle(character, kanjiData, mediaMap);
    },

    clear: function() {
      var container = document.getElementById('entries');
      if (!container) return;
      var kanjiEntries = container.querySelectorAll('.kanji-entry');
      for (var i = kanjiEntries.length - 1; i >= 0; i--) {
        kanjiEntries[i].remove();
      }
    }
  };
})();
