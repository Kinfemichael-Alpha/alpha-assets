/* screens.js - ጅምር (splash)፣ አማራጭ ቅጽል ስም (welcome)፣ ዋና ገጽ፣ መገናኛ እና የአፑ boot። ይህ ፋይል ከሌሎች ስክሪን ፋይሎች በኋላ ይጫናል። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;

  /* ------------------------------------------------------------ Splash: 5 ሰከንድ፣ ከ 20 አኒሜሽን አንዱ በዘፈቀደ */
  const FIDEL = ['ሀሁሂሃሄህሆ', 'ለሉሊላሌልሎ', 'ሐሑሒሓሔሕሖ', 'መሙሚማሜምሞ', 'ሠሡሢሣሤሥሦ', 'ረሩሪራሬርሮ', 'ሰሱሲሳሴስሶ', 'ሸሹሺሻሼሽሾ'];
  const MARK = '<svg viewBox="0 0 200 200" aria-hidden="true"><circle cx="100" cy="100" r="76" fill="none" stroke="currentColor" stroke-opacity=".16" stroke-width="1.5"/><path transform="translate(84.2 8.0) scale(0.32)" d="M72.66387726638773 100.0 66.38772663877266 78.80055788005579H32.49651324965133L26.08089260808926 100.0H0.0L33.89121338912134 0.0H64.57461645746164L98.74476987447699 100.0ZM55.36959553695956 39.74895397489539Q54.67224546722455 37.517433751743376 53.48675034867504 32.98465829846583Q52.30125523012553 28.45188284518828 51.11576011157601 23.57043235704323Q49.9302649930265 18.688981868898182 49.37238493723849 15.760111576011155Q48.675034867503484 18.96792189679219 47.62900976290098 23.500697350069736Q46.582984658298464 28.03347280334728 45.467224546722456 32.42677824267783Q44.35146443514645 36.82008368200837 43.51464435146443 39.74895397489539L37.79637377963738 58.99581589958159H61.08786610878661Z" fill="currentColor"/><path transform="translate(169.2 63.7) scale(0.256)" d="M2.8187919463087256 100.0 0.0 0.0H20.93959731543624L23.758389261744966 100.0Z" fill="#FFD54A"/><path transform="translate(128.7 146.4) scale(0.30111801242236025)" d="M73.43234323432344 100.0Q66.17161716171617 87.45874587458746 59.57095709570957 76.81518151815182Q52.97029702970298 66.17161716171617 46.20462046204621 56.43564356435644Q44.554455445544555 54.12541254125413 42.90429042904291 51.81518151815182Q39.43894389438944 53.46534653465346 37.458745874587464 56.43564356435644Q34.98349834983499 60.23102310231023 33.993399339934 65.18151815181518Q33.00330033003301 70.13201320132013 32.01320132013202 75.41254125412541L28.217821782178216 100.0H0.0L3.960396039603961 73.5973597359736Q5.2805280528052805 64.85148514851485 8.333333333333332 56.76567656765677Q11.386138613861386 48.67986798679868 17.986798679867984 42.40924092409241Q22.607260726072607 37.95379537953795 29.86798679867987 35.148514851485146Q27.22772277227723 31.848184818481855 24.09240924092409 27.55775577557756Q19.966996699669966 22.44224422442244 15.75907590759076 16.996699669966993Q11.55115511551155 11.551155115511548 8.003300330033003 7.013201320132012Q4.455445544554454 2.4752475247524757 2.3102310231023098 0.0H37.128712871287135Q40.429042904290434 3.960396039603964 44.38943894389439 9.405940594059409Q48.349834983498354 14.851485148514854 52.06270627062707 20.132013201320134Q55.77557755775578 25.412541254125415 58.25082508250826 29.04290429042905Q61.88118811881188 34.48844884488449 65.67656765676568 40.0990099009901Q68.31683168316832 38.44884488448845 69.96699669966996 36.13861386138614Q72.60726072607261 32.50825082508251 73.76237623762376 27.55775577557756Q74.91749174917491 22.60726072607261 75.41254125412541 17.491749174917487L77.55775577557756 0.0H105.77557755775578L103.79537953795379 19.306930693069305Q102.97029702970298 28.052805280528048 99.50495049504951 36.13861386138613Q96.03960396039604 44.224422442244226 88.94389438943895 50.33003300330033Q84.32343234323433 54.45544554455446 77.39273927392739 57.26072607260726Q83.82838283828383 66.5016501650165 90.42904290429043 75.74257425742574Q98.51485148514851 87.45874587458746 106.27062706270627 100.0Z" fill="currentColor"/><path transform="translate(39.3 150.2) scale(0.22594360086767895)" d="M110.29185867895545 100.00000000000001Q105.99078341013825 99.38556067588327 100.15360983102919 96.8509984639017Q94.31643625192012 94.31643625192014 86.71274961597543 89.24731182795699Q79.10906298003073 84.17818740399386 69.12442396313364 76.036866359447Q58.52534562211982 66.97388632872504 50.46082949308756 61.82795698924732Q42.3963133640553 56.682027649769594 35.79109062980031 54.3778801843318Q29.18586789554532 52.073732718894014 22.887864823348693 52.073732718894014H20.89093701996928Q21.966205837173582 56.221198156682036 23.19508448540707 61.82795698924732Q24.423963133640555 67.4347158218126 25.729646697388635 73.65591397849462Q27.035330261136714 79.87711213517666 28.110599078341018 86.0983102918587Q29.18586789554532 92.31950844854072 29.953917050691246 97.54224270353303H9.831029185867896Q9.216589861751151 92.78033794162828 8.371735791090629 87.86482334869433Q7.526881720430106 82.94930875576037 6.528417818740398 78.03379416282642Q5.52995391705069 73.11827956989248 4.454685099846389 68.27956989247312Q3.3794162826420884 63.44086021505377 2.4577572964669727 58.67895545314901Q1.0752688172043001 52.53456221198157 0.5376344086021501 48.61751152073733Q0.0 44.70046082949309 0.0 43.62519201228879Q0.0 33.64055299539171 6.451612903225806 31.02918586789555Q12.442396313364055 31.79723502304148 17.895545314900154 32.25806451612904Q23.34869431643625 32.7188940092166 28.341013824884794 32.872503840245784Q33.333333333333336 33.02611367127497 37.78801843317973 33.02611367127497Q54.83870967741936 33.02611367127497 71.04454685099847 29.18586789554532Q87.25038402457757 25.345622119815673 101.84331797235023 17.972350230414747Q116.43625192012288 10.599078341013822 128.72503840245778 0.0L141.6282642089094 13.671274961597547Q132.56528417818743 23.655913978494624 119.50844854070661 31.259600614439325Q106.45161290322581 38.863287250384026 91.85867895545314 43.394777265745006Q77.2657450076805 47.92626728110599 63.133640552995395 49.0015360983103V49.61597542242704Q69.12442396313364 52.2273425499232 74.19354838709677 55.37634408602151Q79.2626728110599 58.52534562211982 85.25345622119815 62.82642089093702Q90.47619047619048 66.66666666666667 95.16129032258064 69.73886328725038Q99.84639016897081 72.8110599078341 103.84024577572964 75.26881720430109Q107.83410138248848 77.88018433179724 111.9047619047619 79.56989247311829Q115.97542242703533 81.25960061443934 119.96927803379417 82.33486943164363Z" fill="currentColor"/><path transform="translate(15.6 60.5) scale(0.32)" d="M0.0 100.0 26.470588235294116 41.596638655462186Q14.145658263305323 39.91596638655462 8.54341736694678 33.12324929971989Q2.9411764705882355 26.33053221288516 2.9411764705882355 15.546218487394952V0.0H24.64985994397759V11.344537815126046Q24.64985994397759 19.04761904761905 28.0812324929972 22.408963585434172Q31.51260504201681 25.770308123249293 39.49579831932773 25.770308123249293H56.44257703081232L47.19887955182073 46.49859943977591Q55.88235294117647 49.719887955182074 61.904761904761905 55.602240896358545Q68.62745098039217 62.46498599439776 72.05882352941177 73.31932773109243Q75.49019607843138 84.17366946778711 75.63025210084034 100.0H52.661064425770306Q52.52100840336134 87.6750700280112 50.77030812324929 80.25210084033614Q49.01960784313725 72.82913165266106 45.65826330532213 68.62745098039215Q43.13725490196078 65.54621848739495 39.49579831932773 63.58543417366947L23.249299719887958 100.0Z" fill="currentColor"/><g transform="translate(100 100) scale(0.55) translate(-100 -100)" fill="none" stroke-linecap="round" stroke-linejoin="round" stroke-width="13"><path d="M58 160 L128 40" stroke="currentColor"/><path d="M128 40 L128 160" stroke="#FFD54A"/><path d="M58 160 L58 118" stroke="currentColor"/><path d="M86 112 L128 112" stroke="currentColor"/></g></svg>';
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
