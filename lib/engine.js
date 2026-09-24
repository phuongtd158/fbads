// Bộ chạy tự động: lịch bật/tắt/ngân sách, rule theo hiệu quả, báo cáo hằng ngày.
const store = require('./store');
const fb = require('./fb');
const notify = require('./notify');

const DAYS = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
const GRACE_MIN = 10; // nếu máy bật trễ tối đa 10 phút vẫn chạy bù lịch

function localNow() {
  const fmt = (timeZone) => new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', weekday: 'short',
  }).formatToParts(new Date());
  let parts;
  try { parts = fmt(store.get().settings.timezone || 'Asia/Ho_Chi_Minh'); } catch { parts = fmt('Asia/Ho_Chi_Minh'); } // múi giờ sai → dùng mặc định
  const p = Object.fromEntries(parts.map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, minutes: +p.hour * 60 + +p.minute, day: DAYS[p.weekday] };
}
const toMin = (hhmm) => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; };
const money = (n) => Math.round(n).toLocaleString('vi-VN');

async function record(entry) {
  store.log(entry);
  const icon = entry.ok === false ? '❌' : entry.dry ? '🧪' : '✅';
  const tag = entry.dry ? ' (chạy thử)' : '';
  await notify.telegram(`${icon} <b>${entry.source}</b>${tag}\n${entry.name}: ${entry.detail}`);
}

const modeNow = () => { const s = store.get().settings; return s.mock ? 'mock' : s.dryRun ? 'dry' : 'live'; };

// Thực thi 1 hành động lên 1 đối tượng, tôn trọng chế độ chạy thử. ctx: { kind, refId, refName, condition }
async function act(obj, action, source, ctx = {}) {
  const s = store.get().settings;
  const dry = s.dryRun && !s.mock;
  let detail;
  const base = {
    kind: ctx.kind || 'manual', refId: ctx.refId, refName: ctx.refName, ...(ctx.condition ? { condition: ctx.condition } : {}),
    target: { id: obj.id, name: obj.name, level: obj.level }, mode: modeNow(), before: fb.snapshot(obj),
    action: { type: action.type, ...(action.type === 'budget' ? { mode: action.mode, value: action.value, max: action.max || undefined, min: action.min || undefined } : {}) },
  };
  let after;
  try {
    if (action.type === 'on' || action.type === 'off') {
      const want = action.type === 'on';
      if ((obj.effective === 'ACTIVE') === want) return; // đã đúng trạng thái
      detail = want ? 'Bật camp' : 'Tắt camp';
      after = { status: want ? 'ACTIVE' : 'PAUSED' };
      if (!dry) await fb.setStatus(obj.id, want);
    } else {
      if (obj.dailyBudget == null) throw new Error('Đối tượng này không có ngân sách ngày (có thể đang dùng CBO ở cấp camp)');
      let next = action.mode === 'percent' ? obj.dailyBudget * (1 + action.value / 100) : action.value;
      if (action.max) next = Math.min(next, action.max);
      if (action.min) next = Math.max(next, action.min);
      next = Math.round(next);
      if (next === Math.round(obj.dailyBudget)) return;
      detail = `Ngân sách ${money(obj.dailyBudget)} → ${money(next)}`;
      after = { dailyBudget: next };
      if (!dry) await fb.setBudget(obj.id, next);
    }
    await record({ ...base, source, name: obj.name, detail, ok: true, dry, after });
  } catch (e) {
    await record({ ...base, source, name: obj.name, detail: e.message, ok: false, dry, error: fb.describeError(e) });
  }
}

async function runSchedule(sch) {
  const objs = await fb.listObjects(true);
  for (const id of sch.targets) {
    const obj = objs.find((o) => o.id === id);
    if (!obj) {
      await record({ kind: 'schedule', refId: sch.id, refName: sch.name, source: `Lịch: ${sch.name}`, name: id, detail: 'Không tìm thấy đối tượng', ok: false, mode: modeNow(), target: { id },
        action: { type: sch.action, mode: sch.mode, value: sch.value }, error: { message: 'Không tìm thấy đối tượng trên tài khoản quảng cáo (có thể đã bị xoá hoặc đổi cấp).' } });
      continue;
    }
    await act(obj, { type: sch.action, mode: sch.mode, value: sch.value, max: sch.max, min: sch.min }, `Lịch: ${sch.name}`, { kind: 'schedule', refId: sch.id, refName: sch.name });
  }
}

