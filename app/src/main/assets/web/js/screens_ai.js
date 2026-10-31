/* screens_ai.js - AI ውይይት፣ ፈተና ማዕከል እና ፈተና መስጫ። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ AI ውይይት */
  A.screens.ai = function ai() {
    let alive = true, pending = false, entered = false;
    const nick = (A.S.state && A.S.state.nickname) || '';
    const list = h('div', { class: 'chat', 'aria-live': 'polite' });
    const memFill = h('i');
    const mem = h('div', { class: 'mem', title: t('chat_memory') }, memFill);
    const input = h('textarea', { class: 'inp', rows: '1', maxlength: '4000', placeholder: t('input_hint'), 'aria-label': t('input_hint') });
    const sendBtn = h('button', { class: 'btn', type: 'button' }, t('send_btn'));
    const composer = h('div', { class: 'composer' }, input, sendBtn);

    const toBottom = () => { list.scrollTop = list.scrollHeight; };
    const bubble = (role, text, extra) => {
      const b = h('div', { class: 'msg ' + role + (extra ? ' ' + extra : '') }, text);
      list.append(b); toBottom(); return b;
    };
    const typing = () => {
      const b = h('div', { class: 'msg ai' }, h('span', { class: 'dots', 'aria-label': t('ai_processing') }, h('i'), h('i'), h('i')));
      list.append(b); toBottom(); return b;
    };
    const setUsage = (u) => { memFill.style.width = Math.min(100, Math.round(u * 100)) + '%'; mem.classList.toggle('hi', u >= 0.8); };
    const lock = (on) => { input.disabled = on; sendBtn.disabled = on; };
    const setSend = (busy) => { sendBtn.textContent = busy ? t('btn_stop') : t('send_btn'); sendBtn.classList.toggle('stop', busy); };

    function welcome() { bubble('ai', nick ? t('ai_welcome_name', { name: nick }) : t('ai_welcome')); }

    function render(view) {
      A.clear(list);
      if (!view.messages.length) welcome();
      view.messages.forEach((m) => bubble(m.role === 'user' ? 'user' : 'ai', m.content, m.error ? 'err' : ''));
      setUsage(view.usage || 0);
    }

    let contBtn = null;
    const clearCont = () => { if (contBtn) { contBtn.remove(); contBtn = null; } };

    async function waitReply(jobId, tb, target) {
      pending = true; setSend(true); clearCont();
      let ai = null;
      const show = (text) => {
        if (!ai) { tb.remove(); ai = target || bubble('ai', ''); }
        ai.textContent = text;
        toBottom();
      };
      try {
        for (;;) {
          if (!alive) return;
          const j = await A.get('/api/chat/poll', { job_id: jobId });
          if (!alive) return;
          if (j.text) show(j.text);
          setUsage(j.usage || 0);
          if (j.status === 'running') { await new Promise((r) => setTimeout(r, 120)); continue; }
          if (j.status === 'error') {
            tb.remove();
            bubble('ai', (j.error || t('err_server')) + (j.detail ? '\n(' + j.detail + ')' : ''), 'err');
            if (j.restore) {
              input.value = j.restore; autoGrow();
              const users = list.querySelectorAll('.msg.user');
              const lastUser = users[users.length - 1];
              if (lastUser && lastUser.textContent === j.restore) lastUser.remove();
            }
            if (ai && j.text) offerContinue(ai);
          } else {
            if (j.finish === 'MAX_TOKENS') { bubble('ai', t('ai_cut_limit'), 'note'); offerContinue(ai); }
            else if (j.finish === 'NETWORK') { bubble('ai', j.error || t('ai_cut_net'), 'note'); offerContinue(ai); }
            if (j.warn) bubble('ai', t('ai_memory_warn'), 'note');
          }
          break;
        }
      } catch (e) {
        tb.remove();
        bubble('ai', e.message || t('err_server'), 'err');
      }
      pending = false; setSend(false); input.focus();
    }

    function offerContinue(ai) {
      clearCont();
      contBtn = h('button', { class: 'btn sm', type: 'button' }, t('ai_continue'));
      contBtn.addEventListener('click', async () => {
        if (pending) return;
        const btn = contBtn; clearCont();
        try {
          const r = await A.post('/api/chat/continue');
          waitReply(r.job_id, h('span'), ai);
        } catch (e) { bubble('ai', e.message, 'err'); }
      });
      const row = h('div', { class: 'msg note' }, contBtn);
      list.append(row); toBottom();
      contBtn.remove = () => row.remove();
    }

    async function send() {
      if (pending) { try { await A.post('/api/chat/stop'); } catch (e) {} return; }
      const text = input.value.trim();
      if (!text) return;
      input.value = ''; autoGrow();
      bubble('user', text);
      const tb = typing();
      try {
        const r = await A.post('/api/chat/send', { text });
        waitReply(r.job_id, tb);
      } catch (e) {
        tb.remove();
        input.value = text; autoGrow();
        bubble('ai', e.message, 'err');
      }
    }
    function autoGrow() { input.style.height = 'auto'; input.style.height = Math.min(120, input.scrollHeight) + 'px'; }
    input.addEventListener('input', autoGrow);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (!pending) send(); } });
    sendBtn.addEventListener('click', send);

    /* |save|: ውይይቱን ወደ JSON ይልካል */
    const saveBtn = h('button', { class: 'bar-btn txt', type: 'button' }, t('btn_save'));
    saveBtn.addEventListener('click', async () => {
      try { const r = await A.post('/api/chat/save'); A.info(r.message, t('btn_save')); } catch (e) { A.info(e.message); }
    });
    const newBtn = h('button', { class: 'bar-btn txt', type: 'button' }, t('ai_new_chat'));
    newBtn.addEventListener('click', async () => {
      if (pending) return A.toast(t('err_busy'));
      if (!(await A.confirm(t('confirm_new_chat')))) return;   // ህግ 6
      try { render(await A.post('/api/chat/new')); } catch (e) { A.info(e.message); }
    });

    const el = A.page(
      A.bar({ title: t('ai_title'), left: [saveBtn], right: [A.ads.pill(), newBtn] }),
      mem,
      h('div', { class: 'chat-wrap' }, list, composer));

    async function enter(force) {
      lock(true);
      let r;
      try { r = await A.post('/api/ai/enter', { force: !!force }); } catch (e) { A.info(e.message).then(() => A.back()); return; }
      if (r.warn) {
        if (await A.confirm(r.message)) return enter(true);
        A.back();
        return;
      }
      if (r.locked) {   // ሞዴሉን ወደ RAM ከመጫን በፊት መዳረሻ ያስፈልጋል
        A.ads.setStatus(r.ads);
        if (await A.ads.gate()) return enter();
        A.back();
        return;
      }
      if (r.mode === 'offline' && !r.ready) {
        if (!r.job_id) {   // ንቁ ሞዴል የለም
          welcome(); bubble('ai', r.message, 'err');
          const go = h('button', { class: 'btn', type: 'button', onclick: () => A.go('settings') }, t('settings_title'));
          list.append(h('div', { class: 'msg note' }, go));
          return;
        }
        // ህግ: ሞዴሉ በ RAM ውስጥ የሚጫነው ወደዚህ ማያ ሲገባ ብቻ ነው
        const ov = A.loading(t('ai_loading_model'));
        try { await A.poll(r.job_id); ov.close(); } catch (e) { ov.fail(e.message, () => A.back()); return; }
      }
      entered = true;
      try {
        const view = await A.get('/api/chat');
        render(view);
        if (view.pending) {
          const tb = typing(); const last = list.lastChild; waitReply(view.pending, tb, last && last.classList.contains('ai') ? last : null); lock(false); return;
        }
      } catch (e) { welcome(); }
      lock(false);
    }

    return {
      el,
      onShow() { enter(); },
      onLeave() { alive = false; A.post('/api/ai/leave').catch(() => {}); },   // ማያ ገጹን ሲለቅ ሞዴሉ ከ RAM ወዲያውኑ ይለቀቃል
    };
  };

  /* ------------------------------------------------------------ ፈተና ማዕከል */
  A.screens.quiz = function quiz(params) {
    let tab = params.tab || 'new', lib = null;
    const body = h('div', { class: 'stack' });
    let info = null;

    function drawNew() {
      const topic = h('input', { class: 'inp', type: 'text', maxlength: '120', placeholder: t('quiz_topic_hint'), 'aria-label': t('quiz_topic_hint') });
      const max = info ? info.max_questions : 10;
      const count = h('input', { class: 'inp', type: 'number', inputmode: 'numeric', min: '1', max: String(max), value: '5', 'aria-label': t('quiz_count_hint') });
      const mc = h('input', { type: 'radio', name: 'qt', value: 'mc', checked: true });
      const tf = h('input', { type: 'radio', name: 'qt', value: 'tf' });
      const go = h('button', { class: 'btn block', type: 'button' }, t('generate_btn'));
      go.addEventListener('click', async () => {
        const n = parseInt(count.value, 10);
        if (!topic.value.trim() || !n) return A.info(t('quiz_need_input'));
        if (n < 1 || n > max) return A.info(t('quiz_count_range', { max }));
        if (info && !info.offline_ready) return A.info(t('no_active_model_hint'));   // መጀመሪያ ሞዴል (ማስታወቂያ ሳያሳዩ)
        if (!(await A.ads.ensure())) return;                                          // የሽልማት ማስታወቂያ መስኮት
        const ov = A.loading(t('quiz_generating', { topic: topic.value.trim(), n }), t('quiz_gen_title'));
        try {
          const r = await A.ads.guard(() => A.post('/api/quiz/generate', { topic: topic.value.trim(), count: n, qtype: tf.checked ? 'tf' : 'mc' }));
          const j = await A.poll(r.job_id, (jj) => { if (jj.status === 'loading') ov.set(t('ai_loading_model')); });
          ov.close();
          if (j.result.got < j.result.want) await A.info(t('quiz_partial', { got: j.result.got, want: j.result.want }));
          A.go('quizplay', { id: j.result.quiz_id });
        } catch (e) { ov.fail(e.message || t('err_server')); }
      });
      A.clear(body).append(h('div', { class: 'card stack' },
        h('div', { class: 'h-sec' }, t('ai_quiz_title')),
        h('p', { class: 'muted' }, t('ai_quiz_desc')),
        h('label', { class: 'fld' }, topic),
        h('label', { class: 'fld' }, count, h('div', { class: 'muted small' }, '1 - ' + max)),
        h('div', { class: 'radio' },
          h('label', null, mc, h('span', null, t('quiz_type_mc'))),
          h('label', null, tf, h('span', null, t('quiz_type_tf')))),
        (info && !info.offline_ready) ? h('div', { class: 'err' }, t('no_active_model_hint')) : null,
        go));
    }

    function drawSaved() {
      const card = h('div', { class: 'card' }, h('div', { class: 'h-sec' }, t('saved_quizzes')));
      if (!info || !info.quizzes.length) card.append(h('p', { class: 'muted' }, t('no_saved_quizzes')));
      (info ? info.quizzes : []).forEach((q) => {
        const start = h('button', { class: 'btn sm', type: 'button', onclick: () => A.go('quizplay', { id: q.id }) }, t('btn_start'));
        const del = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_delete'));
        del.addEventListener('click', async () => {
          if (!(await A.confirm(t('confirm_delete_book', { name: q.topic })))) return;   // ህግ 6
          try { await A.post('/api/quiz/delete', { id: q.id }); await loadInfo(); drawSaved(); } catch (e) { A.info(e.message); }
        });
        card.append(h('div', { class: 'item' },
          h('div', { class: 'grow' }, A.mq(q.topic), h('div', { class: 'muted small' }, t('quiz_meta', { count: q.count, date: q.date }))),
          start, del));
      });
      A.clear(body).append(card);
      A.scheduleMarquees();
    }

    function drawExams() {
      if (lib) lib.stop();
      lib = A.libraryView('exams', [t('books_downloaded_tab'), t('books_download_tab')]);
      A.clear(body).append(h('div', { class: 'h-sec' }, t('past_exams')), lib.el);
      lib.start();
    }

    function draw() {
      if (lib) { lib.stop(); lib = null; }
      if (tab === 'new') drawNew(); else if (tab === 'saved') drawSaved(); else drawExams();
    }
    async function loadInfo() {
      try { info = await A.get('/api/quiz/list'); } catch (e) { A.info(e.message); }
    }

    const tabs = A.tabs([['new', t('quiz_tab_new')], ['saved', t('quiz_tab_saved')], ['exams', t('quiz_tab_exams')]], tab, (id) => { tab = id; draw(); });
    const el = A.page(A.bar({ title: t('quiz_title'), right: [A.ads.pill()] }), h('div', { class: 'scroll ruled stack' }, tabs, body));
    return {
      el,
      getParams: () => ({ tab }),
      async onShow() { await loadInfo(); draw(); },
      onLeave() { if (lib) lib.stop(); },
      async canLeave() {
        if (!lib || !lib.busy()) return true;
        if (!(await A.confirm(t('confirm_leave_download')))) return false;
        await A.post('/api/download/cancel');
        return true;
      },
    };
  };

  /* ------------------------------------------------------------ ፈተና መስጫ: ጊዜ፣ ውጤት፣ አረንጓዴ/ቀይ ማረጋገጫ */
  A.screens.quizplay = function quizplay(params) {
    let quizData = null, i = 0, score = 0, answered = false, finished = false;
    let deadline = 0, timerId = null, perQ = 30;
    const content = h('div', { class: 'stack' });
    const title = h('div', { class: 'bar-t' }, A.mq(''));
    const bar = h('header', { class: 'bar' },
      h('button', { class: 'bar-btn', type: 'button', 'aria-label': t('back_btn'), onclick: () => A.back() }, '<'), title);
    const el = A.page(bar, h('div', { class: 'scroll ruled' }, content));

    function stopTimer() { if (timerId) { clearInterval(timerId); timerId = null; } }

    function showQuestion() {
      stopTimer();
      answered = false;
      const q = quizData.questions[i], n = quizData.questions.length;
      const isTF = q.options.length === 2;
      perQ = isTF ? 20 : 30;
      const tfill = h('i'); const tbar = h('div', { class: 'timer' }, tfill);
      const tlabel = h('div', { class: 'muted small' }, t('quiz_time', { s: perQ }));
      const feedback = h('div', { class: 'stack' });
      const next = h('button', { class: 'btn block', type: 'button', disabled: true }, i === n - 1 ? t('quiz_finish') : t('quiz_next'));
      const opts = q.options.map((text, idx) => {
        const b = h('button', { class: 'opt', type: 'button' }, h('span', { class: 'ltr' }, String.fromCharCode(65 + idx)), h('span', { class: 'grow' }, text));
        b.addEventListener('click', () => reveal(idx));
        return b;
      });
      function reveal(choice) {
        if (answered) return;
        answered = true; stopTimer();
        opts.forEach((b, idx) => {
          b.disabled = true;
          if (idx === q.answer_index) b.classList.add('ok');
          else if (idx === choice) b.classList.add('bad');
        });
        if (choice === q.answer_index) score += 1;
        if (choice === -1) feedback.append(h('div', { class: 'explain' }, t('quiz_time_up')));
        if (q.explanation) feedback.append(h('div', { class: 'explain' }, q.explanation));
        next.disabled = false; next.focus({ preventScroll: false });
      }
      next.addEventListener('click', () => { if (!answered) return A.toast(t('quiz_pick')); if (i === n - 1) finish(); else { i += 1; showQuestion(); } });

      A.clear(content).append(
        h('div', { class: 'row' }, h('div', { class: 'grow h-sec' }, t('quiz_progress', { i: i + 1, n })), tlabel),
        tbar,
        h('div', { class: 'card' }, h('div', { class: 'q-text' }, q.question)),
        h('div', null, ...opts),
        feedback, next);

      deadline = Date.now() + perQ * 1000;
      timerId = setInterval(() => {
        const left = Math.max(0, deadline - Date.now());
        tfill.style.transform = 'scaleX(' + (left / (perQ * 1000)) + ')';
        tbar.classList.toggle('low', left < perQ * 1000 * 0.25);
        tlabel.textContent = t('quiz_time', { s: Math.ceil(left / 1000) });
        if (left <= 0) reveal(-1);   // ጊዜው ሲያልቅ ትክክለኛው መልስ ይታያል
      }, 200);
      content.scrollIntoView({ block: 'start' });
    }

    async function finish() {
      finished = true; stopTimer();
      const total = quizData.questions.length;
      try { await A.post('/api/quiz/score', { quiz_id: quizData.id, score, total }); } catch (e) { /* ውጤቱ በ UI ይታያል */ }
      const pct = Math.round(score * 100 / total);
      const ring = h('div', { class: 'ring', style: { '--p': 0 } }, h('div', null, h('div', null, h('b', null, score + ' / ' + total))));
      A.clear(content).append(
        h('div', { class: 'card center stack' }, h('div', { class: 'h-sec' }, t('quiz_result', { score, total }).split('\n')[0]), ring),
        h('button', { class: 'btn block', type: 'button', onclick: () => A.back() }, t('quiz_back')));
      let p = 0;   // ቀለበቱ በቀስታ ይሞላል
      const step = () => { p = Math.min(pct, p + Math.max(1, pct / 30)); ring.style.setProperty('--p', p); if (p < pct) requestAnimationFrame(step); };
      requestAnimationFrame(step);
      setTimeout(() => A.ads.breakPoint(), 1800);   // ተፈጥሯዊ እረፍት: ውጤት ከታየ በኋላ (ፖሊሲው ከፈቀደ) interstitial
    }

    async function load() {
      try {
        quizData = await A.get('/api/quiz/get', { id: params.id });
        quizData.id = params.id;
        A.clear(title).append(A.mq(t('quiz_topic_prefix', { topic: quizData.topic })));
        showQuestion();
        A.scheduleMarquees();
      } catch (e) { await A.info(e.message || t('quiz_corrupt')); A.back(); }
    }

    return {
      el,
      onShow() { A.ads.setBanner(false); load(); },            // በፈተና ወቅት ባነር የለም (ድንገተኛ ጠቅታ)
      onLeave() { stopTimer(); A.ads.setBanner(true); },
      async canLeave() { return finished || !quizData ? true : A.confirm(t('quiz_exit_confirm')); },   // ህግ 6
    };
  };
})();
