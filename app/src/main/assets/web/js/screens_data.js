/* screens_data.js - ቅንብሮች (ሞዴል አስተዳደር) እና መጻሕፍት / ፈተና ማውረጃ (library view)። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ የማውረድ ሂደት ፓነል (የተጋራ) */
  function progressPanel(accept) {
    const label = h('div', { class: 'small' });
    const bar = h('div', { class: 'prog' }, h('i'));
    const cancel = h('button', { class: 'btn sm bad', type: 'button' }, t('btn_cancel_dl'));
    const el = h('div', { class: 'card tint', hidden: true }, label, bar, cancel);
    el.hidden = true;
    cancel.addEventListener('click', () => A.post('/api/download/cancel'));
    return {
      el,
      update(s) {
        const show = s.state === 'running' && accept(s);
        el.hidden = !show;
        if (show) {
          label.textContent = t('dl_progress', { name: s.title, pct: s.pct });
          bar.firstChild.style.width = s.pct + '%';
        }
      },
    };
  }

  /* ------------------------------------------------------------ ቅንብሮች */
  A.screens.settings = function settings() {
    let watcher = null;
    let data = null;
    const secTheme = h('div', { class: 'card' });
    const secLang = h('div', { class: 'card' });
    const secAi = h('div', { class: 'card stack' });
    const secModels = h('div', { class: 'card' });
    const secPublic = h('div', { class: 'card' });
    const secProfile = h('div', { class: 'card stack' });   // አማራጭ ቅጽል ስም
    const panel = progressPanel((s) => s.scope === 'public');
    let lastState = 'idle', started = false;

    const refreshBtn = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_refresh'));
    refreshBtn.addEventListener('click', async () => {
      refreshBtn.disabled = true;
      const ov = A.loading(t('sync_updating'), t('notice_title'));
      try {
        const r = await A.post('/api/sync', { force: true });
        await A.poll(r.job_id);
        ov.close();
        await load();
      } catch (e) { ov.fail(e.message || t('err_server')); }
      refreshBtn.disabled = false;
    });

    /* ----- 1. ገጽታ (Global Theme Engine) */
    function drawTheme() {
      A.clear(secTheme).append(h('div', { class: 'h-sec' }, t('set_theme')));
      // ብርሃን / ጨለማ / ራስ-ሰር
      const modeCur = A.S.state.settings.appearance || 'device';
      const seg = h('div', { class: 'tabs', role: 'tablist' });
      [['light', t('mode_light')], ['dark', t('mode_dark')], ['device', t('mode_device')]].forEach(([key, label]) => seg.append(h('button', {
        class: 'tab', type: 'button', role: 'tab', 'aria-selected': String(key === modeCur),
        onclick: async () => {
          const r = await A.post('/api/settings', { appearance: key });
          A.S.state.settings = r.settings; A.applyMode(r.settings.appearance); drawTheme();
        } }, label)));
      secTheme.append(seg);
      const sw = h('div', { class: 'swatches' });
      const cur = A.S.state.settings.theme;
      A.S.state.themes.forEach((p) => sw.append(h('button', {
        class: 'sw-c', type: 'button', 'aria-label': p.name, 'aria-pressed': String(p.name === cur),
        style: { background: p.primary },
        onclick: async () => {
          const r = await A.post('/api/settings', { theme: p.name });
          A.S.state.settings = r.settings;
          A.applyTheme(r.settings.palette);   // በሁሉም ገጾች በአንድ ጊዜ ይተገበራል
          drawTheme();
        },
      })));
      secTheme.append(sw);
    }

    /* ----- 2. ቋንቋ */
    function drawLang() {
      A.clear(secLang).append(h('div', { class: 'row' },
        h('div', { class: 'grow h-sec' }, t('set_language')),
        h('button', { class: 'btn sm', type: 'button', onclick: () => A.setLang(A.S.lang === 'AM' ? 'EN' : 'AM') }, t('lang_btn'))));
    }

    /* ----- 3. Online / Offline AI */
    function statusText() {
      const s = A.S.state.settings;
      if (s.online_mode) return t('status_online');
      return t('status_offline', { model: s.active_model ? s.active_model.title : t('no_model_selected') });
    }
    function drawAi() {
      const s = A.S.state.settings;
      const cb = h('input', { type: 'checkbox', checked: !!s.online_mode, 'aria-label': t('set_ai_mode') });
      cb.addEventListener('change', async () => {
        try {
          const r = await A.post('/api/settings', { online_mode: cb.checked });
          A.S.state.settings = r.settings;
        } catch (e) { cb.checked = !cb.checked; A.info(e.message); }
        drawAi();
      });
      const save = async (body, okMsg) => {
        try {
          const r = await A.post('/api/settings', body);
          A.S.state.settings = r.settings;
          if (okMsg) A.toast(okMsg);
        } catch (e) { A.info(e.message); }
        drawAi();
      };
      const keyInp = h('input', { class: 'inp', type: 'password', autocomplete: 'off', spellcheck: 'false', placeholder: t('key_ph'), 'aria-label': t('set_gemini_key') });
      const keySave = h('button', { class: 'btn sm', type: 'button' }, t('btn_save'));
      keySave.addEventListener('click', () => { if (keyInp.value.trim()) save({ gemini_key: keyInp.value.trim() }, t('key_saved')); });
      const keyClear = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_clear'));
      keyClear.addEventListener('click', async () => { if (await A.confirm(t('confirm_delete_book', { name: t('set_gemini_key') }))) save({ gemini_key: '' }, t('key_cleared')); });
      const keyRow = h('div', { class: 'row' }, h('div', { class: 'grow' }, keyInp), keySave);
      const tokVal = h('b', null, String(s.max_tokens));
      const tok = h('input', { type: 'range', min: '512', max: '8192', step: '256', value: String(s.max_tokens), 'aria-label': t('set_max_tokens') });
      tok.addEventListener('input', () => { tokVal.textContent = tok.value; });
      tok.addEventListener('change', () => save({ max_tokens: parseInt(tok.value, 10) }));
      const tmpVal = h('b', null, Number(s.temperature).toFixed(2));
      const tmp = h('input', { type: 'range', min: '0', max: '1', step: '0.05', value: String(s.temperature), 'aria-label': t('set_temperature') });
      const tmpWarn = h('div', { class: 'err small' }, t('temp_warn'));
      tmpWarn.style.display = s.temperature > 0.8 ? '' : 'none';
      tmp.addEventListener('input', () => { tmpVal.textContent = Number(tmp.value).toFixed(2); tmpWarn.style.display = Number(tmp.value) > 0.8 ? '' : 'none'; });
      tmp.addEventListener('change', () => save({ temperature: parseFloat(tmp.value) }));
      const lenSeg = h('div', { class: 'tabs', role: 'tablist' });
      [['short', t('resp_short')], ['long', t('resp_long')]].forEach(([key, label]) => lenSeg.append(h('button', {
        class: 'tab', type: 'button', role: 'tab', 'aria-selected': String(key === s.resp_len), onclick: () => save({ resp_len: key }),
      }, label)));
      A.clear(secAi).append(
        h('div', { class: 'row' }, h('div', { class: 'grow h-sec' }, t('set_ai_mode')), h('label', { class: 'sw' }, cb, h('i'))),
        h('div', { class: 'h-sec' }, A.mq(statusText())),
        h('div', { class: 'h-sec' }, t('set_gemini_key')),
        h('div', { class: 'muted small' }, s.gemini_key_set ? t('key_set_tail', { tail: s.gemini_key_tail }) : t('key_not_set')),
        keyRow,
        s.gemini_key_set ? keyClear : null,
        h('div', { class: 'muted small' }, t('set_gemini_hint')),
        h('div', { class: 'row' }, h('div', { class: 'grow h-sec' }, t('set_max_tokens')), tokVal), tok,
        h('div', { class: 'muted small' }, t('set_max_tokens_hint')),
        h('div', { class: 'row' }, h('div', { class: 'grow h-sec' }, t('set_temperature')), tmpVal), tmp, tmpWarn,
        h('div', { class: 'muted small' }, t('set_temp_hint')),
        h('div', { class: 'h-sec' }, t('set_resp_len')), lenSeg);
      A.scheduleMarquees();
    }

    /* ----- 4. የወረዱ ሞዴሎች: |0| name |Activate|  ወይም  |1| name |deactivate| */
    function drawModels() {
      A.clear(secModels).append(h('div', { class: 'row' }, h('div', { class: 'grow h-sec' }, t('models_downloaded_title')), refreshBtn));
      if (!data.downloaded.length) { secModels.append(h('p', { class: 'muted' }, t('no_downloaded_models'))); return; }
      const hasActive = data.downloaded.some((m) => m.active);
      data.downloaded.forEach((m) => {
        const act = h('button', { class: 'btn sm ' + (m.active ? 'bad' : 'ok'), type: 'button' }, m.active ? t('btn_deactivate') : t('btn_activate'));
        act.addEventListener('click', async () => {
          if (m.active) {
            const r = await A.post('/api/models/deactivate');
            A.S.state.settings = r.settings;
          } else {
            if (hasActive && !(await A.confirm(t('confirm_switch_model', { name: m.title })))) return;   // ህግ 6
            try {
              const r = await A.post('/api/models/activate', { name: m.name, scope: m.scope });
              A.S.state.settings = r.settings;
            } catch (e) { return A.info(e.message); }
          }
          await load();
        });
        const del = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_delete'));
        del.addEventListener('click', async () => {
          if (!(await A.confirm(t('confirm_delete_model', { name: m.title })))) return;   // ህግ 6
          try {
            const r = await A.post('/api/models/delete', { name: m.name, scope: m.scope });
            A.S.state.settings = r.settings;
          } catch (e) { return A.info(e.message); }
          await load();
        });
        secModels.append(h('div', { class: 'item' },
          h('span', { class: 'badge' }, m.active ? '●' : '○'),
          h('div', { class: 'grow' }, A.mq(m.title), h('div', { class: 'muted small' }, m.scope + ' - ' + A.fmtSize(m.size_mb))),
          act, del));
      });
      A.scheduleMarquees();
    }

    /* ----- 5/6. ሞዴል ማውረጃ ዝርዝሮች */
    function entryRow(scope, e) {
      const right = e.downloaded
        ? h('span', { class: 'tag done' }, t('lbl_downloaded'))
        : h('button', { class: 'btn sm', type: 'button', onclick: () => startDownload(scope, e) }, t('btn_download'));
      return h('div', { class: 'item' },
        h('div', { class: 'grow' }, A.mq(e.title), e.size_mb ? h('div', { class: 'muted small' }, A.fmtSize(e.size_mb)) : null), right);
    }
    async function startDownload(scope, e) {
      if (!(await A.confirm(t('confirm_download', { name: e.title })))) return;
      try {
        await A.post('/api/models/download', { scope, filename: e.filename });
        started = true;   // ማውረዱ ከመጀመሪያው ምርጫ በፊት ቢያልቅ እንኳ መጨረሻው እንዳይመለጥ
        watcher.kick();
      } catch (err) { A.info(err.message); }
    }
    function drawPublic() {
      A.clear(secPublic).append(h('div', { class: 'h-sec' }, t('models_public_title')));
      if (!data.public.length) { secPublic.append(h('p', { class: 'muted' }, t('no_catalog'))); return; }
      data.public.forEach((g) => {
        secPublic.append(h('div', { class: 'h-sec' }, g.group));
        g.entries.forEach((e) => secPublic.append(entryRow('public', e)));
      });
      A.scheduleMarquees();
    }
    /* ----- ቅጽል ስም (አማራጭ): AI የሚጠራበት። ባዶ ማድረግ ይቻላል */
    function drawProfile() {
      const inp = h('input', { class: 'inp', type: 'text', maxlength: '40', 'aria-label': t('set_nickname') });
      inp.value = A.S.state.nickname || '';
      const save = h('button', { class: 'btn sm', type: 'button' }, t('btn_save'));
      save.addEventListener('click', async () => {
        try {
          const r = await A.post('/api/profile', { nickname: inp.value.trim() });
          A.S.state.nickname = r.nickname;
          A.toast(t('nick_saved'));
        } catch (e) { A.info(e.message); }
      });
      A.clear(secProfile).append(h('div', { class: 'h-sec' }, t('set_nickname')), h('div', { class: 'row' }, h('div', { class: 'grow' }, inp), save));
    }

    async function load() {
      try { data = await A.get('/api/models'); } catch (e) { return A.info(e.message); }
      A.S.state.settings = data.settings;
      drawAi(); drawModels(); drawPublic();
    }

    /* የማውረድ ሁኔታ ሲቀየር: ተጠናቀቀ -> ዝርዝር ያድሳል */
    function onSnap(s) {
      panel.update(s);
      if ((lastState === 'running' || started) && s.state !== 'running' && s.state !== 'idle') {
        started = false;
        if (s.state === 'done') { A.toast(t('dl_done')); load(); }
        else if (s.state === 'error') A.info(t('dl_failed', { err: s.error }));
        else if (s.state === 'cancelled') A.toast(t('dl_cancelled'));
      }
      lastState = s.state;
    }

    const resetBtn = h('button', { class: 'btn bad block', type: 'button' }, t('reset_btn'));
    resetBtn.addEventListener('click', async () => {
      if (!(await A.confirm(t('confirm_reset')))) return;   // ህግ 6
      const r = await A.post('/api/settings/reset');
      A.S.state.settings = r.settings;
      A.S.lang = r.settings.language;
      A.applyTheme(r.settings.palette);
      A.toast(t('reset_done'));
      A.rerender();
    });

    const el = A.page(
      A.bar({ title: t('settings_title') }),
      h('div', { class: 'scroll ruled stack' }, secTheme, secLang, secProfile, secAi, panel.el, secModels, secPublic, resetBtn));
    drawTheme(); drawLang(); drawProfile(); drawAi();

    return {
      el,
      onShow() { watcher = A.watchDownload(onSnap); load(); },
      onLeave() { if (watcher) watcher.stop(); },
      async canLeave() {
        if (lastState !== 'running') return true;
        if (!(await A.confirm(t('confirm_leave_download')))) return false;
        await A.post('/api/download/cancel');
        return true;
      },
    };
  };

  /* ------------------------------------------------------------ library view (መጻሕፍት + ያለፉ ፈተናዎች) */
  A.libraryView = function libraryView(kind, tabLabels) {
    let watcher = null, lastState = 'idle', data = null, tab = 'have', started = false;
    const panel = progressPanel((s) => s.scope === kind);
    const body = h('div', { class: 'stack' });
    const noneHave = kind === 'books' ? t('no_books') : t('no_exams');
    const noneCat = kind === 'books' ? t('no_book_catalog') : t('no_exam_catalog');
    const refreshBtn = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_refresh'));
    refreshBtn.addEventListener('click', async () => {
      const ov = A.loading(t('sync_updating'), t('notice_title'));
      try {
        const r = await A.post('/api/sync', { force: true });
        await A.poll(r.job_id);
        ov.close();
        await load();
      } catch (e) { ov.fail(e.message || t('err_server')); }
    });

    function rowHave(f) {
      const read = h('button', { class: 'btn sm', type: 'button' }, t('btn_read'));
      read.addEventListener('click', async () => {
        try { await A.post('/api/library/read', { kind, name: f.name }); } catch (e) { A.info(e.message); }
      });
      const del = h('button', { class: 'btn sm ghost', type: 'button' }, t('btn_delete'));
      del.addEventListener('click', async () => {
        if (!(await A.confirm(t('confirm_delete_book', { name: f.title })))) return;   // ህግ 6
        try { await A.post('/api/library/delete', { kind, name: f.name }); await load(); } catch (e) { A.info(e.message); }
      });
      return h('div', { class: 'item' },
        h('div', { class: 'grow' }, A.mq(f.title), h('div', { class: 'muted small' }, A.fmtSize(f.size_mb))), read, del);
    }
    function rowCat(e) {
      const right = e.downloaded
        ? h('span', { class: 'tag done' }, t('lbl_downloaded'))
        : h('button', { class: 'btn sm', type: 'button' }, t('btn_download'));
      if (!e.downloaded) {
        right.addEventListener('click', async () => {
          try { await A.post('/api/library/download', { kind, filename: e.filename }); started = true; watcher.kick(); } catch (err) { A.info(err.message); }
        });
      }
      return h('div', { class: 'item' },
        h('div', { class: 'grow' }, A.mq(e.title), e.size_mb ? h('div', { class: 'muted small' }, A.fmtSize(e.size_mb)) : null), right);
    }
    function draw() {
      A.clear(body);
      if (!data) return;
      if (tab === 'have') {
        const card = h('div', { class: 'card' });
        if (!data.downloaded.length) card.append(h('p', { class: 'muted' }, noneHave));
        data.downloaded.forEach((f) => card.append(rowHave(f)));
        body.append(card);
      } else {
        const card = h('div', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }), refreshBtn));
        if (!data.catalog.length) card.append(h('p', { class: 'muted' }, noneCat));
        data.catalog.forEach((g) => {
          card.append(h('div', { class: 'h-sec' }, g.group));
          g.entries.forEach((e) => card.append(rowCat(e)));
        });
        body.append(card);
      }
      A.scheduleMarquees();
    }
    async function load() {
      try { data = await A.get('/api/library', { kind }); } catch (e) { return A.info(e.message); }
      draw();
    }
    function onSnap(s) {
      panel.update(s);
      if ((lastState === 'running' || started) && s.state !== 'running' && s.state !== 'idle' && s.scope === kind) {
        started = false;
        if (s.state === 'done') { A.toast(t('dl_done')); load(); }
        else if (s.state === 'error') A.info(t('dl_failed', { err: s.error }));
        else if (s.state === 'cancelled') A.toast(t('dl_cancelled'));
      }
      lastState = s.state;
    }
    const tabs = A.tabs([['have', tabLabels[0]], ['get', tabLabels[1]]], tab, (id) => { tab = id; draw(); });
    const el = h('div', { class: 'stack' }, tabs, panel.el, body);
    return {
      el,
      start() { watcher = A.watchDownload(onSnap); load(); },
      stop() { if (watcher) watcher.stop(); },
      busy: () => lastState === 'running',
    };
  };

  /* ------------------------------------------------------------ መጻሕፍት */
  A.screens.books = function books() {
    const lib = A.libraryView('books', [t('books_downloaded_tab'), t('books_download_tab')]);
    const el = A.page(A.bar({ title: t('books_title') }), h('div', { class: 'scroll ruled' }, lib.el));
    return {
      el,
      onShow() { lib.start(); },
      onLeave() { lib.stop(); },
      async canLeave() {
        if (!lib.busy()) return true;
        if (!(await A.confirm(t('confirm_leave_download')))) return false;
        await A.post('/api/download/cancel');
        return true;
      },
    };
  };
})();
