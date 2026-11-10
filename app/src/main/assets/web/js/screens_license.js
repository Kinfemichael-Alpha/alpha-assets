'use strict';
(function () {
  const A = window.A, h = A.h, t = A.t;
  const lic = (A.lic = { v: null, timer: null, trialEnd: 0, tick: 0 });
  const GATED = ['none', 'trial_expired', 'locked'];
  const OWN = ['license', 'licreg', 'lictrial', 'licredeem', 'liccheck', 'licmove', 'licpending', 'liccode', 'licnotice'];

  A.can = (k) => !!(lic.v && lic.v.features && lic.v.features[k]);
  A.hasAI = () => A.can('ai_offline') || A.can('ai_online');
  A.examMode = () => !!(lic.v && lic.v.features && lic.v.features.past_papers === 'exam');

  lic.apply = function apply(v) {
    if (!v) return v;
    lic.v = v;
    A.S.license = v;
    lic.trialEnd = v.state === 'trial' ? Date.now() + (v.trial_left || 0) * 1000 : 0;
    if (A.ads && A.ads.refresh) A.ads.refresh();
    return v;
  };
  lic.refresh = async function refresh() {
    try { lic.apply(await A.get('/api/license')); } catch (e) { }
    return lic.v;
  };
  lic.sync = async function sync() {
    try { lic.apply(await A.post('/api/license/refresh')); } catch (e) { }
    return lic.v;
  };

  lic.route = async function route() {
    const v = lic.v;
    if (v.wiped) {
      try { lic.apply(await A.post('/api/license/ack_notice')); } catch (e) { }
      await A.info(t('lic_wiped'), t('notice_title'));
    }
    if (lic.v.rejected) {
      try { lic.apply(await A.post('/api/license/ack_notice')); } catch (e) { }
      await A.info(t('lic_rejected'), t('notice_title'));
    }
    if (!lic.v.notice && GATED.includes(lic.v.state)) return A.go('licnotice', {}, { root: true, force: true });
    if (lic.v.code) return A.go('liccode', {}, { root: true, force: true });
    if (GATED.includes(lic.v.state)) return A.go('license', {}, { root: true, force: true });
    if (lic.v.state === 'pending') return A.go('licpending', {}, { root: true, force: true });
    return A.go(A.S.state.onboarded ? 'home' : 'welcome', {}, { root: true, force: true });
  };

  A.onLicenseRequired = function onLicenseRequired() {
    const cur = A.current();
    if (cur && OWN.includes(cur.name)) return;
    lic.refresh().then(() => lic.route());
  };

  lic.start = function start() {
    if (lic.timer) return;
    lic.timer = setInterval(async () => {
      const cur = A.current();
      if (!cur || cur.name === 'splash' || OWN.includes(cur.name) || !lic.v) return;
      lic.tick++;
      const trialOver = lic.v.state === 'trial' && Date.now() >= lic.trialEnd;
      if (!trialOver && lic.tick % 30) return;
      const old = lic.v.state;
      await lic.refresh();
      if (lic.v.state !== old || lic.v.wiped || trialOver) {
        if (GATED.includes(lic.v.state) || lic.v.wiped || lic.v.code) {
          if (lic.v.state === 'trial_expired') await A.info(t('lic_trial_over'), t('notice_title'));
          lic.route();
        }
      }
    }, 1000);
  };

  const price = (f) => (f && f.price > 0 ? t('lic_price', { p: f.price, c: (lic.v && lic.v.currency) || 'ETB' }) : t('lic_price_soon'));

  function featList(f) {
    const out = [];
    out.push(t(f.past_papers === 'exam' ? 'lic_f_exam' : 'lic_f_view'));
    if (f.ai_offline) out.push(t('lic_f_ai_off'));
    if (f.ai_online) out.push(t('lic_f_ai_on'));
    if (f.generated) out.push(t('lic_f_gen'));
    if (f.daily_questions > 0) out.push(t('lic_f_daily', { n: f.daily_questions }));
    if (f.custom) out.push(t('lic_f_custom'));
    out.push(t(f.ads ? 'lic_f_ads' : 'lic_f_noads'));
    out.push(t(f.key_mode === 'ours' ? 'lic_f_key_ours' : f.key_mode === 'own' ? 'lic_f_key_own' : 'lic_f_key_none'));
    return out;
  }

  const phoneInput = (val) => {
    const i = h('input', { class: 'inp', type: 'tel', inputmode: 'tel', maxlength: '16', autocomplete: 'tel', placeholder: '09xxxxxxxx', 'aria-label': t('lic_phone') });
    i.value = val || '';
    return i;
  };

  function policyBox() {
    const v = lic.v;
    const custom = v && v.policy && (v.policy[A.S.lang] || v.policy.EN);
    const cb = h('input', { type: 'checkbox', 'aria-label': t('lic_policy_accept') });
    const box = h('div', { class: 'card stack' },
      h('b', null, t('lic_policy_title')),
      h('p', { class: 'muted small', style: { whiteSpace: 'pre-line' } }, custom || t('lic_policy')),
      h('label', { class: 'row' }, cb, h('span', { class: 'grow' }, t('lic_policy_accept'))));
    return { el: box, ok: () => cb.checked };
  }

  function busy(btn, fn, local) {
    return async () => {
      if (btn.disabled) return;
      if (!local && navigator.onLine === false) return A.info(t('lic_offline'), t('notice_title'));
      btn.disabled = true;
      try { await fn(); } catch (e) { await A.info((e && e.message) || t('err_server')); } finally { btn.disabled = false; }
    };
  }

  A.screens.license = function license() {
    const v = lic.v;
    const gated = GATED.includes(v.state);
    const body = h('div', { class: 'scroll ruled stack' });
    const msgKey = v.state === 'trial_expired' ? 'lic_trial_over' : v.state === 'locked' ? 'lic_locked' : 'lic_intro';
    body.append(h('h1', { class: 'hello' }, t('lic_title')), h('div', { class: 'card tint' }, h('p', null, t(msgKey, { d: v.offline_days }))));
    if (v.state === 'locked') {
      const b = h('button', { class: 'btn block', type: 'button' }, t('lic_check_now'));
      b.addEventListener('click', busy(b, async () => {
        lic.apply(await A.post('/api/license/refresh'));
        if (lic.v.state === 'locked') return A.info(t('lic_still_locked'));
        return lic.route();
      }));
      body.append(b);
    }
    if (v.state !== 'locked' && (v.state === 'none' || v.state === 'trial_expired')) {
      const f = h('button', { class: 'btn block', type: 'button' }, t('lic_continue_free'));
      f.addEventListener('click', busy(f, async () => { lic.apply(await A.post('/api/license/free')); return lic.route(); }, true));
      body.append(f);
    }
    if (v.state === 'none') body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('lictrial') }, t('lic_trial_btn')));
    if (v.tier < 5 && v.state !== 'locked') body.append(h('button', { class: 'btn block', type: 'button', onclick: () => A.go('licreg') }, t('lic_register_btn')));
    body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('liccheck') }, t('lic_check_pay_btn')));
    body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('licredeem') }, t('lic_redeem_btn')));
    body.append(h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.go('licmove') }, t('lic_move_btn')));
    body.append(h('h2', { class: 'h-sec' }, t('lic_levels')));
    for (let i = 0; i <= 5; i++) {
      const f = v.tiers[String(i)];
      if (!f || i === 1) continue;
      body.append(h('div', { class: 'card stack' }, h('div', { class: 'row' }, h('b', { class: 'grow' }, t('lic_level', { n: i, name: f.name })), h('span', { class: 'muted' }, i === 0 ? '' : price(f))),
        h('ul', { class: 'muted small' }, ...featList(f).map((x) => h('li', null, x)))));
    }
    return { el: A.page(A.bar({ title: t('lic_title'), back: !gated, pts: false }), body) };
  };

  A.screens.lictrial = function lictrial() {
    const phone = phoneInput('');
    const pol = policyBox();
    const go = h('button', { class: 'btn block', type: 'button' }, t('lic_trial_start'));
    go.addEventListener('click', busy(go, async () => {
      if (!pol.ok()) return A.info(t('lic_policy_need'));
      if (!phone.value.trim()) return A.info(t('lic_err_bad_phone'));
      lic.apply(await A.post('/api/license/trial', { phone: phone.value }));
      return lic.route();
    }));
    return { el: A.page(A.bar({ title: t('lic_trial_btn'), pts: false }), h('div', { class: 'scroll ruled stack' },
      pol.el, h('div', { class: 'card stack' }, h('p', null, t('lic_trial_info')), h('label', { class: 'fld' }, h('span', null, t('lic_phone')), phone), go))) };
  };

  A.screens.licreg = function licreg(p) {
    const v = lic.v;
    const st = Object.assign({ tier: Math.max(2, (v.tier || 0) + 1), method: 'telebirr', phone: v.phone || '', txn: '', pname: '', last4: '', amount: '' }, p.v || {});
    const tiersBox = h('div', { class: 'stack' });
    const payBox = h('div', { class: 'card tint stack' });
    const phone = phoneInput(st.phone);
    const txn = h('input', { class: 'inp', type: 'text', maxlength: '32', autocapitalize: 'characters', autocomplete: 'off', 'aria-label': t('lic_txn') });
    txn.value = st.txn;
    phone.addEventListener('input', () => { st.phone = phone.value; });
    txn.addEventListener('input', () => { st.txn = txn.value; });
    const mk = (key, attrs, label) => { const i = h('input', Object.assign({ class: 'inp', autocomplete: 'off', 'aria-label': t(label) }, attrs)); i.value = st[key]; i.addEventListener('input', () => { st[key] = i.value; }); return i; };
    const pname = mk('pname', { type: 'text', maxlength: '60' }, 'lic_payer_name');
    const last4 = mk('last4', { type: 'text', inputmode: 'numeric', maxlength: '4' }, 'lic_payer_last4');
    const amount = mk('amount', { type: 'text', inputmode: 'decimal', maxlength: '9' }, 'lic_payer_amount');
    const detailBox = h('div', { class: 'card stack' });
    function drawDetails() {
      const fld = (label, el) => h('label', { class: 'fld' }, h('span', null, t(label)), el);
      A.clear(detailBox).append(...(st.method === 'cbe'
        ? [h('p', { class: 'muted small' }, t('lic_cbe_info')), fld('lic_payer_name', pname), fld('lic_payer_last4', last4), fld('lic_payer_amount', amount)]
        : [fld('lic_txn', txn)]));
    }
    if (v.phone) phone.readOnly = true;

    function drawTiers() {
      A.clear(tiersBox);
      for (let i = 2; i <= 5; i++) {
        if (i <= v.tier) continue;
        const f = v.tiers[String(i)];
        if (!f) continue;
        tiersBox.append(h('button', { class: 'opt' + (st.tier === i ? ' ok' : ''), type: 'button', onclick: () => { st.tier = i; drawTiers(); drawPay(); } },
          h('span', { class: 'ltr' }, String(i)),
          h('span', { class: 'grow' }, h('b', null, f.name + ' - ' + price(f)), h('ul', { class: 'muted small' }, ...featList(f).map((x) => h('li', null, x))))));
      }
    }
    function drawPay() {
      const f = v.tiers[String(st.tier)] || {};
      const num = (v.pay_to && v.pay_to[st.method]) || '';
      A.clear(payBox).append(
        h('div', { class: 'row' },
          ...['telebirr', 'cbe'].map((m) => h('button', { class: 'btn sm' + (st.method === m ? '' : ' ghost'), type: 'button', onclick: () => { st.method = m; drawPay(); drawDetails(); } }, t('lic_m_' + m)))),
        h('p', null, t('lic_pay_howto', { p: price(f), m: t('lic_m_' + st.method) })),
        (v.pay_name && v.pay_name[st.method]) ? h('p', { class: 'muted small' }, t('lic_pay_name', { n: v.pay_name[st.method] })) : null,
        h('div', { class: 'row' }, h('b', { class: 'grow' }, num || '-'), num ? h('button', { class: 'btn sm ghost', type: 'button', onclick: () => A.copy(num) }, t('act_copy')) : null));
    }
    const pol = policyBox();
    const go = h('button', { class: 'btn block', type: 'button' }, t('lic_submit'));
    go.addEventListener('click', busy(go, async () => {
      if (!pol.ok()) return A.info(t('lic_policy_need'));
      if (!phone.value.trim()) return A.info(t('lic_err_bad_phone'));
      if (st.method === 'cbe') {
        if (st.pname.trim().length < 3) return A.info(t('lic_err_bad_payer_name'));
        if (!/^\d{4}$/.test(st.last4.trim())) return A.info(t('lic_err_bad_last4'));
        if (!(parseFloat(st.amount) > 0)) return A.info(t('lic_err_bad_amount'));
      } else if (!txn.value.trim()) return A.info(t('lic_err_bad_txn'));
      lic.apply(await A.post('/api/license/register', { phone: phone.value, tier: st.tier, txn_id: txn.value, method: st.method, payer_name: st.pname, payer_last4: st.last4, amount: st.amount }));
      await A.info(t('lic_submitted'), t('notice_title'));
      return lic.route();
    }));
    drawTiers(); drawPay(); drawDetails();
    return { el: A.page(A.bar({ title: t('lic_register_btn'), pts: false }), h('div', { class: 'scroll ruled stack' },
      tiersBox, payBox, detailBox, pol.el, h('div', { class: 'card stack' }, h('label', { class: 'fld' }, h('span', null, t('lic_phone')), phone), go))),
      getParams: () => Object.assign({}, p, { v: st }) };
  };

  A.screens.licnotice = function licnotice() {
    const days = (lic.v && lic.v.offline_days) || 7;
    const secs = [1, 2, 3, 4, 5].map((n) => h('div', { class: 'card stack' }, h('b', null, t('notice_h' + n)), h('p', { class: 'small', style: { whiteSpace: 'pre-line' } }, t('notice_p' + n, { d: days }))));
    const cb = h('input', { type: 'checkbox', 'aria-label': t('notice_agree') });
    const go = h('button', { class: 'btn block', type: 'button', disabled: true }, t('notice_next'));
    cb.addEventListener('change', () => { go.disabled = !cb.checked; });
    go.addEventListener('click', busy(go, async () => {
      lic.apply(await A.post('/api/license/notice_ok'));
      return lic.route();
    }, true));
    return { el: A.page(A.bar({ title: t('notice_title'), back: false, pts: false }), h('div', { class: 'scroll ruled stack' },
      ...secs, h('label', { class: 'row card' }, cb, h('span', { class: 'grow' }, t('notice_agree'))), go)) };
  };

  A.screens.liccheck = function liccheck() {
    const phone = phoneInput('');
    const go = h('button', { class: 'btn block', type: 'button' }, t('lic_check_go'));
    go.addEventListener('click', busy(go, async () => {
      if (!phone.value.trim()) return A.info(t('lic_err_bad_phone'));
      lic.apply(await A.post('/api/license/check', { phone: phone.value }));
      return lic.route();
    }));
    return { el: A.page(A.bar({ title: t('lic_check_pay_btn'), pts: false }), h('div', { class: 'scroll ruled stack' },
      h('div', { class: 'card stack' }, h('p', null, t('lic_check_info')), h('label', { class: 'fld' }, h('span', null, t('lic_phone')), phone), go))) };
  };

  A.screens.licredeem = function licredeem() {
    const phone = phoneInput('');
    const code = h('input', { class: 'inp', type: 'text', maxlength: '12', autocapitalize: 'characters', autocomplete: 'off', placeholder: 'XXXX-XXXX', 'aria-label': t('lic_invite') });
    const pol = policyBox();
    const go = h('button', { class: 'btn block', type: 'button' }, t('lic_redeem_go'));
    go.addEventListener('click', busy(go, async () => {
      if (!pol.ok()) return A.info(t('lic_policy_need'));
      if (!phone.value.trim()) return A.info(t('lic_err_bad_phone'));
      if (!code.value.trim()) return A.info(t('lic_err_bad_code'));
      lic.apply(await A.post('/api/license/redeem', { phone: phone.value, code: code.value }));
      return lic.route();
    }));
    return { el: A.page(A.bar({ title: t('lic_redeem_btn'), pts: false }), h('div', { class: 'scroll ruled stack' },
      pol.el, h('div', { class: 'card stack' }, h('p', null, t('lic_redeem_info')), h('label', { class: 'fld' }, h('span', null, t('lic_phone')), phone),
        h('label', { class: 'fld' }, h('span', null, t('lic_invite')), code), go))) };
  };

  A.screens.licmove = function licmove() {
    const phone = phoneInput('');
    const code = h('input', { class: 'inp', type: 'text', maxlength: '16', autocapitalize: 'characters', autocomplete: 'off', placeholder: 'XXXXX-XXXXX', 'aria-label': t('lic_code') });
    const go = h('button', { class: 'btn block', type: 'button' }, t('lic_move_go'));
    go.addEventListener('click', busy(go, async () => {
      if (!phone.value.trim() || !code.value.trim()) return A.info(t('lic_err_wrong_code'));
      if (!(await A.confirm(t('lic_move_warn')))) return null;
      lic.apply(await A.post('/api/license/move', { phone: phone.value, recovery_code: code.value }));
      return lic.route();
    }));
    return { el: A.page(A.bar({ title: t('lic_move_btn'), pts: false }), h('div', { class: 'scroll ruled stack' },
      h('div', { class: 'card stack' }, h('p', null, t('lic_move_info')), h('label', { class: 'fld' }, h('span', null, t('lic_phone')), phone),
        h('label', { class: 'fld' }, h('span', null, t('lic_code')), code), go))) };
  };

  A.screens.licpending = function licpending() {
    let timer = null;
    const msg = h('p', null, t('lic_pending_info'));
    async function check() {
      try {
        lic.apply(await A.post('/api/license/refresh'));
        if (lic.v.state !== 'pending') { clearInterval(timer); lic.route(); }
      } catch (e) { }
    }
    const now = h('button', { class: 'btn block', type: 'button' }, t('lic_check_now'));
    now.addEventListener('click', busy(now, async () => {
      await check();
      if (lic.v.state === 'pending') await A.info(t('lic_still_pending'));
    }));
    const cont = h('button', { class: 'btn ghost block', type: 'button' }, t('lic_continue_free'));
    cont.addEventListener('click', () => { clearInterval(timer); A.go(A.S.state.onboarded ? 'home' : 'welcome', {}, { root: true, force: true }); });
    return {
      el: A.page(A.bar({ title: t('lic_pending_title'), back: false, pts: false }), h('div', { class: 'scroll ruled stack' },
        h('div', { class: 'card stack' }, msg, h('p', { class: 'muted small' }, lic.v.phone ? t('lic_phone') + ': ' + lic.v.phone : ''), now, cont))),
      onShow() { timer = setInterval(check, 15000); },
      onLeave() { clearInterval(timer); },
    };
  };

  A.screens.liccode = function liccode() {
    const code = lic.v.code || '';
    const ok = h('button', { class: 'btn block', type: 'button' }, t('lic_code_saved'));
    ok.addEventListener('click', busy(ok, async () => {
      if (!(await A.confirm(t('lic_code_confirm')))) return null;
      lic.apply(await A.post('/api/license/ack_code'));
      return lic.route();
    }, true));
    return { el: A.page(A.bar({ title: t('lic_code_title'), back: false, pts: false }), h('div', { class: 'scroll ruled stack' },
      h('div', { class: 'card stack' }, h('p', null, t('lic_code_info')),
        h('div', { class: 'h-sec', style: { fontSize: '30px', textAlign: 'center', letterSpacing: '3px', userSelect: 'all' } }, code),
        h('button', { class: 'btn ghost block', type: 'button', onclick: () => A.copy(code) }, t('act_copy')),
        h('p', { class: 'muted small' }, t('lic_code_warn')), ok))) };
  };

  lic.badge = function badge() {
    const v = lic.v;
    if (!v) return null;
    const f = v.features || {};
    const name = v.state === 'trial' ? t('lic_trial_name') : t('lic_level', { n: v.tier < 0 ? 0 : v.tier, name: f.name || '' });
    const up = v.tier < 5 ? h('button', { class: 'btn sm', type: 'button', onclick: () => A.go('license') }, v.state === 'pending' ? t('lic_pending_title') : t('lic_upgrade')) : null;
    return h('div', { class: 'card tint row' }, h('span', { class: 'grow' }, name), up);
  };

  A.upsell = async function upsell() {
    if (await A.confirm(t('lic_need_upgrade'))) A.go('license');
  };
})();
