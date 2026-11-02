/* core.js - የ UI መሠረት: DOM ረዳት፣ API ጥሪ፣ ቋንቋ፣ ገጽታ፣ modal፣ marquee፣ router።
   ሁሉም ጽሁፍ በ textContent ነው የሚገባው (innerHTML በቋሚ SVG ብቻ) - የ AI ውጤት ውስጥ ያለ ተንኮል ስክሪፕት መሥራት አይችልም። */
'use strict';
(function () {
  const A = (window.A = { S: { state: null, strings: {}, lang: 'EN' }, screens: {} });

  /* ---------------------------------------------------------------- i18n */
  A.t = function t(key, params) {
    const L = A.S.strings[A.S.lang] || {};
    const E = A.S.strings.EN || {};
    let s = L[key];
    if (s === undefined) s = E[key];
    if (s === undefined) s = key;
    if (params) s = s.replace(/\{(\w+)\}/g, (m, k) => (params[k] !== undefined ? params[k] : m));
    return s;
  };
  const t = A.t;

  /* ---------------------------------------------------------------- DOM helper */
  function append(el, kids) {
    for (const k of kids.flat(Infinity)) {
      if (k === null || k === undefined || k === false) continue;
      el.append(k.nodeType ? k : document.createTextNode(String(k)));
    }
  }
  const PROPS = new Set(['value', 'checked', 'disabled', 'selected']);
  A.h = function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') {
        for (const [sk, sv] of Object.entries(v)) {
          if (sk.startsWith('--')) el.style.setProperty(sk, sv);
          else el.style[sk] = sv;
        }
      } else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (PROPS.has(k)) el[k] = v;
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    }
    append(el, kids);
    return el;
  };
  const h = A.h;
  A.clear = (el) => { while (el.firstChild) el.removeChild(el.firstChild); return el; };
  A.sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  const ICONS = {
    dots: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="5" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="19" r="1.6" fill="currentColor"/></svg>',
    edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/></svg>',
    image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="1.8"/><path d="M21 16l-5-5-8 9"/></svg>',
    camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
    info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v6"/><circle cx="12" cy="7.6" r="1" fill="currentColor"/></svg>',
    plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
    save: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 4h11l3 3v13H5z"/><path d="M8 4v5h7V4M8 20v-6h8v6"/></svg>',
    folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h6l2 2h10v11H3z"/></svg>',
    trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"/></svg>',
    copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 012-2h9"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 11a8 8 0 10-2.3 5.7"/><path d="M20 4v7h-7"/></svg>',
    back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>',
    sliders: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M5 4v16M12 4v16M19 4v16"/><path d="M3 9h4M10 15h4M17 8h4"/></svg>',
    chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 5h16v11H9l-5 4z"/><path d="M8 9.5h8M8 12.5h5"/></svg>',
    book: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 4h11a3 3 0 013 3v13H8a3 3 0 01-3-3z"/><path d="M5 17a3 3 0 013-3h11"/></svg>',
    sparkle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"/></svg>',
    flag: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 21V4M5 4h11l-2 4 2 4H5"/></svg>',
    chev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>',
    exam: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h7M9 17h5"/></svg>',
    quiz: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 9l1.6 1.6L12.5 7.5M8 16l1.6 1.6 2.9-3.1M15 10h2M15 17h2"/></svg>',
    note: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 12h7M9 16h7"/></svg>',
    chart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h16M7 17v-5M12 17V6M17 17v-8"/></svg>',
    star: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2.8l2.8 5.9 6.4.9-4.7 4.5 1.2 6.4L12 17.4 6.3 20.5l1.2-6.4L2.8 9.6l6.4-.9z"/></svg>',
    phone: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 4h4l2 5-2.5 1.5a11 11 0 005 5L15 13l5 2v4a2 2 0 01-2 2A16 16 0 013 6a2 2 0 012-2z"/></svg>',
  };
  const TOK = /([DI])(\d+)/;
  const INLINE = new RegExp([
    '(`[^`\\n]+`)',
    '(\\uE000[DI]\\d+\\uE001)',
    '(\\*\\*\\*[^*\\n]+?\\*\\*\\*)',
    '(\\*\\*[^*\\n]+?\\*\\*)',
    '(__[^_\\n]+?__)',
    '(~~[^~\\n]+?~~)',
    '(\\*[^*\\s][^*\\n]*?\\*)',
    '((?<![\\w])_[^_\\s][^_\\n]*?_(?![\\w]))',
    '(!?\\[[^\\]\\n]*\\]\\([^)\\s]+(?:\\s+"[^"]*")?\\))',
    '(https?:\\/\\/[^\\s<>()]+)',
    '(<br\\s*\\/?>)',
    '(<(?:sup|sub)>[^<\\n]{1,40}<\\/(?:sup|sub)>)',
  ].join('|'));
  let MATHS = [];
  function renderMath(tex, display) {
    const box = h(display ? 'div' : 'span', { class: display ? 'math-d' : 'math-i' });
    try {
      if (!window.katex) throw new Error('no katex');
      window.katex.render(tex, box, { displayMode: display, throwOnError: false, strict: 'ignore', trust: false, maxSize: 20, maxExpand: 500 });
    } catch (e) { box.textContent = tex; box.classList.add('math-raw'); }
    return box;
  }
  function inline(parent, s) {
    for (;;) {
      const m = INLINE.exec(s);
      if (!m) break;
      if (m.index) parent.append(document.createTextNode(s.slice(0, m.index)));
      const tok = m[0];
      let node;
      if (tok[0] === '`') node = h('code', null, tok.slice(1, -1));
      else if (tok[0] === '') { const q = TOK.exec(tok); const mm = MATHS[Number(q[2])]; node = mm ? renderMath(mm.tex, q[1] === 'D') : document.createTextNode(''); }
      else if (tok.startsWith('***')) { node = h('strong'); const e = h('em'); inline(e, tok.slice(3, -3)); node.append(e); }
      else if (tok.startsWith('**') || tok.startsWith('__')) { node = h('strong'); inline(node, tok.slice(2, -2)); }
      else if (tok.startsWith('~~')) { node = h('del'); inline(node, tok.slice(2, -2)); }
      else if (tok[0] === '*' || tok[0] === '_') { node = h('em'); inline(node, tok.slice(1, -1)); }
      else if (tok.startsWith('![')) { node = h('span', { class: 'lnk' }, tok.slice(2, tok.indexOf(']'))); }
      else if (tok[0] === '[') {
        const label = tok.slice(1, tok.indexOf(']('));
        node = h('span', { class: 'lnk' }); inline(node, label);
      }
      else if (tok.startsWith('http')) node = h('span', { class: 'lnk' }, tok);
      else if (tok.startsWith('<br')) node = document.createElement('br');
      else { const tag = tok.slice(1, 4); node = h(tag); node.textContent = tok.slice(5, tok.length - 6); }
      parent.append(node);
      s = s.slice(m.index + tok.length);
    }
    if (s) parent.append(document.createTextNode(s));
  }
  const isBlockMath = (ln) => /^\s*D\d+\s*$/.test(ln);
  function lines(parent, arr) {
    arr.forEach((ln, i) => {
      if (i && !isBlockMath(ln) && !isBlockMath(arr[i - 1])) parent.append(document.createElement('br'));
      inline(parent, ln);
    });
  }
  function extractMath(text) {
    MATHS = [];
    const keep = (tex, display) => { MATHS.push({ tex: tex.trim() }); return '' + (display ? 'D' : 'I') + (MATHS.length - 1) + ''; };
    const re = /(`[^`\n]*`)|\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|(?<![\\$\w])\$(?![\s$])([^$\n]+?)(?<![\s\\])\$(?![\d\w$])/g;
    const parts = text.split(/(^|\n)([ \t]*```[\s\S]*?(?:\n[ \t]*```|$))/);
    let out = '';
    for (let i = 0; i < parts.length; i++) {
      if (i % 3 === 2) { out += parts[i]; continue; }
      if (i % 3 === 1) { out += parts[i]; continue; }
      out += parts[i].replace(re, (all, code, d1, d2, i1, i2) => {
        if (code) return code;
        if (d1 !== undefined) return keep(d1, true);
        if (d2 !== undefined) return keep(d2, true);
        if (i1 !== undefined) return keep(i1, false);
        return keep(i2, false);
      });
    }
    return out;
  }
  const LISTM = /^(\s*)([-*+•]|\d+[.)])\s+(.*)$/;
  function buildList(items) {
    const stack = [];
    const make = (it) => {
      const l = h(it.ordered ? 'ol' : 'ul');
      if (it.ordered && it.num > 1) l.setAttribute('start', it.num);
      return l;
    };
    let rootEl = null;
    items.forEach((it) => {
      while (stack.length && it.indent < stack[stack.length - 1].indent) stack.pop();
      let cur = stack[stack.length - 1];
      if (!cur || it.indent > cur.indent) {
        const l = make(it);
        if (!cur) rootEl = rootEl || l;
        else cur.lastLi.append(l);
        cur = { indent: it.indent, list: l, lastLi: null, ordered: it.ordered };
        stack.push(cur);
      }
      const li = h('li');
      const task = /^\[([ xX])\]\s+/.exec(it.text);
      let txt = it.text;
      if (task) { li.classList.add('task'); li.append(h('span', { class: 'cb' + (task[1] === ' ' ? '' : ' on') }, task[1] === ' ' ? '' : '✓')); txt = txt.slice(task[0].length); }
      const body = h('span', { class: 'li-b' });
      lines(body, [txt].concat(it.more));
      li.append(body);
      cur.list.append(li);
      cur.lastLi = li;
    });
    return rootEl;
  }
  function codeBlock(lang, code) {
    const copy = h('button', { class: 'code-copy', type: 'button', 'aria-label': t('act_copy') }, A.icon('copy'));
    copy.addEventListener('click', () => A.copy(code));
    return h('div', { class: 'md-code' },
      h('div', { class: 'code-bar' }, h('span', null, lang || ''), copy),
      h('pre', null, h('code', null, code)));
  }
  A.md = function md(el, text) {
    el.textContent = '';
    el.classList.add('md');
    return blocks(el, extractMath(String(text || '').replace(/\r\n?/g, '\n')).split('\n'));
  };
  function blocks(el, src) {
    let i = 0;
    while (i < src.length) {
      const ln = src[i];
      let m;
      if ((m = /^\s*```\s*([\w+#.-]*)/.exec(ln))) {
        const buf = []; i++;
        while (i < src.length && !/^\s*```/.test(src[i])) buf.push(src[i++]);
        i++;
        el.append(codeBlock(m[1], buf.join('\n')));
        continue;
      }
      if (!ln.trim()) { i++; continue; }
      if (isBlockMath(ln)) { inline(el, ln.trim()); i++; continue; }
      if ((m = /^\s{0,3}(#{1,6})\s+(.*)$/.exec(ln))) {
        const hd = h('h' + Math.min(6, m[1].length + 2), { class: 'md-h' });
        inline(hd, m[2].replace(/\s*#+\s*$/, ''));
        el.append(hd); i++; continue;
      }
      if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(ln)) { el.append(document.createElement('hr')); i++; continue; }
      if (/^\s*>/.test(ln)) {
        const buf = [];
        while (i < src.length && /^\s*>/.test(src[i])) buf.push(src[i++].replace(/^\s*>\s?/, ''));
        const q = h('blockquote'); blocks(q, buf); el.append(q); continue;
      }
      if (/^\s*\|/.test(ln)) {
        const rows = [];
        while (i < src.length && /^\s*\|/.test(src[i])) rows.push(src[i++]);
        const cells = (r) => r.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
        const data = rows.filter((r) => !(/^\s*\|?[\s:|-]+\|?\s*$/.test(r) && /-/.test(r)));
        const tb = h('table');
        data.forEach((r, ri) => {
          const tr = h('tr');
          cells(r).forEach((c) => { const td = h(ri === 0 && rows.length > 1 ? 'th' : 'td'); inline(td, c); tr.append(td); });
          tb.append(tr);
        });
        el.append(h('div', { class: 'md-tw' }, tb)); continue;
      }
      if (LISTM.test(ln)) {
        const items = [];
        while (i < src.length) {
          const l = src[i];
          if (!l.trim()) {
            let k = i + 1; while (k < src.length && !src[k].trim()) k++;
            if (k < src.length && (LISTM.test(src[k]) || /^\s{2,}\S/.test(src[k]))) { i = k; continue; }
            break;
          }
          const mm = LISTM.exec(l);
          if (mm) { items.push({ indent: mm[1].replace(/\t/g, '    ').length, ordered: /\d/.test(mm[2]), num: parseInt(mm[2], 10) || 1, text: mm[3], more: [] }); i++; continue; }
          if (/^\s{2,}\S/.test(l) && items.length) { items[items.length - 1].more.push(l.trim()); i++; continue; }
          break;
        }
        el.append(buildList(items)); continue;
      }
      const buf = [];
      while (i < src.length && src[i].trim() && !/^\s*(```|#{1,6}\s|>|\||([-*+•]|\d+[.)])\s)/.test(src[i]) && !isBlockMath(src[i])) buf.push(src[i++].trim());
      if (!buf.length) { buf.push(src[i++].trim()); }
      const p = h('p'); lines(p, buf); el.append(p);
    }
    return el;
  }

  A.copy = async function copy(text) {
    try { await navigator.clipboard.writeText(text); }
    catch (e) {
      const ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.append(ta); ta.select();
      try { document.execCommand('copy'); } catch (e2) { }
      ta.remove();
    }
    A.toast(t('copied'), 1600);
  };
  A.pickImage = function pickImage(kind) {
    return new Promise((resolve) => {
      if (!window.AlphaBridge || !window.AlphaBridge.pickImage) return resolve({ ok: false, message: t('err_server') });
      A._imgPicked = (r) => { A._imgPicked = null; resolve(r); };
      window.AlphaBridge.pickImage(kind);
    });
  };
  A.icon = (name) => { const s = h('span', { class: 'ico', 'aria-hidden': 'true' }); s.innerHTML = ICONS[name] || ''; return s; };

  /* ---------------------------------------------------------------- API */
  class ApiError extends Error {
    constructor(status, data) { super((data && data.message) || 'error'); this.status = status; this.data = data || {}; }
  }
  A.ApiError = ApiError;
  // Android: ጥሪዎች በ Kotlin ድልድይ (AlphaBridge) ይሄዳሉ። በኮምፒውተር ሙከራ ላይ ወደ fetch ይመለሳል።
  // Android: calls go through the Kotlin bridge; falls back to fetch when testing in a desktop browser.
  const pending = {}; let nextId = 1;
  A._bridgeDone = function (id, raw) {
    const cb = pending[id]; delete pending[id];
    if (cb) cb(raw);
  };
  A.api = async function api(method, path, body) {
    if (window.AlphaBridge) {
      const raw = await new Promise((resolve) => {
        const id = nextId++;
        pending[id] = resolve;
        window.AlphaBridge.call(id, method, path, JSON.stringify(body || {}));
      });
      let r = { status: 500, data: {} };
      try { r = JSON.parse(raw); } catch (e) { /* ባዶ */ }
      if (r.status >= 400) throw new ApiError(r.status, r.data);
      return r.data;
    }
    const opt = { method, cache: 'no-store', headers: { 'X-Alpha': '1' } };
    if (method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body || {}); }
    let res;
    try { res = await fetch(path, opt); } catch (e) { throw new ApiError(0, { message: t('err_local_server') }); }
    let data = {};
    try { data = await res.json(); } catch (e) { /* ባዶ */ }
    if (!res.ok) throw new ApiError(res.status, data);
    return data;
  };
  A.get = (path, params) => A.api('GET', params ? path + '?' + new URLSearchParams(params) : path);
  A.post = (path, body) => A.api('POST', path, body);

  /* ረጅም ሥራ (job) እስኪጠናቀቅ polling */
  A.poll = function poll(jobId, onUpdate) {
    return new Promise((resolve, reject) => {
      const tick = async () => {
        try {
          const j = await A.get('/api/job', { id: jobId });
          if (onUpdate) onUpdate(j);
          if (j.state === 'done') return resolve(j);
          if (j.state === 'error') return reject(new Error(j.error || t('err_server')));
        } catch (e) { return reject(e); }
        setTimeout(tick, 600);
      };
      tick();
    });
  };

  /* የማውረድ ሁኔታ ክትትል (ሞዴል፣ መጽሐፍ፣ ፈተና) - አንድ ጊዜ አንድ ማውረድ ብቻ */
  A.watchDownload = function watchDownload(onSnap, interval = 700) {
    let stopped = false, last = '';
    const tick = async () => {
      if (stopped) return;
      try {
        const s = await A.get('/api/download');
        onSnap(s);
        last = s.state;
      } catch (e) { /* ጊዜያዊ ስህተት: ይቀጥላል */ }
      if (!stopped && last === 'running') setTimeout(tick, interval);
    };
    tick();
    return { stop() { stopped = true; }, kick() { if (!stopped && last !== 'running') { last = 'running'; tick(); } } };
  };

  /* ---------------------------------------------------------------- theme (Global Theme Engine) */
  // ቀለም ማደባለቂያ / small color helpers
  const hex2rgb = (x) => { const n = parseInt(x.replace('#', ''), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const mixHex = (a, b, k) => { const A1 = hex2rgb(a), B1 = hex2rgb(b); return '#' + A1.map((v, i) => Math.round(v + (B1[i] - v) * k).toString(16).padStart(2, '0')).join(''); };
  A.applyTheme = function applyTheme(p) {
    if (!p) return;
    A._palette = p;
    const r = document.documentElement.style;
    const [R, G, B] = hex2rgb(p.primary);
    r.setProperty('--primary', p.primary);
    r.setProperty('--dark', p.dark);
    r.setProperty('--primary-2', mixHex(p.primary, '#ffffff', 0.32));   // የቅልመት ሁለተኛ ቀለም
    r.setProperty('--tint', 'rgba(' + R + ',' + G + ',' + B + ',.16)');  // ለማንኛውም ዳራ የሚስማማ ግልጽ ቀለም
    const m = document.querySelector('meta[name="theme-color"]');
    if (m) m.setAttribute('content', p.primary);
  };
  // የብርሃን / ጨለማ / የስልክ ራስ-ሰር ሁነታ
  A.applyMode = function applyMode(mode) {
    mode = mode === 'light' || mode === 'dark' ? mode : 'device';
    A._mode = mode;
    const mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    const eff = mode === 'device' ? (mq && mq.matches ? 'dark' : 'light') : mode;
    document.documentElement.setAttribute('data-mode', eff);
    if (mq && !A._mqBound) { A._mqBound = true; const f = () => { if (A._mode === 'device') A.applyMode('device'); }; mq.addEventListener ? mq.addEventListener('change', f) : mq.addListener(f); }
  };

  /* ---------------------------------------------------------------- marquee (ህግ 3) */
  A.mq = (text, cls) => h('span', { class: 'mq' + (cls ? ' ' + cls : ''), title: text }, h('span', { class: 'mq-in' }, text));
  A.refreshMarquees = function refreshMarquees() {
    document.querySelectorAll('.mq').forEach((el) => {
      const inn = el.firstElementChild;
      if (!inn) return;
      el.classList.remove('run');
      const over = inn.offsetWidth - el.clientWidth;
      if (over > 2) {
        el.style.setProperty('--mq-dist', -(over + 10) + 'px');
        el.style.setProperty('--mq-dur', Math.max(3, over / 35 + 2).toFixed(1) + 's');
        el.classList.add('run');
      }
    });
  };
  let mqTimer = null;
  A.scheduleMarquees = () => { clearTimeout(mqTimer); mqTimer = setTimeout(() => requestAnimationFrame(A.refreshMarquees), 60); };
  window.addEventListener('resize', A.scheduleMarquees);

  /* ---------------------------------------------------------------- modal / confirm / toast / loading */
  const layer = () => document.getElementById('layer');
  A.modal = function modal({ title, body, actions = [], dismiss = false }) {
    const btns = actions.map((a) => h('button', { class: 'btn ' + (a.kind || ''), type: 'button', disabled: !!a.disabled }, a.label));
    const bodyEl = h('div', { class: 'dlg-b' }, body);
    const dlg = h('div', { class: 'dlg', role: 'dialog', 'aria-modal': 'true' },
      title ? h('h2', { class: 'dlg-t' }, title) : null, bodyEl, btns.length ? h('div', { class: 'dlg-a' }, btns) : null);
    const back = h('div', { class: 'backdrop' }, dlg);
    let closed = false;
    const api = {
      el: dlg, bodyEl, btn: (i) => btns[i],
      close() { if (closed) return; closed = true; back.remove(); if (api.onClose) api.onClose(); },
    };
    actions.forEach((a, i) => btns[i].addEventListener('click', async () => {
      if (a.onClick) { const r = await a.onClick(api); if (r === false) return; }
      if (!a.keepOpen) api.close();
    }));
    if (dismiss) back.addEventListener('click', (e) => { if (e.target === back) api.close(); });
    layer().append(back);
    const first = btns[btns.length - 1] || null;
    if (first) first.focus({ preventScroll: true });
    A.scheduleMarquees();
    return api;
  };

  /* ህግ 6: Yes / No ማረጋገጫ - ተስፋው true (አዎ) ወይም false (አይ) */
  A.confirm = (message, title) => new Promise((resolve) => {
    A.modal({
      title: title || t('confirm_title'), body: message,
      actions: [
        { label: t('no'), kind: 'ghost', onClick: () => resolve(false) },
        { label: t('yes'), kind: 'primary', onClick: () => resolve(true) },
      ],
    });
  });
  A.info = (message, title) => new Promise((resolve) => {
    const m = A.modal({ title: title || t('notice_title'), body: message, actions: [{ label: t('ok'), kind: 'primary' }] });
    m.onClose = resolve;
  });
  A.toast = (message, ms = 3200) => {
    const el = h('div', { class: 'toast', role: 'status' }, message);
    layer().append(el);
    setTimeout(() => el.remove(), ms);
  };

  /* የመጫኛ አኒሜሽን: የሚሽከረከር ክብ (CSS) እና የሰከንድ ቆጣሪ */
  A.loading = function loading(message, title) {
    let secs = 0, base = message;
    const spin = h('div', { class: 'spin', 'aria-hidden': 'true' });
    const msg = h('div', { class: 'center' }, base);
    const m = A.modal({ title: title || t('ai_loading_title'), body: h('div', null, spin, msg) });
    const sv = setInterval(() => { secs += 1; msg.textContent = base + ' (' + secs + 's)'; }, 1000);
    const stop = () => { clearInterval(sv); };
    const ctl = {
      set(text) { base = text; msg.textContent = text; },
      close() { stop(); m.close(); },
      fail(text, onClose) {
        stop();
        spin.textContent = '!'; spin.classList.add('bad');
        msg.textContent = text;
        m.el.querySelector('.dlg-t').textContent = t('error_title');
        const b = h('button', { class: 'btn', type: 'button' }, t('close'));
        b.addEventListener('click', () => { m.close(); if (onClose) onClose(); });
        m.el.append(h('div', { class: 'dlg-a' }, b));
        b.focus();
      },
    };
    return ctl;
  };

  A.tabs = function tabs(items, active, onChange) {
    const el = h('div', { class: 'tabs', role: 'tablist' });
    const draw = (cur) => {
      A.clear(el);
      items.forEach(([id, label]) => el.append(h('button', {
        class: 'tab', type: 'button', role: 'tab', 'aria-selected': String(id === cur),
        onclick: () => { draw(id); onChange(id); },
      }, label)));
    };
    draw(active);
    return el;
  };

  A.fmtSize = (mb) => (mb === null || mb === undefined ? '' : mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : (mb < 10 ? Number(mb).toFixed(1) : Math.round(mb)) + ' MB');
  A.page = (bar, ...content) => h('section', null, bar, ...content);
  A.bar = function bar({ title, back = true, left = [], right = [], pts = true }) {
    if (pts && A.ads) right = [A.ads.pill(), ...right];
    return h('header', { class: 'bar' },
      back ? h('button', { class: 'bar-btn', type: 'button', 'aria-label': t('back_btn'), onclick: () => A.back() }, A.icon('back')) : null,
      ...left,
      h('div', { class: 'bar-t' }, A.mq(title)),
      ...right);
  };

  /* ---------------------------------------------------------------- router */
  const appEl = () => document.getElementById('app');
  let stack = [];
  const leaveOk = async (entry) => (entry && entry.scr.canLeave ? !!(await entry.scr.canLeave()) : true);
  function destroy(entry) {
    try { if (entry.scr.onLeave) entry.scr.onLeave(); } catch (e) { /* ችላ */ }
    entry.scr.el.remove();
  }
  function mount(name, params, dir) {
    const scr = A.screens[name](params || {});
    scr.el.classList.add('screen', dir === 'back' ? 'enter-back' : 'enter');
    appEl().append(scr.el);
    if (scr.onShow) scr.onShow();
    A.scheduleMarquees();
    return { name, params: params || {}, scr };
  }
  A.go = async function go(name, params, opts) {
    opts = opts || {};
    const cur = stack[stack.length - 1];
    if (cur && !opts.force && !(await leaveOk(cur))) return false;
    if (cur) destroy(cur);
    if (opts.root) stack = [];
    else if (opts.replace) stack.pop();
    stack.push(mount(name, params, 'fwd'));
    if (A.ads && name !== 'splash' && name !== 'ai' && name !== 'quizplay' && name !== 'examrun' && name !== 'examresult' && name !== 'examreview' && !opts.root && !opts.replace) A.ads.breakPoint();
    return true;
  };
  A.back = async function back() {
    if (stack.length < 2) return false;
    const cur = stack[stack.length - 1];
    if (!(await leaveOk(cur))) return false;
    destroy(cur);
    stack.pop();
    const prev = stack.pop();
    stack.push(mount(prev.name, prev.params, 'back'));
    return true;
  };
  /* ቋንቋ ሲቀየር አሁን ያለውን ስክሪን እንደገና ይሳላል (የፎርም እሴት ከ getParams ይጠበቃል) */
  A.rerender = function rerender() {
    const cur = stack[stack.length - 1];
    if (!cur) return;
    const params = cur.scr.getParams ? cur.scr.getParams() : cur.params;
    destroy(cur);
    stack.pop();
    const e = mount(cur.name, params, 'fwd');
    e.scr.el.classList.remove('enter');
    stack.push(e);
  };
  A.current = () => stack[stack.length - 1];

  A.setLang = async function setLang(code) {
    const r = await A.post('/api/settings', { language: code });
    A.S.lang = r.settings.language;
    A.S.state.settings = r.settings;
    document.documentElement.lang = A.S.lang === 'AM' ? 'am' : 'en';
    A.rerender();
  };

  /* የ Android Back ቁልፍ / አሳሽ Back: ሁልጊዜ ወደ አፑ ውስጣዊ back ይቀየራል */
  A.installBackHandler = function installBackHandler() {
    history.replaceState({ root: 1 }, '');
    history.pushState({ app: 1 }, '');
    window.addEventListener('popstate', async () => {
      history.pushState({ app: 1 }, '');
      const cur = stack[stack.length - 1];
      if (!cur || cur.name === 'splash') return;
      if (await A.back()) return;
      if (await A.confirm(t('confirm_exit'))) {
        try { await A.post('/api/exit'); } catch (e) { /* ችላ */ }
      }
    });
  };
})();