async function tickSchedules() {
  const st = store.get().state;
  const now = localNow();
  for (const sch of store.get().schedules) {
    if (!sch.enabled || !sch.days.includes(now.day)) continue;
    const at = toMin(sch.time);
    const key = `${sch.id}:${now.date}:${sch.time}`;
    if (now.minutes >= at && now.minutes - at <= GRACE_MIN && !st.fired[key]) {
      st.fired[key] = 1;
      store.save();
      await runSchedule(sch);
    }
  }
  for (const k of Object.keys(st.fired)) if (!k.includes(now.date)) delete st.fired[k];
}

function metricValue(m, metric) {
  if (metric === 'cpa') return m.results > 0 ? m.cpa : m.spend > 0 ? Infinity : 0;
  if (metric === 'roas') return m.roas ?? 0;
  if (metric === 'results') return m.results;
  return m.spend;
}

async function runRules() {
  const st = store.get().state;
  const now = localNow();
  const objs = await fb.listObjects(true);
  for (const rule of store.get().rules) {
    if (!rule.enabled) continue;
    if (rule.from && rule.to && (now.minutes < toMin(rule.from) || now.minutes > toMin(rule.to))) continue;
    const targets = rule.allActive
      ? objs.filter((o) => o.level === (rule.level || 'campaign') && o.effective === 'ACTIVE')
      : objs.filter((o) => rule.targets.includes(o.id) && o.effective === 'ACTIVE');
    for (const obj of targets) {
      if (obj.metrics.spend < (rule.minSpend || 0)) continue;
      const v = metricValue(obj.metrics, rule.metric);
      const hit = rule.op === '>' ? v > rule.value : v < rule.value;
      if (!hit) continue;
      const key = `${rule.id}:${obj.id}`;
      if (Date.now() - (st.lastRule[key] || 0) < (rule.cooldownHours || 0) * 3600e3) continue;
      st.lastRule[key] = Date.now();
      store.save();
      const label = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Kết quả' }[rule.metric];
      const shown = v === Infinity ? '∞ (chưa có kết quả)' : rule.metric === 'roas' ? v.toFixed(2) : money(v);
      const action = rule.action === 'pause'
        ? { type: 'off' }
        : { type: 'budget', mode: 'percent', value: rule.action === 'increase' ? rule.pct : -rule.pct, max: rule.maxBudget, min: rule.minBudget };
      const condition = { metric: rule.metric, op: rule.op, threshold: rule.value, actual: Number.isFinite(v) ? v : null, actualInf: v === Infinity, minSpend: rule.minSpend || 0, spend: obj.metrics.spend, cooldownHours: rule.cooldownHours || 0 };
      await act(obj, action, `Rule: ${rule.name} [${label} ${shown}]`, { kind: 'rule', refId: rule.id, refName: rule.name, condition });
    }
  }
}

async function sendReport() {
  const objs = (await fb.listObjects(true)).filter((o) => o.level === 'campaign');
  const active = objs.filter((o) => o.effective === 'ACTIVE');
  const spend = objs.reduce((t, o) => t + o.metrics.spend, 0);
  const results = objs.reduce((t, o) => t + o.metrics.results, 0);
  const lines = active.slice(0, 15).map((o) => `• ${o.name}: ${money(o.metrics.spend)} | KQ ${o.metrics.results}`);
  const text = `📊 <b>Báo cáo Facebook Ads</b>\nĐang chạy: ${active.length}/${objs.length} camp\nChi tiêu hôm nay: ${money(spend)}\nKết quả: ${results}${results ? ` | CPA ${money(spend / results)}` : ''}\n${lines.join('\n')}`;
  return notify.telegram(text);
}

async function tickReport() {
  const s = store.get().settings, st = store.get().state, now = localNow();
  if (!s.reportTime || !s.telegramToken) return;
  const at = toMin(s.reportTime);
  if (now.minutes >= at && now.minutes - at <= GRACE_MIN && st.lastReport !== now.date) {
    st.lastReport = now.date; store.save();
    await sendReport();
  }
}

let lastRules = 0, busy = false;
async function tick() {
  if (busy) return;
  busy = true;
  try {
    await tickSchedules();
    const every = (store.get().settings.ruleIntervalMin || 15) * 60e3;
    if (Date.now() - lastRules >= every) { lastRules = Date.now(); await runRules(); }
    await tickReport();
  } catch (e) {
    console.error('Lỗi tick:', e.message);
    store.log({ kind: 'system', source: 'Hệ thống', name: '-', detail: e.message, ok: false, mode: modeNow(), error: fb.describeError(e) });
  } finally { busy = false; }
}

function start() { setInterval(tick, 30000); setTimeout(tick, 3000); }

module.exports = { start, runSchedule, runRules, sendReport };
