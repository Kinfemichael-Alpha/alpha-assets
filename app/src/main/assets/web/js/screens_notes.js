/* screens_notes.js - አጫጭር ማስታወሻዎች (Short Notes) እና የእኔ እድገት (My Progress)። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ Short Notes: አዲስ / የተቀመጡ */
  A.screens.notes = function notes(params) {
    let tab = params.tab || 'new', info = null, draft = params.draft || { topic: '', length: 'short', text: '' };
    const body = h('div', { class: 'stack' });

    async function load() {
      try { info = await A.get('/api/notes/list'); A.ads.setStatus(info.ads); } catch (e) { A.info(e.message); }
    }

    /* ----- አዲስ ማስታወሻ: ርዕስ + ርዝመት -> |Generate| -> ውጤት -> |save| */
    function drawNew() {
      const topic = h('input', { class: 'inp', type: 'text', maxlength: '120', value: draft.topic, placeholder: t('notes_topic_hint'), 'aria-label': t('notes_topic') });
      topic.value = draft.topic;
      topic.addEventListener('input', () => { draft.topic = topic.value; });
      const mk = (val, label) => {
        const r = h('input', { type: 'radio', name: 'nl', value: val, checked: draft.length === val });
        r.addEventListener('change', () => { draft.length = val; });
        return h('label', null, r, h('span', null, label));
      };
      const out = h('div', { class: 'stack' });
      const go = h('button', { class: 'btn block', type: 'button' }, t('notes_generate_btn'));

      function showResult() {
        A.clear(out);
        if (!draft.text) return;
        const save = h('button', { class: 'btn block', type: 'button' }, t('btn_save'));
        save.addEventListener('click', async () => {
          save.disabled = true;
          try {
            await A.post('/api/notes/save', { topic: draft.topic.trim(), body: draft.text });
            A.toast(t('notes_saved'));
            draft.text = '';
            await load();
            showResult();
          } catch (e) { save.disabled = false; A.info(e.message); }
        });
        out.append(h('div', { class: 'card stack' },
          h('div', { class: 'h-sec' }, A.mq(draft.topic.trim())),
          h('div', { class: 'note-body' }, draft.text), save));
        A.scheduleMarquees();
      }

      go.addEventListener('click', async () => {
        const tp = topic.value.trim();
        if (!tp) return A.info(t('notes_need_topic'));
        if (info && !info.model_ready) return A.info(t('notes_need_model'));   // መጀመሪያ ሞዴል (ማስታወቂያ ሳያሳዩ)
        if (!(await A.ads.ensure())) return;                    // የሽልማት ማስታወቂያ መስኮት
        draft.topic = tp;
        const ov = A.loading(t('notes_generating'), t('notes_title'));
        try {
          const r = await A.ads.guard(() => A.post('/api/notes/generate', { topic: tp, length: draft.length }));
          const j = await A.poll(r.job_id, (jj) => { if (jj.status === 'loading') ov.set(t('ai_loading_model')); });
          ov.close();
          draft.text = j.result.text;
          showResult();
        } catch (e) { ov.fail(e.message || t('err_server')); }
      });

      const noModel = info && !info.model_ready;
      A.clear(body).append(h('div', { class: 'card stack' },
        h('div', { class: 'h-sec' }, t('notes_title')),
        h('p', { class: 'muted' }, t('notes_local_only')),
        h('label', { class: 'fld' }, h('span', null, t('notes_topic')), topic),
        h('div', { class: 'seg' }, mk('short', t('notes_len_short')), mk('medium', t('notes_len_medium'))),
        noModel ? h('div', { class: 'err' }, t('notes_need_model')) : null,
        noModel ? h('button', { class: 'btn sm ghost', type: 'button', onclick: () => A.go('settings') }, t('btn_open_settings')) : null,
        go), out);
      showResult();
    }

    /* ----- የተቀመጡ: ዝርዝር -> አንብብ / ሰርዝ (Yes/No) */
    function drawSaved() {
      const card = h('div', { class: 'card' }, h('div', { class: 'h-sec' }, t('notes_tab_saved')));
      if (!info || !info.notes.length) card.append(h('p', { class: 'muted' }, t('notes_empty')));
      (info ? info.notes : []).forEach((n) => {
        const read = h('button', { class: 'btn sm', type: 'button', onclick: () => A.go('noteread', { id: n.id }) }, t('btn_read'));
        const del = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_delete'));
        del.addEventListener('click', async () => {
          if (!(await A.confirm(t('notes_confirm_delete')))) return;   // ህግ 6
          try { await A.post('/api/notes/delete', { id: n.id }); await load(); drawSaved(); } catch (e) { A.info(e.message); }
        });
        card.append(h('div', { class: 'item' },
          h('div', { class: 'grow' }, A.mq(n.topic), h('div', { class: 'muted small' }, n.date + ' | ' + n.preview)),
          read, del));
      });
      A.clear(body).append(card);
      A.scheduleMarquees();
    }

    const draw = () => (tab === 'new' ? drawNew() : drawSaved());
    const tabs = A.tabs([['new', t('notes_tab_new')], ['saved', t('notes_tab_saved')]], tab, async (id) => { tab = id; if (id === 'saved') await load(); draw(); });   // የተቀመጡ ሲከፈት ዝርዝሩ ይታደሳል
    const el = A.page(A.bar({ title: t('notes_title'), right: [A.ads.pill()] }), h('div', { class: 'scroll ruled stack' }, tabs, body));
    return {
      el,
      getParams: () => ({ tab, draft }),
      async onShow() { await load(); draw(); },
    };
  };

  /* ------------------------------------------------------------ ማስታወሻ ማንበቢያ */
  A.screens.noteread = function noteread(params) {
    const content = h('div', { class: 'stack' });
    const title = h('div', { class: 'bar-t' }, A.mq(t('notes_read_title')));
    const bar = h('header', { class: 'bar' },
      h('button', { class: 'bar-btn', type: 'button', 'aria-label': t('back_btn'), onclick: () => A.back() }, '<'), title);
    async function load() {
      try {
        const n = await A.get('/api/notes/get', { id: params.id });
        A.clear(title).append(A.mq(n.topic));
        A.clear(content).append(h('div', { class: 'card stack' },
          h('div', { class: 'muted small' }, n.date), h('div', { class: 'note-body' }, n.body)));
        A.scheduleMarquees();
      } catch (e) { await A.info(e.message); A.back(); }
    }
    return { el: A.page(bar, h('div', { class: 'scroll ruled' }, content)), onShow() { load(); } };
  };

  /* ------------------------------------------------------------ የእኔ እድገት */
  A.screens.progress = function progress() {
    const body = h('div', { class: 'stack' });
    const grade = (p) => (p >= 70 ? 'good' : p < 40 ? 'low' : '');
    const stat = (value, label, hot) => h('div', { class: 'stat' + (hot ? ' hot' : '') }, h('b', null, String(value)), h('span', null, label));
    const barOf = (pct) => { const b = h('div', { class: 'hbar ' + grade(pct) }, h('i')); requestAnimationFrame(() => { b.firstChild.style.width = pct + '%'; }); return b; };

    async function load() {
      let d;
      try { d = await A.get('/api/progress'); } catch (e) { return A.info(e.message); }
      A.clear(body);
      if (!d.attempts) {
        body.append(h('div', { class: 'card' }, h('p', { class: 'muted' }, t('prog_empty'))));
        return;
      }
      body.append(
        h('div', { class: 'stats' },
          stat(d.attempts, t('prog_attempts')), stat(d.average + '%', t('prog_average')), stat(d.best + '%', t('prog_best'))),
        h('div', { class: 'stats' },
          stat(d.streak, t('prog_streak'), d.streak > 0), stat(d.quizzes, t('prog_quizzes')), stat(d.notes, t('prog_notes'))));
      if (d.topics.length) {
        const card = h('div', { class: 'card stack' }, h('div', { class: 'h-sec' }, t('prog_topics')));
        d.topics.forEach((x) => card.append(h('div', null,
          h('div', { class: 'row' }, h('div', { class: 'grow' }, A.mq(x.topic)), h('b', null, x.average + '%')), barOf(x.average))));
        body.append(card);
      }
      const rec = h('div', { class: 'card stack' }, h('div', { class: 'h-sec' }, t('prog_recent')));
      d.recent.forEach((r) => rec.append(h('div', null,
        h('div', { class: 'row' }, h('div', { class: 'grow' }, A.mq(r.topic || '-')), h('span', { class: 'muted small' }, r.date),
          h('b', null, r.score + '/' + r.total)), barOf(r.pct))));
      body.append(rec);
      const reset = h('button', { class: 'btn bad block', type: 'button' }, t('prog_reset'));
      reset.addEventListener('click', async () => {
        if (!(await A.confirm(t('prog_reset_confirm')))) return;   // ህግ 6
        try { await A.post('/api/progress/reset'); await load(); } catch (e) { A.info(e.message); }
      });
      body.append(reset);
      A.scheduleMarquees();
    }
    return { el: A.page(A.bar({ title: t('progress_title') }), h('div', { class: 'scroll ruled' }, body)), onShow() { load(); } };
  };
})();
