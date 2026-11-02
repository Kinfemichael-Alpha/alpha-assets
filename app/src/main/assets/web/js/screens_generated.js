/* screens_generated.js - Generated Questions and Generated Notes. */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;
  const cache = {};

  A.screens.generated = function generated() {
    const card = (icon, label, hint, go) => h('button', { class: 'card subj', type: 'button', onclick: go },
      A.icon(icon), h('div', { class: 'grow' }, h('div', { class: 'h-sec' }, label), h('div', { class: 'muted small' }, hint)), A.icon('chev'));
    return {
      el: A.page(A.bar({ title: t('gen_title') }), h('div', { class: 'scroll ruled stack' },
        card('exam', t('gen_questions'), t('gen_q_intro'), () => A.go('exams', { kind: 'generated' })),
        card('note', t('gen_notes'), t('gen_notes_intro'), () => A.go('gennotes')))),
    };
  };

  const pick = (o, keys) => { for (const k of keys) { const v = o[k]; if (v !== undefined && v !== null && v !== '' && !(Array.isArray(v) && !v.length)) return v; } return ''; };
  const BODY = ['note', 'notes', 'short_note', 'summary', 'content', 'body', 'text', 'explanation', 'en_explanation', 'description', 'definition'];
  const AM = ['am_explanation', 'amharic_explanation', 'explanation_am', 'amharic'];
  const asText = (v) => (Array.isArray(v) ? v.map((x) => '- ' + (typeof x === 'string' ? x : JSON.stringify(x))).join('\n') : typeof v === 'string' ? v : v ? JSON.stringify(v) : '');

  function flatten(root, subject) {
    const out = [];
    const walk = (node, group) => {
      if (Array.isArray(node)) { node.forEach((x) => walk(x, group)); return; }
      if (!node || typeof node !== 'object') return;
      const body = pick(node, BODY), am = pick(node, AM);
      if (body || am) {
        const title = String(pick(node, ['title', 'subtopic', 'name', 'heading', 'concept', 'topic']) || group || subject);
        const topic = String(pick(node, ['topic', 'unit', 'chapter', 'category', 'section']) || group || subject || '');
        const kw = pick(node, ['keywords', 'tags']);
        out.push({ title, topic, body: asText(body), am: asText(am), kw: Array.isArray(kw) ? kw.join(' ') : String(kw || '') });
        return;
      }
      const g = String(pick(node, ['topic', 'unit', 'chapter', 'title', 'name']) || group || '');
      Object.keys(node).forEach((k) => { if (node[k] && typeof node[k] === 'object') walk(node[k], g); });
    };
    walk(root, '');
    out.forEach((n) => { n.hay = (n.title + ' ' + n.topic + ' ' + n.kw + ' ' + n.body + ' ' + n.am).toLowerCase(); n.th = (n.title + ' ' + n.topic + ' ' + n.kw).toLowerCase(); });
    return out;
  }

  A.screens.gennotes = function gennotes() {
    const body = h('div', { class: 'stack' });
    let data = null;
    async function load(refresh) {
      try { data = await A.get('/api/exams/index', refresh ? { kind: 'notes', refresh: 'true' } : { kind: 'notes' }); } catch (e) { A.clear(body).append(h('div', { class: 'card warn' }, h('p', null, e.message))); return; }
      draw();
    }
    function draw() {
      A.clear(body);
      if (data.offline) body.append(h('div', { class: 'card tint' }, h('p', { class: 'muted' }, t('exams_offline'))));
      const list = data.exams || [];
      if (!list.length) { body.append(h('div', { class: 'card' }, h('p', { class: 'muted' }, t('gen_empty')))); return; }
      body.append(h('p', { class: 'muted' }, t('gen_notes_intro')));
      list.forEach((e) => body.append(h('button', { class: 'card subj', type: 'button', onclick: () => A.go('gennotelist', { entry: e }) },
        h('div', { class: 'grow' }, h('div', { class: 'h-sec' }, (e.grade ? t('exams_grade', { g: e.grade }) + ' · ' : '') + (e.subject || e.title)),
          e.subject && e.title && e.title !== e.subject ? h('div', { class: 'muted small' }, e.title) : null), A.icon('chev'))));
      A.scheduleMarquees();
    }
    const refresh = h('button', { class: 'bar-btn txt', type: 'button', onclick: () => load(true) }, t('exams_refresh'));
    return { el: A.page(A.bar({ title: t('gen_notes'), right: [refresh] }), h('div', { class: 'scroll ruled' }, body)), onShow() { load(false); } };
  };

  A.screens.gennotelist = function gennotelist(p) {
    const e = p.entry;
    const body = h('div', { class: 'stack' });
    const input = h('input', { class: 'inp', type: 'search', placeholder: t('gen_search'), 'aria-label': t('gen_search'), autocomplete: 'off' });
    input.value = p.q || '';
    let notes = cache[e.id] || null, timer = null;

    function draw() {
      A.clear(body);
      if (!notes) return;
      const q = input.value.trim().toLowerCase();
      const open = (n) => A.go('gennote', { note: n, subject: e.subject || e.title });
      const row = (n, showTopic) => h('button', { class: 'plain-row', type: 'button', onclick: () => open(n) },
        h('div', { class: 'row' }, h('div', { class: 'grow' }, A.mq(n.title)), A.icon('chev')), showTopic && n.topic !== n.title ? h('div', { class: 'muted small' }, n.topic) : null);
      if (q) {
        const words = q.split(/\s+/).filter(Boolean);
        const hits = notes.filter((n) => words.every((w) => n.hay.includes(w)));
        hits.sort((a, b) => (words.every((w) => b.th.includes(w)) ? 1 : 0) - (words.every((w) => a.th.includes(w)) ? 1 : 0));
        if (!hits.length) { body.append(h('div', { class: 'card' }, h('p', { class: 'muted' }, t('gen_no_results')))); return; }
        const card = h('div', { class: 'card stack' });
        hits.slice(0, 100).forEach((n) => card.append(row(n, true)));
        body.append(card);
      } else {
        const groups = {};
        notes.forEach((n) => { (groups[n.topic] = groups[n.topic] || []).push(n); });
        Object.keys(groups).forEach((k) => {
          const d = h('details', { class: 'card topicbox' }, h('summary', null, h('span', { class: 'grow' }, k), h('span', { class: 'muted small' }, t('gen_note_count', { n: groups[k].length }))));
          const inner = h('div', { class: 'stack' });
          groups[k].forEach((n) => inner.append(row(n, false)));
          d.append(inner);
          body.append(d);
        });
      }
      A.scheduleMarquees();
    }
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(draw, 150); });

    async function load() {
      if (!notes) {
        const ov = A.loading(t('gen_loading'), t('gen_notes'));
        try {
          const raw = await A.get('/api/exams/get', { kind: 'notes', id: e.id, path: e.path });
          notes = flatten(raw, e.subject || e.title);
          cache[e.id] = notes;
          ov.close();
        } catch (err) { return ov.fail(err.message || t('err_server')); }
      }
      draw();
    }
    return {
      el: A.page(A.bar({ title: (e.grade ? t('exams_grade', { g: e.grade }) + ' · ' : '') + (e.subject || e.title) }), h('div', { class: 'scroll ruled stack' }, input, body)),
      getParams: () => ({ entry: e, q: input.value }),
      onShow() { load(); },
    };
  };

  A.screens.gennote = function gennote(p) {
    const n = p.note;
    const am = h('div', { class: 'stack' });
    const body = h('div', { class: 'stack' },
      h('div', { class: 'card stack' }, h('div', { class: 'muted small' }, n.topic), (() => { const d = h('div', { class: 'note-body' }); A.md(d, n.body); return d; })()));
    if (n.am) {
      const btn = h('button', { class: 'btn block', type: 'button' }, t('gen_am_btn'));
      let on = false;
      btn.addEventListener('click', () => {
        on = !on;
        A.clear(am);
        if (on) { const d = h('div', { class: 'note-body' }); A.md(d, n.am); am.append(h('div', { class: 'card tint' }, d)); }
        btn.textContent = on ? t('gen_am_hide') : t('gen_am_btn');
        A.scheduleMarquees();
      });
      body.append(btn, am);
    }
    return { el: A.page(A.bar({ title: n.title }), h('div', { class: 'scroll ruled' }, body)) };
  };
})();
