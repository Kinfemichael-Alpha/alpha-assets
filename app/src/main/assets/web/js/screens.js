/* screens.js - ጅምር (splash)፣ አማራጭ ቅጽል ስም (welcome)፣ ዋና ገጽ፣ መገናኛ እና የአፑ boot። ይህ ፋይል ከሌሎች ስክሪን ፋይሎች በኋላ ይጫናል። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ Splash: 5 ሰከንድ፣ ከ 20 አኒሜሽን አንዱ በዘፈቀደ */
  const FIDEL = ['ሀሁሂሃሄህሆ', 'ለሉሊላሌልሎ', 'ሐሑሒሓሔሕሖ', 'መሙሚማሜምሞ', 'ሠሡሢሣሤሥሦ', 'ረሩሪራሬርሮ', 'ሰሱሲሳሴስሶ', 'ሸሹሺሻሼሽሾ'];
  const MARK = '<svg viewBox="0 0 200 200" aria-hidden="true"><g fill="none" stroke-linecap="round" stroke-linejoin="round" stroke-width="12"><path d="M58 160L128 40M58 160L58 118M86 112L128 112" stroke="currentColor"/><path d="M128 40L128 160" stroke="#FFD54A"/></g></svg>';
  A.EMBLEM = MARK;
  A.screens.splash = function splash() {
    const n = Math.floor(Math.random() * 20);   // sp0 .. sp19
    const letters = 'ALPHA AI'.split('').map((c, i) => h('span', { class: 'ch', style: { '--i': i } }, c === ' ' ? ' ' : c));
    const mark = h('div', { class: 'splash-mark', 'aria-hidden': 'true' });
    mark.innerHTML = MARK;
    const el = h('section', { class: 'splash' },
      h('div', { class: 'fidel', 'aria-hidden': 'true' }, ...FIDEL.map((r) => h('span', null, r))),
      h('div', { class: 'splash-c' },
        mark,
        h('h1', { class: 'logo sp' + n, 'aria-label': 'ALPHA AI' }, letters),
        h('p', { class: 'dev' }, 'developed by Kinfemichael')),
      h('div', { class: 'splash-bar' }, h('i')));
    return { el };
  };

  /* ------------------------------------------------------------ እንኳን ደህና መጡ: አማራጭ ቅጽል ስም (መዝለል ይቻላል) */
  A.screens.welcome = function welcome(p) {
    const v = Object.assign({ nick: (A.S.state && A.S.state.nickname) || '' }, p.v || {});
    const inp = h('input', { class: 'inp', type: 'text', maxlength: '40', autocomplete: 'nickname', 'aria-label': t('nick_label') });
    inp.value = v.nick;
    inp.addEventListener('input', () => { v.nick = inp.value; });
    async function done(save) {
      try {
        const r = await A.post('/api/profile', { nickname: save ? v.nick.trim() : '', onboarded: true });
        A.S.state.nickname = r.nickname;
        A.S.state.onboarded = true;
      } catch (e) { /* ቅጽል ስም አማራጭ ነው: ስህተት ቢኖር እንኳ አፑ ይቀጥላል */ }
      A.go('home', {}, { root: true });
    }
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') done(true); });
    const langBtn = h('button', { class: 'bar-btn txt', type: 'button', onclick: () => A.setLang(A.S.lang === 'AM' ? 'EN' : 'AM') }, t('lang_btn'));
    const el = A.page(
      A.bar({ title: t('app_title'), back: false, right: [langBtn], pts: false }),
      h('div', { class: 'scroll ruled stack' },
        h('h1', { class: 'hello' }, t('welcome_title')),
        h('div', { class: 'card stack' },
          h('p', null, t('welcome_body')),
          h('label', { class: 'fld' }, h('span', null, t('nick_label')), inp),
          h('button', { class: 'btn block', type: 'button', onclick: () => done(true) }, t('btn_continue')),
          h('button', { class: 'btn ghost block', type: 'button', onclick: () => done(false) }, t('btn_skip')))));
    return { el, getParams: () => ({ v }) };
  };

  /* ------------------------------------------------------------ ዋና ገጽ */
  A.screens.home = function home() {
    const nick = A.S.state.nickname;
    const tile = (cls, icon, label, screen, ok = true) => h('button', { class: 'tile ' + cls + (ok ? '' : ' lock'), type: 'button', onclick: () => (ok ? A.go(screen) : A.upsell()) }, A.icon(icon), h('span', null, label));
    const updBox = h('div', { class: 'card tint row' }, h('span', { class: 'grow' }, t('upd_available')),
      h('button', { class: 'btn sm', type: 'button', onclick: () => A.post('/api/update/open').then((r) => { if (r && r.started) A.info(t('upd_started')); }).catch((e) => A.info(e.message)) }, t('upd_download')));
    updBox.hidden = true;
    const langBtn = h('button', { class: 'bar-btn txt', type: 'button', onclick: () => A.setLang(A.S.lang === 'AM' ? 'EN' : 'AM') }, t('lang_btn'));
    const setBtn = h('button', { class: 'bar-btn', type: 'button', 'aria-label': t('settings_title'), onclick: () => A.go('settings') }, A.icon('sliders'));
    const el = A.page(
      A.bar({ title: t('dashboard_title'), back: false, right: [langBtn, setBtn] }),
      h('div', { class: 'scroll ruled stack' },
        h('h1', { class: 'hello' }, nick ? t('home_greeting', { name: nick }) : t('dashboard_question')),
        updBox,
        A.lic.badge(),
        A.ads.card(),
        h('div', { class: 'tiles' },
          tile('main c-green', 'chat', t('btn_ai'), 'ai', A.hasAI()),
          tile('c-blue', 'exam', t('btn_exams'), 'exams'),
          tile('c-purple', 'sparkle', t('btn_generated'), 'generated', A.can('generated')),
          tile('c-blue', 'quiz', t('btn_quiz'), 'quiz', A.hasAI()),
          tile('c-teal', 'note', t('btn_notes'), 'notes', A.hasAI()),
          tile('c-amber', 'book', t('btn_books'), 'books'),
          tile('c-purple', 'chart', t('btn_progress'), 'progress'),
          tile('wide c-accent', 'phone', t('btn_contact'), 'contact'))));
    return { el, onShow() { A.get('/api/update').then((r) => { updBox.hidden = !r.available; }).catch(() => {}); } };
  };

  /* ------------------------------------------------------------ መገናኛ */
  A.screens.report = function report() {
    const text = h('textarea', { class: 'inp', rows: '6', maxlength: '1500', placeholder: t('report_ph'), 'aria-label': t('report_title') });
    const contact = h('input', { class: 'inp', type: 'text', maxlength: '80', placeholder: t('report_contact'), 'aria-label': t('report_contact') });
    const go = h('button', { class: 'btn block', type: 'button' }, t('report_send'));
    go.addEventListener('click', async () => {
      if (go.disabled) return;
      if (!text.value.trim()) return A.info(t('report_empty'));
      if (navigator.onLine === false) return A.info(t('lic_offline'));
      go.disabled = true;
      try {
        await A.post('/api/report/send', { text: text.value, contact: contact.value, js: A.lastError || '' });
        await A.info(t('report_sent'));
        A.back();
      } catch (e) { await A.info(e.message); } finally { go.disabled = false; }
    });
    return { el: A.page(A.bar({ title: t('report_title') }), h('div', { class: 'scroll ruled stack' },
      h('div', { class: 'card stack' }, h('p', { class: 'muted small' }, t('report_note')), text, contact, go))) };
  };

  A.screens.contact = function contact() {
    const body = h('div', { class: 'stack' });
    async function open(kind, value) {
      try { await A.post('/api/contacts/open', { kind, value }); } catch (e) { A.info(e.message); }
    }
    const rows = (c, kind, label, btnKey) => c[kind].map((val) => h('div', { class: 'item' },
      h('div', { class: 'grow' }, h('div', { class: 'muted small' }, label), A.mq(val)),
      h('button', { class: 'btn sm', type: 'button', onclick: () => open(kind, val) }, t(btnKey))));
    async function load() {
      let c;
      try { c = await A.get('/api/contacts'); } catch (e) { return A.info(e.message); }
      const any = c.phone.length || c.telegram.length || c.email.length;
      A.clear(body).append(h('div', { class: 'card' },
        h('h1', { class: 'hello' }, c.name[0] || 'Kinfemichael'),
        any ? null : h('p', { class: 'muted' }, t('contact_none')),
        ...rows(c, 'phone', t('call_me'), 'btn_call'),
        ...rows(c, 'telegram', t('telegram'), 'btn_telegram'),
        ...rows(c, 'email', t('email'), 'btn_email')),
        h('div', { class: 'card' },
          h('h1', { class: 'hello' }, t('report_title')),
          h('div', { class: 'item' }, h('div', { class: 'grow' }, t('report_desc')), h('button', { class: 'btn sm', type: 'button', onclick: () => A.go('report') }, t('report_btn')))),
        h('div', { class: 'card' },
          h('h1', { class: 'hello' }, t('community_title')),
          ...[['channel', 'community_channel'], ['group', 'community_group']].map(([w, k]) => h('div', { class: 'item' },
            h('div', { class: 'grow' }, h('div', { class: 'muted small' }, t(k)), h('div', null, t(k + '_desc'))),
            h('button', { class: 'btn sm', type: 'button', onclick: async () => { try { await A.post('/api/community/open', { which: w }); } catch (e) { A.info(e.message); } } }, t('btn_join'))))));
      A.scheduleMarquees();
    }
    return { el: A.page(A.bar({ title: t('contact_title') }), h('div', { class: 'scroll ruled' }, body)), onShow() { load(); } };
  };

  /* ------------------------------------------------------------ boot */
  function fatal(e) {
    const app = document.getElementById('app');
    A.clear(app).append(h('section', { class: 'screen' }, h('div', { class: 'scroll stack' },
      h('h1', { class: 'hello' }, 'ALPHA AI'),
      h('div', { class: 'card warn' }, h('p', null, (e && e.message) || 'Error')),
      h('button', { class: 'btn', type: 'button', onclick: () => location.reload() }, 'Retry'))));
  }

  async function boot() {
    A.installBackHandler();
    await A.go('splash', {}, { root: true });
    const load = (async () => {
      const [state, i18n] = await Promise.all([A.get('/api/state'), A.get('/api/i18n')]);
      A.S.state = state;
      A.lic.apply(state.license);
      A.S.strings = i18n.strings;
      A.S.lang = state.settings.language;
      document.documentElement.lang = A.S.lang === 'AM' ? 'am' : 'en';
      A.applyTheme(state.settings.palette); A.applyMode(state.settings.appearance);
      A.ads.setStatus(state.ads);       // ባነር ቦታ + የ AI ጊዜ ሁኔታ
    })();
    try { await Promise.all([A.sleep(5000), load]); } catch (e) { return fatal(e); }
    // ራስ-ሰር አቅጣጫ: የመጀመሪያ ጊዜ -> አማራጭ ቅጽል ስም ገጽ፣ ከዚያ በኋላ -> ዋና ገጽ
    await A.lic.route();
    A.lic.start();
    try {
      const pr = await A.get('/api/report/pending');
      if (pr && pr.crash) {
        if (await A.confirm(t('report_crash_ask'))) {
          try { await A.post('/api/report/send', { crash: true }); await A.info(t('report_sent')); } catch (e) { await A.info(e.message); }
        } else await A.post('/api/report/dismiss');
      }
    } catch (e) { }
    if (A.S.state.crash_model) {
      const name = A.S.state.crash_model;
      A.S.state.crash_model = '';
      A.post('/api/crash/ack').catch(() => {});
      await A.info(t('crash_notice', { name }), t('notice_title'));
    }
  }
  boot();
})();
