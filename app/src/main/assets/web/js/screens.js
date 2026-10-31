/* screens.js - ጅምር (splash)፣ አማራጭ ቅጽል ስም (welcome)፣ ዋና ገጽ፣ መገናኛ እና የአፑ boot። ይህ ፋይል ከሌሎች ስክሪን ፋይሎች በኋላ ይጫናል። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ Splash: 5 ሰከንድ፣ ከ 20 አኒሜሽን አንዱ በዘፈቀደ */
  const FIDEL = ['ሀሁሂሃሄህሆ', 'ለሉሊላሌልሎ', 'ሐሑሒሓሔሕሖ', 'መሙሚማሜምሞ', 'ሠሡሢሣሤሥሦ', 'ረሩሪራሬርሮ', 'ሰሱሲሳሴስሶ', 'ሸሹሺሻሼሽሾ'];
  const MARK = '<svg viewBox="0 0 200 200" aria-hidden="true"><g fill="none" stroke-linecap="round" stroke-linejoin="round" stroke-width="12"><path d="M58 160L128 40M58 160L58 118M86 112L128 112" stroke="currentColor"/><path d="M128 40L128 160" stroke="#FFD54A"/></g></svg>';
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
      A.bar({ title: t('app_title'), back: false, right: [langBtn] }),
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
    const tile = (cls, icon, label, screen) => h('button', { class: 'tile ' + cls, type: 'button', onclick: () => A.go(screen) }, A.icon(icon), h('span', null, label));
    const langBtn = h('button', { class: 'bar-btn txt', type: 'button', onclick: () => A.setLang(A.S.lang === 'AM' ? 'EN' : 'AM') }, t('lang_btn'));
    const setBtn = h('button', { class: 'bar-btn', type: 'button', 'aria-label': t('settings_title'), onclick: () => A.go('settings') }, A.icon('sliders'));
    const el = A.page(
      A.bar({ title: t('dashboard_title'), back: false, right: [langBtn, setBtn] }),
      h('div', { class: 'scroll ruled stack' },
        h('h1', { class: 'hello' }, nick ? t('home_greeting', { name: nick }) : t('dashboard_question')),
        h('div', { class: 'tiles' },
          tile('main c-green', 'chat', t('btn_ai'), 'ai'),
          tile('c-blue', 'quiz', t('btn_quiz'), 'quiz'),
          tile('c-teal', 'note', t('btn_notes'), 'notes'),
          tile('c-amber', 'book', t('btn_books'), 'books'),
          tile('c-purple', 'chart', t('btn_progress'), 'progress'),
          tile('wide c-accent', 'phone', t('btn_contact'), 'contact'))));
    return { el };
  };

  /* ------------------------------------------------------------ መገናኛ */
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
        h('p', { class: 'muted' }, t('dev_role')),
        any ? null : h('p', { class: 'muted' }, t('contact_none')),
        ...rows(c, 'phone', t('call_me'), 'btn_call'),
        ...rows(c, 'telegram', t('telegram'), 'btn_telegram'),
        ...rows(c, 'email', t('email'), 'btn_email')));
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
      A.S.strings = i18n.strings;
      A.S.lang = state.settings.language;
      document.documentElement.lang = A.S.lang === 'AM' ? 'am' : 'en';
      A.applyTheme(state.settings.palette); A.applyMode(state.settings.appearance);
      A.ads.setStatus(state.ads);       // ባነር ቦታ + የ AI ጊዜ ሁኔታ
    })();
    try { await Promise.all([A.sleep(5000), load]); } catch (e) { return fatal(e); }
    // ራስ-ሰር አቅጣጫ: የመጀመሪያ ጊዜ -> አማራጭ ቅጽል ስም ገጽ፣ ከዚያ በኋላ -> ዋና ገጽ
    await A.go(A.S.state.onboarded ? 'home' : 'welcome', {}, { root: true, force: true });
    if (A.S.state.crash_model) {
      const name = A.S.state.crash_model;
      A.S.state.crash_model = '';
      A.post('/api/crash/ack').catch(() => {});
      await A.info(t('crash_notice', { name }), t('notice_title'));
    }
  }
  boot();
})();
