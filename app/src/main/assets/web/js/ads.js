'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;
  const ads = (A.ads = { status: null, pills: new Set(), tick: null, bannerOn: true });

  const fmt = (sec) => { const m = Math.floor(sec / 60), s = sec % 60; return m + ':' + (s < 10 ? '0' : '') + s; };

  ads.refresh = async function refresh() {
    try { ads.status = await A.get('/api/ads/status'); } catch (e) { }
    ads.draw();
    return ads.status;
  };
  ads.setStatus = function setStatus(s) { if (s) { ads.status = s; ads.draw(); } };

  ads.applyBanner = function applyBanner() { document.documentElement.style.setProperty('--banner-h', '0px'); };
  ads.setBanner = function setBanner(on) {
    ads.bannerOn = !!on;
    A.post('/api/ads/banner', { visible: !!on }).catch(() => {});
  };

  ads.pill = function pill() {
    const el = h('button', { class: 'bar-btn txt pill', type: 'button', 'aria-label': t('pts_title') });
    el.addEventListener('click', () => ads.gate());
    ads.pills.add(el);
    el.draw = () => {
      const s = ads.status;
      A.clear(el).append(A.icon('star'), h('span', null, String(s ? s.points || 0 : 0)));
      el.classList.toggle('off', !(s && s.points > 0));
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
      if (!s) return;
      if (s.skip_left > 0) s.skip_left -= 1;
      if (s.turbo_left > 0) s.turbo_left -= 1;
    }, 1000);
    setTimeout(() => ads.setBanner(true), 3500);
  };

  ads.watch = async function watch() {
    const ov = A.loading(t('ad_playing'), t('pts_title'));
    let res = null;
    try {
      const r = await A.post('/api/ads/rewarded');
      res = (await A.poll(r.job_id)).result;
    } catch (e) { }
    ov.close();
    await ads.refresh();
    if (res && res.source === 'ad') { A.toast(t('pts_earned'), 3200); return true; }
    await A.info(t('ad_unavailable'), t('notice_title'));
    return false;
  };

  ads.spend = async function spend(item) {
    try {
      ads.status = await A.post('/api/ads/spend', { item });
      ads.draw();
      A.toast(t(item === 'skip' ? 'pts_skip_on' : 'pts_turbo_on'), 3200);
    } catch (e) { A.info(e.message); }
  };

  ads.gate = function gate() {
    return new Promise((resolve) => {
      const s = ads.status || {};
      const row = (label, sub, cost, item) => {
        const b = h('button', { class: 'btn sm', type: 'button', disabled: (s.points || 0) < cost }, t('pts_cost', { n: cost }));
        b.addEventListener('click', async () => { m.close(); await ads.spend(item); resolve(true); });
        return h('div', { class: 'item' }, h('div', { class: 'grow' }, h('div', null, label), h('div', { class: 'muted small' }, sub)), b);
      };
      const m = A.modal({
        title: t('pts_title'),
        body: [
          h('p', null, t('pts_balance', { n: s.points || 0 })),
          s.skip_left > 0 ? h('p', { class: 'muted small' }, t('pts_skip_left', { t: fmt(s.skip_left) })) : null,
          s.turbo_left > 0 ? h('p', { class: 'muted small' }, t('pts_turbo_left', { t: fmt(s.turbo_left) })) : null,
          row(t('pts_skip'), t('pts_skip_desc'), 1, 'skip'),
          row(t('pts_turbo'), t('pts_turbo_desc'), 2, 'turbo'),
        ].filter(Boolean),
        actions: [
          { label: t('close'), kind: 'ghost', onClick: () => resolve(false) },
          { label: t('pts_watch'), kind: 'primary', keepOpen: true, onClick: async (api) => { api.close(); resolve(await ads.watch()); return false; } },
        ],
      });
    });
  };

  ads.ensure = async function ensure() { return true; };
  ads.guard = async function guard(fn) { return fn(); };

  ads.breakPoint = async function breakPoint() {
    try { await A.post('/api/ads/interstitial'); } catch (e) { }
  };

  ads.start();
})();
