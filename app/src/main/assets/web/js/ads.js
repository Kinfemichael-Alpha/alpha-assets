/* ads.js - የሽልማት ማስታወቂያ መስኮት (AI መዳረሻ)፣ የ AI ጊዜ ቆጣሪ፣ ባነር ቦታ እና interstitial።
   ደቂቃዎቹን እና ሕጉን የሚወስነው Python ነው (ads.py)፤ ይህ ፋይል ማሳያ እና ጥሪ ብቻ ነው። */
'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;
  const ads = (A.ads = { status: null, pills: new Set(), tick: null });

  const fmt = (sec) => { const m = Math.floor(sec / 60), s = sec % 60; return m + ':' + (s < 10 ? '0' : '') + s; };

  /* ----- ሁኔታ ከ Python */
  ads.refresh = async function refresh() {
    try { ads.status = await A.get('/api/ads/status'); } catch (e) { /* ችላ */ }
    ads.draw();
    return ads.status;
  };
  ads.setStatus = function setStatus(s) { if (s) { ads.status = s; ads.draw(); } };

  /* ----- ባነር: በ Android ላይ ቤተኛ እይታ ከታች ይደራረባል (ቦታ ብቻ እናስቀምጣለን)፤ በኮምፒውተር የሙከራ ምልክት */
  ads.bannerEl = null;
  ads.bannerOn = true;
  ads.applyBanner = function applyBanner() {
    const s = ads.status;
    const root = document.documentElement.style;
    const show = ads.bannerOn && s && (s.fake_banner || s.banner_dp > 0);
    let el = document.getElementById('banner');
    if (!el) { el = h('div', { id: 'banner', 'aria-hidden': 'true' }); document.body.append(el); }
    el.hidden = !show;
    A.clear(el);
    if (show && s.fake_banner) el.append(h('span', null, t('ad_banner_test')));
    root.setProperty('--banner-h', show ? (s.fake_banner ? 50 : s.banner_dp) + 'px' : '0px');
  };
  ads.setBanner = function setBanner(on) {
    ads.bannerOn = !!on;
    ads.applyBanner();
    A.post('/api/ads/banner', { visible: !!on }).catch(() => {});   // ቤተኛውን ባነር ደብቅ/አሳይ
  };

  /* ----- የ AI ጊዜ ቆጣሪ (pill): በየሰከንዱ በአካባቢው ይቀንሳል፤ ሲያልቅ ያሳውቃል */
  ads.pill = function pill() {
    const el = h('button', { class: 'bar-btn txt pill', type: 'button', 'aria-label': t('ad_gate_title') });
    el.addEventListener('click', () => ads.gate());
    ads.pills.add(el);
    el.draw = () => {
      const s = ads.status;
      el.textContent = s && s.remaining > 0 ? t('ad_remaining', { t: fmt(s.remaining) }) : t('ad_gate_title');
      el.classList.toggle('off', !(s && s.remaining > 0));
    };
    el.draw();
    return el;
  };
  ads.draw = function draw() {
    ads.pills.forEach((p) => { if (p.isConnected) p.draw(); else ads.pills.delete(p); });
    ads.applyBanner();
  };
  ads.start = function start() {
    if (ads.tick) return;
    ads.tick = setInterval(() => {
      const s = ads.status;
      if (!s || !(s.remaining > 0)) return;
      s.remaining -= 1;
      if (s.remaining <= 0) { s.active = false; A.toast(t('ad_expired'), 4500); }
      ads.pills.forEach((p) => { if (p.isConnected) p.draw(); });
    }, 1000);
  };

  /* ----- መስኮት: "|Watch ad| +N ደቂቃ". true = AI ተከፍቷል */
  ads.gate = function gate(opts) {
    opts = opts || {};
    return new Promise((resolve) => {
      let done = false, watching = false;
      const finish = (v) => { if (!done) { done = true; resolve(v); } };
      const next = (ads.status && ads.status.next_minutes) || 5;
      const m = A.modal({
        title: t('ad_gate_title'),
        body: [h('p', null, opts.message || t('ad_gate_body', { min: next }))],
        actions: [
          { label: t('btn_later'), kind: 'ghost', onClick: () => finish(false) },
          { label: t('ad_watch_btn', { min: next }), kind: 'primary', keepOpen: true,
            onClick: async (api) => { watching = true; api.close(); finish(await ads.watch()); return false; } },
        ],
      });
      m.onClose = () => { if (!watching) finish(false); };   // ማስታወቂያ እየታየ መስኮቱ ሲዘጋ ውጤቱን አያበላሽም
    });
  };

  /* ማስታወቂያውን ማሳየት: ውጤቱን ወዳጃዊ መልእክት ያሳያል (ተጠቃሚው በጭራሽ አይቆለፍም) */
  ads.watch = async function watch() {
    const ov = A.loading(t('ad_playing'), t('ad_gate_title'));
    let res;
    try {
      const r = await A.post('/api/ads/rewarded');
      const j = await A.poll(r.job_id);
      res = j.result;
    } catch (e) {
      ov.close();
      // የመጨረሻ መከላከያ: ከ Python ምላሽ ካልመጣ እንኳ ተጠቃሚው አይቆለፍም (Python ጎን fallback አዘጋጅቷል)
      await ads.refresh();
      return !!(ads.status && ads.status.active);
    }
    ov.close();
    await ads.refresh();
    if (res.source === 'ad') { A.toast(t('ad_unlocked', { min: res.minutes }), 3800); return true; }
    if (res.source === 'fallback') { await A.info(t('ad_fallback', { min: res.minutes }), t('notice_title')); return true; }
    if (res.reason === 'busy') { A.toast(t('ad_busy')); return false; }
    await A.info(t('ad_dismissed'), t('notice_title'));
    return false;
  };

  /* AI ጥሪ ጠባቂ: ተከፍቶ ከሆነ ይቀጥላል፤ ካልሆነ መስኮቱን ያሳያል */
  ads.ensure = async function ensure(opts) {
    await ads.refresh();
    if (ads.status && ads.status.active) return true;
    return ads.gate(opts);
  };

  /* ቤተኛ ያልሆነ ጥሪ: 402 ai_locked ከተመለሰ መስኮቱን አሳይቶ አንድ ጊዜ እንደገና ይሞክራል */
  ads.guard = async function guard(fn) {
    try { return await fn(); } catch (e) {
      if (e && e.data && e.data.error === 'ai_locked') {
        await ads.refresh();
        if (await ads.gate({ message: t('ai_locked') })) return fn();
      }
      throw e;
    }
  };

  /* interstitial: ተፈጥሯዊ እረፍት (ፈተና ከተጠናቀቀ በኋላ) ብቻ - ፖሊሲው በ Python ይወሰናል */
  ads.breakPoint = async function breakPoint() {
    try {
      const r = await A.post('/api/ads/interstitial');
      if (r.shown && r.job_id) { await A.poll(r.job_id); }
    } catch (e) { /* ችላ: ማስታወቂያ ባይኖር አፑ ይቀጥላል */ }
  };

  ads.start();
})();
