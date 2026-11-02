'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;
  const grade = (p) => (p >= 70 ? 'good' : p < 40 ? 'low' : '');
  const barOf = (pct) => { const b = h('div', { class: 'hbar ' + grade(pct) }, h('i')); requestAnimationFrame(() => { b.firstChild.style.width = pct + '%'; }); return b; };
  const defaultMinutes = (subject) => (/^(math|physic)/i.test(subject) ? 3 : 1);
  const letters = (q) => Object.keys(q.options || {}).sort();
  const shuffle = (a) => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
  const fmtTime = (s) => { s = Math.max(0, Math.round(s)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
  const md = (tag, cls, text) => { const el = h(tag, { class: cls || null }); A.md(el, text); return el; };

  function itemsOf(exam, filter) {
    const leaders = {}, shared = {};
    exam.questions.forEach((q) => { if (q.group && q.leader) leaders[q.group] = q.text; });
    (exam.groups || []).forEach((g) => { if (g.passage) shared[g.id] = g.passage; });
    return exam.questions.filter(filter).map((q) => ({
      q, eid: exam.id, key: q.group ? exam.id + ':' + q.group : null,
      pass: q.group ? (shared[q.group] || (q.leader ? null : leaders[q.group]) || null) : null,
    }));
  }

  function arrange(items, count) {
    const units = [], seen = {};
    items.forEach((it) => {
      if (!it.key) { units.push([it]); return; }
      if (!seen[it.key]) { seen[it.key] = []; units.push(seen[it.key]); }
      seen[it.key].push(it);
    });
    units.forEach((u) => u.sort((a, b) => (b.q.leader ? 1 : 0) - (a.q.leader ? 1 : 0) || a.q.n - b.q.n));
    shuffle(units);
    const out = [];
    for (const u of units) {
      if (count && out.length >= count) break;
      if (count && out.length + u.length > count && u.length > 1) continue;
      out.push(...u);
    }
    return out;
  }

  function start(session) {
    A.go('examrun', { session });
  }

  A.screens.exams = function exams() {
    const body = h('div', { class: 'stack' });
    let data = null, stats = null;

    async function load(refresh) {
      try {
        data = await A.get('/api/exams/index', refresh ? { refresh: 'true' } : null);
        stats = await A.get('/api/exams/stats').catch(() => null);
      } catch (e) { A.clear(body).append(h('div', { class: 'card warn' }, h('p', null, e.message))); return; }
      draw();
    }

    function draw() {
      A.clear(body);
      if (data.offline) body.append(h('div', { class: 'card tint' }, h('p', { class: 'muted' }, t('exams_offline'))));
      const list = data.exams || [];
      if (!list.length) { body.append(h('div', { class: 'card' }, h('p', { class: 'muted' }, t('exams_empty')))); return; }
      body.append(h('p', { class: 'muted' }, t('exams_intro')));
      if (stats && stats.weak && stats.weak.length) {
        const card = h('div', { class: 'card stack' }, h('div', { class: 'h-sec' }, t('exams_weak')));
        stats.weak.forEach((w) => {
          const pct = Math.round(100 * w.correct / w.total);
          const ex = list.find((e) => e.subject === w.subject);
          const row = h('button', { class: 'plain-row', type: 'button' },
            h('div', { class: 'row' }, h('div', { class: 'grow' }, A.mq(w.subject + ' · ' + w.topic)), h('b', null, pct + '%')), barOf(pct));
          if (ex) row.addEventListener('click', () => A.go('examsetup', { grade: ex.grade, subject: ex.subject, topic: w.topic }));
          card.append(row);
        });
        body.append(card);
      }
      const groups = {};
      list.forEach((e) => { (groups[e.grade + '|' + e.subject] = groups[e.grade + '|' + e.subject] || []).push(e); });
      Object.keys(groups).sort().forEach((k) => {
        const g = groups[k], first = g[0];
        const q = g.reduce((s, e) => s + (e.count || 0), 0);
        body.append(h('button', { class: 'card subj', type: 'button', onclick: () => A.go('examsetup', { grade: first.grade, subject: first.subject }) },
          h('div', { class: 'grow' },
            h('div', { class: 'h-sec' }, t('exams_grade', { g: first.grade }) + ' · ' + first.subject),
            h('div', { class: 'muted small' }, t('exams_count_line', { n: g.length, q }))),
          A.icon('chev')));
      });
      if (stats && stats.attempts) {
        const reset = h('button', { class: 'btn bad block', type: 'button' }, t('exams_reset'));
        reset.addEventListener('click', async () => {
          if (!(await A.confirm(t('exams_reset_confirm')))) return;
          try { await A.post('/api/exams/reset'); await load(false); } catch (e) { A.info(e.message); }
        });
        body.append(reset);
      }
      A.scheduleMarquees();
    }

    const refresh = h('button', { class: 'bar-btn txt', type: 'button', onclick: () => load(true) }, t('exams_refresh'));
    return { el: A.page(A.bar({ title: t('exams_title'), right: [refresh] }), h('div', { class: 'scroll ruled' }, body)), onShow() { load(false); } };
  };

  A.screens.examsetup = function examsetup(p) {
    const st = Object.assign({ src: p.topic ? 'topic' : 'year', eid: '', topics: p.topic ? [p.topic] : [], count: null, minutes: defaultMinutes(p.subject) }, p.v || {});
    const body = h('div', { class: 'stack' });
    let list = [];

    const mine = () => list.filter((e) => e.grade === p.grade && e.subject === p.subject).sort((a, b) => (b.year_ec || 0) - (a.year_ec || 0) || String(a.round).localeCompare(String(b.round)));
    const examName = (e) => t('exams_year_round', { y: e.year_ec, r: e.round ? t('exams_round', { r: e.round }) : '' }).trim();

    function topicCounts() {
      const m = {};
      mine().forEach((e) => Object.entries(e.topics || {}).forEach(([k, v]) => { m[k] = (m[k] || 0) + v; }));
      return m;
    }
    function available() {
      if (st.src === 'year') { const e = mine().find((x) => x.id === st.eid); return e ? e.count : 0; }
      if (st.src === 'topic') { const m = topicCounts(); return st.topics.reduce((s, k) => s + (m[k] || 0), 0); }
      return mine().reduce((s, e) => s + (e.count || 0), 0);
    }

    function draw() {
      A.clear(body);
      const ex = mine();
      if (!ex.length) { body.append(h('div', { class: 'card' }, h('p', { class: 'muted' }, t('exams_empty')))); return; }
      if (!st.eid || !ex.some((e) => e.id === st.eid)) st.eid = ex[0].id;
      const seg = h('div', { class: 'seg' });
      [['year', t('exams_src_year')], ['topic', t('exams_src_topic')], ['mixed', t('exams_src_mixed')]].forEach(([v, label]) => {
        const r = h('input', { type: 'radio', name: 'src', value: v, checked: st.src === v });
        r.addEventListener('change', () => { st.src = v; st.count = null; draw(); });
        seg.append(h('label', null, r, h('span', null, label)));
      });
      const card = h('div', { class: 'card stack' }, h('div', { class: 'h-sec' }, p.subject), seg);
      if (st.src === 'year') {
        const sel = h('select', { class: 'inp', 'aria-label': t('exams_pick_year') });
        ex.forEach((e) => sel.append(h('option', { value: e.id, selected: e.id === st.eid }, examName(e) + ' (' + e.count + ')' + (e.downloaded ? ' ✓' : ''))));
        sel.addEventListener('change', () => { st.eid = sel.value; st.count = null; draw(); });
        card.append(h('label', { class: 'fld' }, h('span', null, t('exams_pick_year')), sel));
      } else if (st.src === 'topic') {
        const box = h('div', { class: 'stack' });
        Object.entries(topicCounts()).sort((a, b) => b[1] - a[1]).forEach(([k, n]) => {
          const c = h('input', { type: 'checkbox', checked: st.topics.includes(k) });
          c.addEventListener('change', () => { st.topics = c.checked ? st.topics.concat(k) : st.topics.filter((x) => x !== k); st.count = null; refreshInfo(); });
          box.append(h('label', { class: 'chk' }, c, h('span', { class: 'grow' }, k), h('span', { class: 'muted small' }, String(n))));
        });
        card.append(h('div', { class: 'h-sec' }, t('exams_pick_topics')), box);
      }
      const avail = available();
      const cnt = h('input', { class: 'inp', type: 'number', inputmode: 'numeric', min: '1', 'aria-label': t('exams_howmany') });
      const mins = h('input', { class: 'inp', type: 'number', inputmode: 'decimal', min: '0.5', step: '0.5', 'aria-label': t('exams_minutes') });
      const info = h('p', { class: 'muted' });
      function wanted() { const v = parseInt(cnt.value, 10); return v > 0 ? Math.min(v, avail || v) : avail; }
      function refreshInfo() {
        const a = available();
        info.textContent = t('exams_total_time', { n: wanted(), m: Math.round(wanted() * (parseFloat(mins.value) || 0)) }) + ' · ' + t('exams_available', { n: a });
      }
      cnt.value = st.count || avail || '';
      mins.value = st.minutes;
      cnt.addEventListener('input', () => { st.count = parseInt(cnt.value, 10) || null; refreshInfo(); });
      mins.addEventListener('input', () => { st.minutes = parseFloat(mins.value) || st.minutes; refreshInfo(); });
      const chips = h('div', { class: 'chips' });
      [10, 20, 50].forEach((n) => chips.append(h('button', { class: 'chip', type: 'button', onclick: () => { cnt.value = Math.min(n, avail); st.count = Math.min(n, avail); refreshInfo(); } }, String(n))));
      chips.append(h('button', { class: 'chip', type: 'button', onclick: () => { cnt.value = avail; st.count = null; refreshInfo(); } }, t('exams_all')));
      card.append(
        h('label', { class: 'fld' }, h('span', null, t('exams_howmany')), cnt), chips,
        h('label', { class: 'fld' }, h('span', null, t('exams_minutes')), mins), info,
        h('button', { class: 'btn block', type: 'button', onclick: go }, t('exams_start')));
      body.append(card);
      refreshInfo();
      A.scheduleMarquees();
    }

    async function go() {
      const ex = mine();
      let chosen;
      if (st.src === 'year') chosen = ex.filter((e) => e.id === st.eid);
      else if (st.src === 'topic') {
        if (!st.topics.length) return A.info(t('exams_need_topic'));
        chosen = ex.filter((e) => Object.keys(e.topics || {}).some((k) => st.topics.includes(k)));
      } else chosen = ex;
      if (!chosen.length) return A.info(t('exams_no_q'));
      const ov = A.loading(t('exams_preparing'), t('exams_title'));
      let items = [];
      try {
        for (let i = 0; i < chosen.length; i++) {
          ov.set(t('exams_preparing') + ' ' + (i + 1) + '/' + chosen.length);
          const e = await A.get('/api/exams/get', { id: chosen[i].id, path: chosen[i].path });
          items = items.concat(itemsOf(e, (q) => st.src !== 'topic' || st.topics.includes(q.topic || 'Other')));
        }
      } catch (e) { return ov.fail(e.message || t('err_server')); }
      ov.close();
      items = items.filter((it) => it.q && it.q.options && Object.keys(it.q.options).length && it.q.answer);
      if (!items.length) return A.info(t('exams_no_q'));
      const n = st.count ? Math.min(st.count, items.length) : items.length;
      const picked = arrange(items, st.count ? n : 0);
      const label = st.src === 'year' ? examName(chosen[0]) : st.src === 'topic' ? st.topics.join(', ') : t('exams_src_mixed');
      start({ subject: p.subject, grade: p.grade, label, items: picked, perQ: st.minutes });
    }

    return {
      el: A.page(A.bar({ title: t('exams_grade', { g: p.grade }) + ' · ' + p.subject }), h('div', { class: 'scroll ruled' }, body)),
      getParams: () => Object.assign({}, p, { v: st }),
      async onShow() {
        try { list = (await A.get('/api/exams/index')).exams || []; } catch (e) { return A.info(e.message); }
        draw();
      },
    };
  };

  A.screens.examrun = function examrun(p) {
    const s = p.session, n = s.items.length;
    const answers = {};
    let i = 0, finished = false;
    const total = Math.max(60, Math.round(n * s.perQ * 60));
    const endAt = Date.now() + total * 1000;
    const timerEl = h('span', { class: 'pill' }, fmtTime(total));
    const area = h('div', { class: 'stack' });
    const nav = h('div', { class: 'exam-nav' });
    let tick = null;

    function finish(auto) {
      if (finished) return;
      finished = true;
      clearInterval(tick);
      const secs = Math.min(total, Math.round((Date.now() - (endAt - total * 1000)) / 1000));
      A.go('examresult', { session: s, answers, secs }, { replace: true });
      if (auto) A.toast(t('exam_time_up'), 5000);
    }
    async function ask() {
      const left = n - Object.keys(answers).length;
      if (left > 0 && !(await A.confirm(t('exam_unanswered', { n: left })))) return;
      finish(false);
    }

    function drawQ() {
      const it = s.items[i], q = it.q;
      A.clear(area);
      area.append(h('div', { class: 'row' }, h('b', null, t('exam_q_of', { n: i + 1, total: n })), h('span', { class: 'grow' }),
        h('span', { class: 'muted small' }, t('exam_answered', { a: Object.keys(answers).length, t: n }))));
      if (it.pass) area.append(h('details', { class: 'card passage', open: 'open' }, h('summary', null, t('exam_passage')), md('div', 'pass-body', it.pass)));
      area.append(md('div', 'q-text', q.text));
      if (q.figure) area.append(h('div', { class: 'explain' }, h('b', null, t('exam_figure') + ': '), q.figure));
      const box = h('div', null);
      letters(q).forEach((L) => {
        const b = h('button', { class: 'opt' + (answers[i] === L ? ' picked' : ''), type: 'button' }, h('span', { class: 'ltr' }, L), md('span', 'grow', q.options[L]));
        b.addEventListener('click', () => { answers[i] = L; drawQ(); });
        box.append(b);
      });
      area.append(box);
      const prev = h('button', { class: 'btn ghost', type: 'button', disabled: i === 0, onclick: () => { i--; drawQ(); } }, t('exam_prev'));
      const grid = h('button', { class: 'btn ghost', type: 'button', onclick: openGrid }, t('exam_list'));
      const last = i === n - 1;
      const next = h('button', { class: 'btn', type: 'button', onclick: () => { if (last) ask(); else { i++; drawQ(); } } }, last ? t('exam_finish') : t('exam_next'));
      A.clear(nav).append(prev, grid, next);
      area.parentNode && (area.parentNode.scrollTop = 0);
      A.scheduleMarquees();
    }

    function openGrid() {
      const g = h('div', { class: 'qgrid' });
      s.items.forEach((_, k) => g.append(h('button', {
        class: 'qcell' + (answers[k] ? ' done' : '') + (k === i ? ' cur' : ''), type: 'button',
        onclick: () => { i = k; m.close(); drawQ(); },
      }, String(k + 1))));
      const m = A.modal({
        title: t('exam_list'), body: g, dismiss: true,
        actions: [{ label: t('exam_finish'), kind: 'ghost', onClick: () => { setTimeout(ask, 0); } }, { label: t('close'), kind: 'primary' }],
      });
    }

    tick = setInterval(() => {
      const left = Math.round((endAt - Date.now()) / 1000);
      timerEl.textContent = fmtTime(left);
      timerEl.classList.toggle('off', left <= 60);
      if (left <= 0) finish(true);
    }, 1000);

    const el = A.page(A.bar({ title: s.subject, right: [timerEl], pts: false }), h('div', { class: 'scroll' }, area), nav);
    return {
      el,
      canLeave: () => (finished ? true : A.confirm(t('exam_leave'))),
      onLeave() { clearInterval(tick); },
      onShow() { drawQ(); },
    };
  };

  A.screens.examresult = function examresult(p) {
    const { session: s, answers, secs } = p;
    const n = s.items.length;
    let right = 0;
    const topics = {}, wrong = [];
    s.items.forEach((it, k) => {
      const ok = answers[k] === it.q.answer;
      const tp = it.q.topic || 'Other';
      topics[tp] = topics[tp] || [0, 0];
      topics[tp][1] += 1;
      if (ok) { right += 1; topics[tp][0] += 1; } else wrong.push(k);
    });
    const pct = Math.round(100 * right / n);
    const skipped = n - Object.keys(answers).length;
    A.post('/api/exams/attempt', { subject: s.subject, label: s.label, score: right, total: n, seconds: secs, topics, wrong: wrong.map((k) => s.items[k].q.n) }).catch(() => {});

    const body = h('div', { class: 'stack' },
      h('div', { class: 'card stack center' },
        h('div', { class: 'ring', style: { '--p': pct } }, h('div', null, h('div', null, h('b', null, right + '/' + n), h('div', { class: 'muted' }, pct + '%')))),
        h('div', { class: 'muted' }, s.label)),
      h('div', { class: 'stats' },
        h('div', { class: 'stat hot' }, h('b', null, String(right)), h('span', null, t('exam_correct'))),
        h('div', { class: 'stat' }, h('b', null, String(n - right - skipped)), h('span', null, t('exam_wrong'))),
        h('div', { class: 'stat' }, h('b', null, String(skipped)), h('span', null, t('exam_skipped')))),
      h('div', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, t('exam_time_used')), h('b', null, fmtTime(secs)))));
    const tcard = h('div', { class: 'card stack' }, h('div', { class: 'h-sec' }, t('exam_by_topic')));
    Object.entries(topics).sort((a, b) => a[1][0] / a[1][1] - b[1][0] / b[1][1]).forEach(([k, v]) => {
      const pp = Math.round(100 * v[0] / v[1]);
      tcard.append(h('div', null, h('div', { class: 'row' }, h('div', { class: 'grow' }, A.mq(k)), h('b', null, v[0] + '/' + v[1])), barOf(pp)));
    });
    body.append(tcard);
    body.append(h('button', { class: 'btn block', type: 'button', onclick: () => A.go('examreview', { session: s, answers, only: false }, { replace: true }) }, t('exam_review_all')));
    if (wrong.length) {
      body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('examreview', { session: s, answers, only: true }, { replace: true }) }, t('exam_review_wrong')));
      body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('examrun', { session: Object.assign({}, s, { label: t('exam_retry_label'), items: arrange(wrong.map((k) => s.items[k]), 0) }) }, { replace: true }) }, t('exam_retry_wrong')));
    } else body.append(h('div', { class: 'card tint' }, h('p', null, t('exam_perfect'))));
    body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.back() }, t('exam_done')));
    return { el: A.page(A.bar({ title: t('exam_result') }), h('div', { class: 'scroll ruled' }, body)) };
  };

  A.screens.examreview = function examreview(p) {
    const { session: s, answers, only } = p;
    const body = h('div', { class: 'stack' });
    s.items.forEach((it, k) => {
      const q = it.q, mine = answers[k], ok = mine === q.answer;
      if (only && ok) return;
      const card = h('div', { class: 'card stack' }, h('div', { class: 'row' }, h('b', null, t('exam_q_short', { n: k + 1 })), h('span', { class: 'grow' }),
        h('span', { class: 'muted small' }, q.topic || '')));
      if (it.pass) card.append(h('details', { class: 'passage' }, h('summary', null, t('exam_passage')), md('div', 'pass-body', it.pass)));
      card.append(md('div', 'q-text', q.text));
      if (q.figure) card.append(h('div', { class: 'explain' }, h('b', null, t('exam_figure') + ': '), q.figure));
      const box = h('div', null);
      letters(q).forEach((L) => {
        const cls = L === q.answer ? ' ok' : L === mine ? ' bad' : '';
        box.append(h('div', { class: 'opt' + cls }, h('span', { class: 'ltr' }, L), md('span', 'grow', q.options[L])));
      });
      card.append(box);
      card.append(h('div', { class: 'muted small' }, mine ? t('exam_your_answer') + ': ' + mine : t('exam_not_answered')));
      if (q.explanation) card.append(h('div', { class: 'explain' }, h('b', null, t('exam_explanation')), md('div', null, q.explanation)));
      if (q.extra) {
        const more = h('div', { class: 'stack' });
        const btn = h('button', { class: 'btn sm ghost', type: 'button' }, t('exam_explain_more'));
        let open = false;
        btn.addEventListener('click', () => {
          open = !open;
          A.clear(more);
          if (open) more.append(h('div', { class: 'explain' }, md('div', null, q.extra), h('p', { class: 'muted small' }, t('exam_ai_note'))));
          btn.textContent = open ? t('exam_hide_more') : t('exam_explain_more');
          A.scheduleMarquees();
        });
        card.append(btn, more);
      }
      body.append(card);
    });
    if (!body.firstChild) body.append(h('div', { class: 'card' }, h('p', null, t('exam_perfect'))));
    return { el: A.page(A.bar({ title: t('exam_review') }), h('div', { class: 'scroll ruled' }, body)) };
  };
})();
