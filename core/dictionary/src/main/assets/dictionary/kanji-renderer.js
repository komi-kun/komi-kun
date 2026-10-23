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

  function renderKanjiContentOrMedia(container, rawText, dictName, mediaMap) {
    if (rawText == null) return;
    var text = String(rawText).trim();
    if (text.startsWith('<svg') && text.endsWith('</svg>')) {
      container.innerHTML = text;
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
