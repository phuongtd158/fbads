// Bộ chạy tự động: lịch bật/tắt/ngân sách, rule theo hiệu quả, dừng khẩn, hoàn tác, báo cáo hằng ngày.
const store = require('./store');
const fb = require('./fb');
const notify = require('./notify');
const { esc } = notify;
const watch = require('./watch');
const alerts = require('./alerts');
const company = require('./companyReport');

const DAYS = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
const GRACE_MIN = 10; // nếu máy bật trễ tối đa 10 phút vẫn chạy bù lịch
const UNDO_HOLD_H = 24; // sau khi hoàn tác một việc do rule làm, rule đó tạm không tác động lại camp này
const UNDO_MAX_DAYS = 3;

function localNow(at = new Date()) {
  const fmt = (timeZone) => new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', weekday: 'short',
  }).formatToParts(at);
  let parts;
  try { parts = fmt(store.get().settings.timezone || 'Asia/Ho_Chi_Minh'); } catch { parts = fmt('Asia/Ho_Chi_Minh'); } // múi giờ sai → dùng mặc định
  const p = Object.fromEntries(parts.map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, minutes: +p.hour * 60 + +p.minute, day: DAYS[p.weekday] };
}
const toMin = (hhmm) => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; };
const scheduleTimes = (s) => (Array.isArray(s.times) && s.times.length ? s.times : [s.time]); // như shared/validate.mjs
// Các lần chạy trong ngày (như scheduleEvents trong shared/validate.mjs): lịch khung giờ = bật lúc window.on + tắt lúc window.off;
// giờ tắt ≤ giờ bật là tắt sau nửa đêm → lần tắt thuộc ngày hôm trước (prevDay).
function scheduleEvents(s) {
  if (s.action === 'window') {
    const w = s.window || {};
    if (!w.on || !w.off) return [];
    return [{ time: w.on, action: 'on', prevDay: false }, { time: w.off, action: 'off', prevDay: w.off <= w.on }];
  }
  return scheduleTimes(s).map((time) => ({ time, action: s.action, prevDay: false }));
}
// Lịch khung giờ lúc này đang trong giờ bật không (nút Chạy ngay đưa camp về đúng trạng thái của khung giờ)
function windowIsOn(s, now) {
  const on = toMin(s.window.on), off = toMin(s.window.off), days = s.days || [], prev = (now.day + 6) % 7;
  if (off > on) return days.includes(now.day) && now.minutes >= on && now.minutes < off;
  return (days.includes(now.day) && now.minutes >= on) || (days.includes(prev) && now.minutes < off);
}
const money = (n) => Math.round(n).toLocaleString('vi-VN');
const httpErr = (status, message) => { const e = new Error(message); e.status = status; return e; };

const METRIC_LABEL = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Kết quả', ctr: 'CTR', cpc: 'CPC', cpm: 'CPM', messages: 'Tin nhắn', costPerMessage: 'Chi phí/tin nhắn', leads: 'Lead', costPerLead: 'Chi phí/lead', frequency: 'Tần suất' };
const RANGE_LABEL = { today: 'hôm nay', yesterday: 'hôm qua', last_3d: '3 ngày gần nhất', last_7d: '7 ngày gần nhất' };
const EMPTY_M = { spend: 0, impressions: 0, clicks: 0, results: 0, revenue: 0, cpa: null, roas: null };
const unitOf = (o) => (o && o.level === 'adset' ? 'nhóm QC' : 'camp');
// Ngân sách chỉ nằm ở 1 cấp: camp CBO giữ ngân sách (nhóm QC không có), camp ABO thì ngược lại
const noBudgetReason = (o) => (o.level === 'adset'
  ? 'Nhóm QC không có ngân sách riêng (chiến dịch dùng ngân sách chiến dịch - CBO), chỉnh ngân sách ở cấp chiến dịch'
  : 'Chiến dịch không có ngân sách riêng (ngân sách đặt ở từng nhóm QC - ABO), hãy dùng rule cấp nhóm QC');
const modeNow = () => { const s = store.get().settings; return s.mock ? 'mock' : s.dryRun ? 'dry' : 'live'; };

// Nút dưới tin Telegram (lib/tgbot.js): thay đổi thật thì có "Hoàn tác"; rule chỉ thông báo thì có "Tắt camp/nhóm QC"
function buttonsFor(l) {
  const t = l.target, a = l.action || {};
  if (!t || !t.id || l.ok === false || l.skipped) return undefined;
  if (a.type === 'notify') return [[{ text: `⏸ Tắt ${unitOf(t)}`, data: `o:${t.id}` }]];
  if (l.kind !== 'undo' && !l.dry && l.after && ['on', 'off', 'budget'].includes(a.type)) return [[{ text: '↩️ Hoàn tác', data: `u:${l.id}` }]];
  return undefined;
}

// Ghi nhật ký (+ Telegram trừ khi silent). Trả về dòng đã lưu.
async function record(entry) {
  const { silent, ...rest } = entry;
  const saved = store.log(rest);
  if (!silent) {
    const isNotify = rest.action && rest.action.type === 'notify';
    const icon = rest.ok === false ? '❌' : isNotify ? '🔔' : rest.skipped ? '⏭️' : rest.dry ? '🧪' : '✅';
    const tag = rest.dry && !isNotify ? ' (chạy thử)' : '';
    await notify.telegram(`${icon} <b>${esc(rest.source)}</b>${tag}\n${esc(rest.name)}: ${esc(rest.detail)}`, { buttons: buttonsFor(saved) });
  }
  return saved;
}

/* ------------------------------------------------------- Bảo vệ ngân sách */
const protect = () => { const s = store.get().settings; return { skipLearning: s.skipLearning !== false, capPct: Number(s.dailyChangeCapPct) || 30 }; };

// Ngân sách "gốc" của mỗi camp trong ngày: giá trị trước lần đổi đầu tiên do rule; dùng để tính giới hạn thay đổi cộng dồn
function budgetDay() {
  const st = store.get().state, today = localNow().date;
  if (!st.budgetDay || st.budgetDay.date !== today) st.budgetDay = { date: today, base: {}, skipLogged: {} };
  return st.budgetDay;
}

// Lập kế hoạch cho một hành động (thuần tuý, không gọi Facebook, không ghi trạng thái).
// Trả về { kind: 'noop' | 'skip' | 'error' | 'do', ... }
function plan(obj, action, ctx = {}) {
  if (action.type === 'notify') return { kind: 'do', notify: true, detail: action.message || 'Cảnh báo' };
  if (action.type === 'on' || action.type === 'off') {
    const want = action.type === 'on';
    if ((obj.effective === 'ACTIVE') === want) return { kind: 'noop' }; // đã đúng trạng thái
    return { kind: 'do', detail: `${want ? 'Bật' : 'Tắt'} ${unitOf(obj)}`, after: { status: want ? 'ACTIVE' : 'PAUSED' } };
  }
  if (obj.dailyBudget == null) return { kind: 'error', message: `${noBudgetReason(obj)}.` };
  const isRule = ctx.kind === 'rule', pr = protect();
  if (isRule && pr.skipLearning && obj.learning && !ctx.includeLearning) return { kind: 'skip', code: 'learning', reason: 'Đang trong giai đoạn học nên tạm không đổi ngân sách (tránh làm Facebook học lại từ đầu).' };
  let next = action.mode === 'percent' ? obj.dailyBudget * (1 + action.value / 100) : action.mode === 'add' ? obj.dailyBudget + action.value : action.value;
  if (action.max) next = Math.min(next, action.max);
  if (action.min) next = Math.max(next, action.min);
  next = Math.round(next);
  // Hướng thay đổi (giảm < 0 < tăng; đặt mức cố định = 0): sàn/trần/giới hạn ngày không bao giờ được đảo hướng
  // (vd ngân sách đã dưới sàn thì rule giảm bỏ qua, không kéo lên bằng sàn)
  const dir = action.mode === 'percent' || action.mode === 'add' ? Math.sign(action.value) : 0, cur = Math.round(obj.dailyBudget);
  if (dir < 0 && (next > cur || (next === cur && action.min >= cur))) return { kind: 'skip', code: 'min', reason: `Ngân sách ${money(cur)} đã ở mức sàn ${money(action.min)} hoặc thấp hơn, không giảm thêm.` };
  if (dir > 0 && (next < cur || (next === cur && action.max && action.max <= cur))) return { kind: 'skip', code: 'max', reason: `Ngân sách ${money(cur)} đã ở mức trần ${money(action.max)} hoặc cao hơn, không tăng thêm.` };
  if (!(next > 0)) return { kind: 'error', message: `Ngân sách mới sẽ là ${money(next)} (không lớn hơn 0) nên không đổi.` };
  let capped = false, base = obj.dailyBudget;
  if (isRule && !ctx.noCap) { // giới hạn tổng thay đổi mỗi ngày (rule tăng theo bậc không dùng: đã bắt buộc có trần ngân sách) (chỉ áp dụng cho rule; lịch là ý định rõ ràng của bạn)
    base = budgetDay().base[obj.id] ?? obj.dailyBudget;
    const lo = Math.round(base * (1 - pr.capPct / 100)), hi = Math.round(base * (1 + pr.capPct / 100));
    let c = Math.min(hi, Math.max(lo, next));
    if (dir < 0) c = Math.min(c, cur); else if (dir > 0) c = Math.max(c, cur); // chạm giới hạn thì dừng lại, không đổi ngược hướng
    if (c !== next) { capped = true; next = c; }
  }
  if (next === cur) {
    return capped ? { kind: 'skip', code: 'cap', reason: `Đã đạt giới hạn thay đổi ${pr.capPct}% mỗi ngày (ngân sách gốc hôm nay ${money(base)}).` } : { kind: 'noop' };
  }
  return { kind: 'do', detail: `Ngân sách ${money(obj.dailyBudget)} → ${money(next)}${capped ? ` (chạm giới hạn ${pr.capPct}%/ngày)` : ''}`, after: { dailyBudget: next }, next, capped };
}

// Thực thi 1 hành động lên 1 đối tượng, tôn trọng chế độ chạy thử. ctx: { kind, refId, refName, condition, silent }
async function act(obj, action, source, ctx = {}) {
  const s = store.get().settings;
  const dry = s.dryRun && !s.mock;
  const isNotify = action.type === 'notify';
  const base = {
    kind: ctx.kind || 'manual', refId: ctx.refId, refName: ctx.refName, ...(ctx.condition ? { condition: ctx.condition } : {}),
    target: { id: obj.id, name: obj.name, level: obj.level, ...(obj.accountId ? { accountId: obj.accountId, accountName: obj.accountName } : {}) }, mode: modeNow(), before: fb.snapshot(obj),
    action: { type: action.type, ...(action.type === 'budget' ? { mode: action.mode, value: action.value, max: action.max || undefined, min: action.min || undefined } : {}) },
    source, name: obj.name,
  };
  const p = plan(obj, action, ctx);
  if (p.kind === 'noop') return 'noop';
  if (p.kind === 'skip') { // ghi nhận việc bỏ qua, mỗi lý do chỉ 1 lần/ngày/camp để khỏi đầy nhật ký; không gửi Telegram
    const day = budgetDay(), key = `${ctx.refId || ''}:${obj.id}:${p.code}`;
    if (day.skipLogged[key]) return 'skip';
    day.skipLogged[key] = 1; store.save();
    await record({ ...base, detail: `Bỏ qua: ${p.reason}`, ok: true, dry, skipped: true, silent: true });
    return 'skip';
  }
  if (p.kind === 'error') { await record({ ...base, detail: p.message, ok: false, dry, error: { message: p.message }, silent: ctx.silent }); return 'error'; }
  try {
    if (!isNotify && !dry) {
      if (p.after.status !== undefined) await fb.setStatus(obj.id, p.after.status === 'ACTIVE');
      else {
        await fb.setBudget(obj.id, p.next);
        if (ctx.kind === 'rule') { const day = budgetDay(); if (day.base[obj.id] == null) day.base[obj.id] = obj.dailyBudget; store.save(); }
      }
      // Cập nhật ngay bản đang dùng trong lượt chạy này để các rule sau không tác động lên camp vừa bị tắt / dùng ngân sách cũ
      if (p.after.status !== undefined) { obj.status = p.after.status; obj.effective = p.after.status; }
      else if (p.after.dailyBudget !== undefined) obj.dailyBudget = p.after.dailyBudget;
    }
    await record({ ...base, detail: p.detail, ok: true, dry: isNotify ? false : dry, after: p.after, silent: ctx.silent });
    return 'ok';
  } catch (e) {
    await record({ ...base, detail: e.message, ok: false, dry, error: fb.describeError(e), silent: ctx.silent });
    return 'fail';
  }
}

/* ------------------------------------------------------------------- Lịch */
let bulkMod = null; // shared/bulk.mjs là ES module → nạp 1 lần khi cần
const bulk = () => (bulkMod ||= import('../shared/bulk.mjs'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const WRITE_GAP_MS = 300; // giãn nhịp giữa các lần đổi của lịch theo điều kiện (đỡ chạm giới hạn số lần gọi)
const blocked = () => !!fb.objectsMeta().blockedUntil;
let deliveryMod = null; // shared/delivery.mjs: trạng thái "Phân phối" như Ads Manager
const delivery = () => (deliveryMod ||= import('../shared/delivery.mjs'));
// Mục đang thật sự chạy (như cột Phân phối): camp bật nhưng mọi nhóm QC đã tắt / đã hết hạn thì KHÔNG tính là đang chạy.
// Trả về { running(obj), label(obj) } cho evaluateRule.
async function runningOf(objs) {
  const { deliveryMap, DELIVERY } = await delivery();
  const dm = deliveryMap(objs);
  return { running: (o) => !!(DELIVERY[dm[o.id]] || {}).running, label: (o) => (DELIVERY[dm[o.id]] || {}).label || '' };
}

// Trả 'blocked' nếu Facebook đang giới hạn số lần gọi và CHƯA làm gì → lịch được thử lại trong thời gian chạy bù.
// type: 'on' | 'off' cho lần chạy của lịch khung giờ; không truyền (bấm Chạy ngay) thì bật/tắt theo khung giờ lúc này.
async function runSchedule(sch, type) {
  const objs = await fb.listObjects(true);
  if (blocked()) return 'blocked';
  if (sch.action === 'window' && !type) type = windowIsOn(sch, localNow()) ? 'on' : 'off';
  const action = { type: type || sch.action, mode: sch.mode, value: sch.value, max: sch.max, min: sch.min };
  const source = `Lịch: ${sch.name}`, ctx = { kind: 'schedule', refId: sch.id, refName: sch.name };
  if (sch.targetMode === 'filter') return runFilterSchedule(sch, objs, action, source, ctx);
  for (let i = 0; i < sch.targets.length; i++) {
    const id = sch.targets[i];
    if (i && blocked()) { await stopLog(sch, source, sch.targets.length - i); break; }
    const obj = objs.find((o) => o.id === id);
    if (!obj) {
      await record({ kind: 'schedule', refId: sch.id, refName: sch.name, source, name: id, detail: 'Không tìm thấy đối tượng', ok: false, mode: modeNow(), target: { id },
        action: { type: action.type, mode: sch.mode, value: sch.value }, error: { message: 'Không tìm thấy đối tượng trên tài khoản quảng cáo (có thể đã bị xoá hoặc đổi cấp).' } });
      continue;
    }
    await act(obj, action, source, ctx);
  }
}

const stopLog = (sch, source, left) => record({ kind: 'schedule', refId: sch.id, refName: sch.name, source, name: '-', mode: modeNow(), ok: false,
  detail: `Dừng giữa chừng: Facebook đang giới hạn số lần gọi, còn ${left} mục chưa xử lý ở lượt này.`, error: { message: 'Facebook đang giới hạn số lần gọi (rate limit).' } });

// Lịch "Theo điều kiện": lọc lại theo số liệu lúc chạy. Mỗi mục vẫn ghi nhật ký riêng (hoàn tác được) nhưng không gửi Telegram từng mục;
// cuối lượt ghi 1 dòng tóm tắt (và gửi 1 tin Telegram nếu có thay đổi/lỗi).
async function runFilterSchedule(sch, objs, action, source, ctx) {
  const { matchFilter, describeFilter } = await bulk();
  const ex = new Set(sch.exclude || []); // mục bạn đã bỏ tích (loại trừ)
  let list = matchFilter(objs, sch.filter).filter((o) => !ex.has(o.id));
  if (action.type === 'budget') list = list.filter((o) => o.dailyBudget != null); // CBO: không có ngân sách ở cấp này → không áp dụng
  const desc = describeFilter(sch.filter) + (ex.size ? ` · trừ ${ex.size} mục` : '');
  const n = { ok: 0, fail: 0, same: 0 };
  let left = 0;
  for (let i = 0; i < list.length; i++) {
    if (i && blocked()) { left = list.length - i; break; }
    const r = await act(list[i], action, source, { ...ctx, silent: true });
    if (r === 'ok') n.ok++; else if (r === 'fail' || r === 'error') n.fail++; else n.same++;
    if ((r === 'ok' || r === 'fail') && i < list.length - 1) await sleep(WRITE_GAP_MS);
  }
  const s = store.get().settings, dry = s.dryRun && !s.mock;
  const detail = !list.length ? `Không có mục nào khớp điều kiện (${desc}).`
    : `Khớp ${list.length} mục (${desc}): ${dry ? 'sẽ đổi' : 'đã đổi'} ${n.ok}${n.same ? `, đã đúng sẵn/bỏ qua ${n.same}` : ''}${n.fail ? `, lỗi ${n.fail}` : ''}`
      + `${left ? `. Dừng vì Facebook giới hạn số lần gọi, còn ${left} mục chưa xử lý` : ''}.`;
  await record({ kind: 'schedule', refId: sch.id, refName: sch.name, source, name: desc, mode: modeNow(), dry, ok: !n.fail && !left,
    action: { type: action.type, ...(action.type === 'budget' ? { mode: action.mode, value: action.value } : {}) }, detail,
    ...(n.fail || left ? { error: { message: left ? 'Facebook đang giới hạn số lần gọi (rate limit).' : `${n.fail} mục lỗi, xem các dòng nhật ký của lịch này.` } } : {}),
    silent: !n.ok && !n.fail && !left });
}

async function tickSchedules() {
  const st = store.get().state;
  const now = localNow();
  for (const sch of store.get().schedules) {
    if (!sch.enabled) continue;
    // Một lịch có thể có nhiều giờ chạy trong ngày (times); lịch cũ chỉ có time; lịch khung giờ có 1 lần bật + 1 lần tắt.
    // Mỗi mốc chạy đúng 1 lần mỗi ngày.
    for (const { time, action, prevDay } of scheduleEvents(sch)) {
      if (!sch.days.includes(prevDay ? (now.day + 6) % 7 : now.day)) continue;
      const at = toMin(time);
      const key = `${sch.id}:${now.date}:${time}`;
      if (now.minutes >= at && now.minutes - at <= GRACE_MIN && !st.fired[key]) {
        st.fired[key] = 1;
        store.save();
        if (await runSchedule(sch, sch.action === 'window' ? action : undefined) === 'blocked') {
          // Facebook đang giới hạn và lịch chưa làm gì: thử lại ở lượt sau (30 giây) trong thời gian chạy bù; hết thời gian thì ghi lỗi
          if (now.minutes - at < GRACE_MIN) { delete st.fired[key]; store.save(); }
          else await record({ kind: 'schedule', refId: sch.id, refName: sch.name, source: `Lịch: ${sch.name}`, name: '-', mode: modeNow(), ok: false,
            detail: `Không chạy được lượt ${time}: Facebook giới hạn số lần gọi suốt ${GRACE_MIN} phút sau giờ hẹn.`, error: { message: 'Facebook đang giới hạn số lần gọi (rate limit).' } });
        }
      }
    }
  }
  for (const k of Object.keys(st.fired)) if (!k.includes(now.date)) delete st.fired[k];
}

/* ------------------------------------------------------------------- Rule */
// Giá trị của một số liệu để so với ngưỡng. CTR/CPC/CPM tính như derive() trong shared/metrics.mjs (tests/engine.test.mjs kiểm tra hai bản khớp).
// Chi phí mà chưa có mẫu số (CPA khi chưa có kết quả, CPC khi chưa có lượt nhấp) là ∞ nếu đã chi tiêu: tức là "đắt vô hạn", không phải 0.
const costPer = (spend, n) => (n > 0 ? spend / n : spend > 0 ? Infinity : 0);
function metricValue(m, metric) {
  const impressions = m.impressions || 0, clicks = m.clicks || 0;
  if (metric === 'cpa') return m.results > 0 ? m.cpa : m.spend > 0 ? Infinity : 0;
  if (metric === 'roas') return m.roas ?? 0;
  if (metric === 'results') return m.results;
  if (metric === 'ctr') return impressions > 0 ? (clicks * 100) / impressions : 0;
  if (metric === 'cpc') return clicks > 0 ? m.spend / clicks : m.spend > 0 ? Infinity : 0;
  if (metric === 'cpm') return impressions > 0 ? (m.spend * 1000) / impressions : 0;
  if (metric === 'messages') return m.conversations || 0;
  if (metric === 'leads') return m.leads || 0;
  if (metric === 'costPerMessage') return costPer(m.spend, m.conversations);
  if (metric === 'costPerLead') return costPer(m.spend, m.leads);
  if (metric === 'frequency') return m.reach > 0 ? impressions / m.reach : 0;
  return m.spend;
}
const showValue = (v, metric) => (v === Infinity ? '∞ (chưa có kết quả)' : metric === 'roas' || metric === 'frequency' ? Number(v).toFixed(2) : metric === 'ctr' ? `${Number(v).toFixed(2)}%` : money(v));

// Các điều kiện của rule (rule cũ chỉ có metric/op/value → 1 điều kiện) — như conditionsOf trong shared/validate.mjs
const conditionsOf = (r) => (Array.isArray(r.conditions) && r.conditions.length ? r.conditions : [{ metric: r.metric, op: r.op, value: r.value }]);
// So với khoảng khác (như compareThreshold trong shared/validate.mjs): số liệu dạng tổng chia trung bình theo ngày, tỉ lệ so thẳng
const RANGE_DAYS = { today: 1, yesterday: 1, last_3d: 3, last_7d: 7 };
const TOTAL_METRICS = ['spend', 'results', 'messages', 'leads'];
function compareThreshold(c, base) {
  if (base == null || !Number.isFinite(base) || base <= 0) return null;
  const perDay = TOTAL_METRICS.includes(c.metric) ? base / (RANGE_DAYS[c.compareRange] || 1) : base;
  return (perDay * (Number(c.factor) || 100)) / 100;
}
// Ngưỡng của một điều kiện cho một camp: số cụ thể; mục tiêu của TÀI KHOẢN chứa camp × factor%; hoặc chính số liệu đó ở khoảng khác × factor%.
// null = chưa quyết định được (tài khoản chưa đặt mục tiêu / khoảng so sánh chưa có số liệu).
function thresholdOf(c, obj, mapFor) {
  if (c.vs === 'range') {
    const bm = (mapFor(c.compareRange) || {})[obj.id];
    return bm && bm.spend > 0 ? compareThreshold(c, metricValue(bm, c.metric)) : null;
  }
  if (c.vs !== 'target') return Number(c.value);
  const t = Number(((store.get().settings.accountTargets || {})[obj.accountId] || {})[c.metric === 'spend' ? 'cpa' : c.metric]); // chi tiêu so với CPA mục tiêu
  return t > 0 ? (t * (Number(c.factor) || 100)) / 100 : null;
}
// Ngưỡng chi tiêu nâng theo số kết quả (như spendTierOf trong shared/validate.mjs): bậc cao nhất đã đạt, không đạt bậc nào thì ngưỡng gốc
function spendTierOf(c, count) {
  let pick = null;
  for (const t of c.tiers || []) if (count >= t.count && (!pick || t.count > pick.count)) pick = t;
  return pick ? { value: Number(pick.value), tier: pick } : { value: Number(c.value), tier: null };
}
const fmtTh = (c) => (c.metric === 'roas' || c.metric === 'frequency' ? Number(c.threshold).toFixed(2) : c.metric === 'ctr' ? `${Number(c.threshold).toFixed(2)}%` : money(c.threshold));
const vsText = (c) => (c.vs === 'target' ? ` (${c.factor}% ${c.metric === 'spend' ? 'CPA ' : ''}mục tiêu)`
  : c.vs === 'range' ? ` (${c.factor}% ${METRIC_LABEL[c.metric]} ${RANGE_LABEL[c.compareRange]}${TOTAL_METRICS.includes(c.metric) && RANGE_DAYS[c.compareRange] > 1 ? ', trung bình mỗi ngày' : ''})` : '');
const tierText = (c) => (c.tierMetric ? ` (đã có ${c.tierCount} ${METRIC_LABEL[c.tierMetric]}${c.tier ? `, đạt bậc từ ${c.tier.count}` : ''})` : '');
const condSentence = (c, range) => `${METRIC_LABEL[c.metric]} ${RANGE_LABEL[range]} = ${showValue(c.actual, c.metric)} ${c.op === '>' ? 'lớn hơn' : 'nhỏ hơn'} ngưỡng ${fmtTh(c)}${vsText(c)}${tierText(c)}`;

function actionOf(rule) {
  if (rule.action === 'pause') return { type: 'off' };
  if (rule.action === 'notify') return { type: 'notify' };
  const sign = rule.action === 'increase' ? 1 : -1;
  if (rule.budgetMode === 'amount') return { type: 'budget', mode: 'add', value: sign * rule.amount, max: rule.maxBudget, min: rule.minBudget };
  return { type: 'budget', mode: 'percent', value: sign * rule.pct, max: rule.maxBudget, min: rule.minBudget };
}

// Đánh giá một rule trên danh sách đối tượng. mapFor(range) → { [id]: metrics }. Không thay đổi gì (dùng cho cả chạy thật lẫn xem trước).
// Mỗi phần tử: { obj, range, metrics, value, hit, eligible, status: 'match'|'nomatch'|'skip'|'nochange'|'error', code, reason, plan }
function evaluateRule(rule, objs, mapFor, opts = {}) {
  const st = store.get().state, now = opts.now || localNow();
  st.hold = st.hold || {};
  const range = rule.range || 'today', map = mapFor(range) || {};
  const level = rule.level || 'campaign';
  const outside = rule.from && rule.to && (now.minutes < toMin(rule.from) || now.minutes > toMin(rule.to));
  // "Tất cả camp đang chạy" có thể giới hạn theo tài khoản (accountIds trống = mọi tài khoản); rule chọn camp cụ thể thì không cần
  const inAccounts = (o) => !(rule.accountIds || []).length || rule.accountIds.includes(o.accountId);
  // opts.running: trạng thái phân phối (runningOf); không có thì chỉ xét trạng thái Facebook báo (effective ACTIVE)
  // (luôn xét cả effective: mục vừa bị rule trước tắt trong cùng lượt đã được cập nhật effective = PAUSED)
  const isRunning = (o) => o.effective === 'ACTIVE' && (!opts.running || opts.running.running(o));
  const list = rule.allActive ? objs.filter((o) => o.level === level && isRunning(o) && inAccounts(o)) : objs.filter((o) => (rule.targets || []).includes(o.id));
  const conditions = conditionsOf(rule), any = rule.match === 'any';
  const action = actionOf(rule);
  return list.map((obj) => {
    const metrics = map[obj.id] || EMPTY_M;
    const d = { obj, range, metrics, value: null, hit: false, eligible: false, status: 'nomatch', code: '', reason: '', plan: null };
    if (!isRunning(obj)) {
      const why = opts.running ? opts.running.label(obj) : '';
      return Object.assign(d, { status: 'skip', code: 'inactive', reason: `${unitOf(obj) === 'camp' ? 'Camp' : 'Nhóm QC'} không đang chạy${why ? ` (${why})` : ''}` });
    }
    // Rule đổi ngân sách: mục không có ngân sách riêng thì bỏ qua ngay (hiện lý do ở Xem trước, không ghi nhật ký mỗi lượt)
    if (action.type === 'budget' && obj.dailyBudget == null) return Object.assign(d, { status: 'skip', code: 'nobudget', reason: noBudgetReason(obj) });
    if (outside) return Object.assign(d, { status: 'skip', code: 'window', reason: `Ngoài khung giờ của rule (${rule.from}–${rule.to})` });
    if (metrics.spend < (rule.minSpend || 0)) return Object.assign(d, { status: 'skip', code: 'minspend', reason: `Chưa đủ chi tiêu tối thiểu (${money(metrics.spend)} < ${money(rule.minSpend)})` });
    if (rule.action === 'ladder') return evaluateLadder(rule, obj, d, st);
    // Đánh giá từng điều kiện rồi gộp bằng VÀ (mặc định) hoặc HOẶC. Điều kiện so với mục tiêu mà tài khoản chưa đặt mục tiêu thì "chưa biết".
    d.conds = conditions.map((c) => {
      const actual = metricValue(metrics, c.metric);
      // Ngưỡng nâng theo số kết quả: đếm kết quả trong cùng khoảng của rule rồi chọn bậc
      const tierCount = c.tiers && c.tiers.length ? metricValue(metrics, c.tierMetric) : null;
      const st_ = tierCount != null ? spendTierOf(c, tierCount) : null;
      const threshold = st_ ? st_.value : thresholdOf(c, obj, mapFor);
      const unknown = threshold == null;
      return { metric: c.metric, op: c.op, vs: c.vs, factor: c.factor, compareRange: c.compareRange, actual, threshold, unknown, hit: !unknown && (c.op === '>' ? actual > threshold : actual < threshold),
        ...(st_ ? { tierMetric: c.tierMetric, tierCount, tier: st_.tier } : {}) };
    });
    d.value = d.conds[0].actual; // giữ cho phần cũ (xem trước, nhật ký) đọc điều kiện đầu
    const known = d.conds.filter((c) => !c.unknown), someUnknown = known.length < d.conds.length;
    d.hit = any ? known.some((c) => c.hit) : !someUnknown && known.every((c) => c.hit);
    if (!d.hit) {
      // Chưa quyết định được vì thiếu mục tiêu (VÀ: các điều kiện còn lại đều đúng; HOẶC: chưa điều kiện nào đúng) → báo lý do thay vì im lặng
      const undecided = someUnknown && (any || known.every((c) => c.hit));
      if (undecided) {
        const unk = d.conds.filter((c) => c.unknown);
        const noTarget = unk.filter((c) => c.vs === 'target');
        if (noTarget.length) {
          const need = [...new Set(noTarget.map((c) => METRIC_LABEL[c.metric]))].join(', ');
          return Object.assign(d, { status: 'skip', code: 'notarget', reason: `Tài khoản ${obj.accountName || obj.accountId || ''} chưa đặt mục tiêu ${need} (Cài đặt → Mục tiêu)` });
        }
        const what = [...new Set(unk.map((c) => `${METRIC_LABEL[c.metric]} ${RANGE_LABEL[c.compareRange]}`))].join(', ');
        return Object.assign(d, { status: 'skip', code: 'nobaseline', reason: `Chưa có số liệu ${what} để so sánh (${unitOf(obj)} chưa chi tiêu trong khoảng đó)` });
      }
      return d;
    }
    const key = `${rule.id}:${obj.id}`;
    const holdLeft = (st.hold[key] || 0) - Date.now();
    if (holdLeft > 0) return Object.assign(d, { status: 'skip', code: 'hold', reason: `Tạm hoãn sau khi bạn hoàn tác (còn ${Math.ceil(holdLeft / 3600e3)} giờ)` });
    const left = (st.lastRule[key] || 0) + (rule.cooldownHours || 0) * 3600e3 - Date.now();
    if (left > 0) return Object.assign(d, { status: 'skip', code: 'cooldown', reason: `Đang nghỉ giữa hai lần (còn khoảng ${Math.ceil(left / 3600e3)} giờ)` });
    d.eligible = true;
    const act_ = action.type === 'notify'
      ? { ...action, message: d.conds.filter((c) => c.hit).map((c) => condSentence(c, range)).join(any ? ' HOẶC ' : ' VÀ ') }
      : action;
    d.action = act_;
    d.plan = plan(obj, act_, { kind: 'rule' });
    d.status = d.plan.kind === 'do' ? 'match' : d.plan.kind === 'skip' ? 'skip' : d.plan.kind === 'error' ? 'error' : 'nochange';
    if (d.plan.kind === 'skip') { d.code = d.plan.code; d.reason = d.plan.reason; }
    if (d.plan.kind === 'noop') d.reason = 'Không có gì để thay đổi (đã ở trạng thái/ngân sách mong muốn)';
    if (d.plan.kind === 'error') d.reason = d.plan.message;
    return d;
  });
}

// Rule tăng theo bậc kết quả: bậc cao nhất đã đạt mà hôm nay chưa chạy thì tăng theo bậc đó (nhảy nhiều bậc chỉ chạy bậc cao nhất);
// bậc cuối có everyHours thì lặp lại sau mỗi chừng ấy giờ. Bậc đã chạy lưu ở state.ladder[ruleId:objId] = { date, step, at }, sang ngày mới tính lại.
const ladderState = (st, key) => { const l = (st.ladder || {})[key]; return l && l.date === localNow().date ? l : { step: -1, at: 0 }; };
const unitWord = (m) => (METRIC_LABEL[m] || m).toLowerCase();
function evaluateLadder(rule, obj, d, st) {
  const steps = rule.steps || [], m = rule.ladderMetric || 'results';
  const count = metricValue(d.metrics, m) || 0;
  let h = -1;
  steps.forEach((t, i) => { if (count >= t.count) h = i; });
  const ref = steps[Math.max(h, 0)] || { count: 1 };
  d.value = count;
  d.conds = [{ metric: m, op: '>', actual: count, threshold: ref.count - 1, hit: h >= 0, unknown: false, ladderStep: h + 1, ladderNeed: ref.count }];
  if (h < 0) return Object.assign(d, { reason: `Chưa có ${ref.count} ${unitWord(m)} (đang có ${count})` });
  d.hit = true;
  d.ladder = { step: h, count, need: steps[h].count, metric: m };
  const key = `${rule.id}:${obj.id}`, done = ladderState(st, key);
  if (h <= done.step) {
    const every = h === steps.length - 1 ? Number(steps[h].everyHours) || 0 : 0;
    if (!every) {
      const next = steps[h + 1];
      return Object.assign(d, { status: 'skip', code: 'ladderdone', reason: next ? `Hôm nay đã tăng bậc ${h + 1}, chờ có ${next.count} ${unitWord(m)} để lên bậc ${h + 2}` : `Hôm nay đã tăng bậc cuối (bậc ${h + 1})` });
    }
    const left = done.at + every * 3600e3 - Date.now();
    if (left > 0) return Object.assign(d, { status: 'skip', code: 'ladderwait', reason: `Đã tăng bậc ${h + 1}, lần tăng tiếp theo sau khoảng ${Math.ceil(left / 60e3)} phút` });
  }
  const holdLeft = (st.hold[key] || 0) - Date.now();
  if (holdLeft > 0) return Object.assign(d, { status: 'skip', code: 'hold', reason: `Tạm hoãn sau khi bạn hoàn tác (còn ${Math.ceil(holdLeft / 3600e3)} giờ)` });
  d.eligible = true;
  const t = steps[h];
  d.action = { type: 'budget', mode: t.mode === 'amount' ? 'add' : 'percent', value: t.value, max: rule.maxBudget };
  d.planCtx = { kind: 'rule', noCap: true, includeLearning: rule.includeLearning !== false };
  d.plan = plan(obj, d.action, d.planCtx);
  d.status = d.plan.kind === 'do' ? 'match' : d.plan.kind === 'skip' ? 'skip' : d.plan.kind === 'error' ? 'error' : 'nochange';
  if (d.plan.kind === 'skip') { d.code = d.plan.code; d.reason = d.plan.reason; }
  if (d.plan.kind === 'noop') d.reason = `Đã chạm trần ngân sách ${money(rule.maxBudget)}`;
  if (d.plan.kind === 'error') d.reason = d.plan.message;
  return d;
}

// Số liệu của mọi khoảng các rule cần: khoảng tính của rule và các khoảng so sánh trong điều kiện
async function loadMaps(rules, force = true) {
  const maps = {}, ranges = new Set();
  for (const r of rules) {
    ranges.add(r.range || 'today');
    for (const c of conditionsOf(r)) if (c.vs === 'range' && c.compareRange) ranges.add(c.compareRange);
  }
  for (const range of ranges) maps[range] = await fb.rangeMetrics(range, force);
  return maps;
}

async function runRules() {
  const st = store.get().state;
  const objs = await fb.listObjects(true);
  await checkKillSwitch(objs);
  const rules = store.get().rules.filter((r) => r.enabled);
  if (!rules.length) return;
  // Facebook đang giới hạn số lần gọi → chỉ có số liệu cũ: không quyết định dựa trên nó, đợi lượt kiểm tra sau
  if (fb.objectsMeta().stale) { console.log('  Bỏ qua lượt kiểm tra rule: Facebook đang giới hạn số lần gọi, số liệu chưa cập nhật.'); return; }
  const maps = await loadMaps(rules);
  const running = await runningOf(objs);
  for (const rule of rules) {
    for (const d of evaluateRule(rule, objs, (r) => maps[r], { running })) {
      if (!d.eligible) continue;
      const key = `${rule.id}:${d.obj.id}`;
      // Chỉ bắt đầu "thời gian nghỉ" khi thật sự có hành động/lỗi; bỏ qua (đang học, chạm giới hạn ngày) thì kiểm tra lại lần sau
      if (d.plan.kind === 'do' || d.plan.kind === 'error') { st.lastRule[key] = Date.now(); store.save(); }
      const range = d.range === 'today' ? '' : ` ${RANGE_LABEL[d.range]}`;
      const c0 = d.conds[0];
      const condition = {
        // 6 trường đầu = điều kiện đầu tiên (giữ cho nhật ký cũ và phần đọc kiểu cũ); conditions/match = đầy đủ khi rule có nhiều điều kiện
        metric: c0.metric, op: c0.op, range: d.range, threshold: c0.threshold, actual: Number.isFinite(c0.actual) ? c0.actual : null, actualInf: c0.actual === Infinity,
        minSpend: rule.minSpend || 0, spend: d.metrics.spend, cooldownHours: rule.cooldownHours || 0,
        match: rule.match === 'any' ? 'any' : 'all',
        conditions: d.conds.map((c) => ({ metric: c.metric, op: c.op, vs: c.vs, factor: c.factor, compareRange: c.compareRange, threshold: c.threshold, actual: Number.isFinite(c.actual) ? c.actual : null, actualInf: c.actual === Infinity, hit: c.hit, unknown: c.unknown, ...(c.tierMetric ? { tierMetric: c.tierMetric, tierCount: c.tierCount, tierAt: c.tier ? c.tier.count : null } : {}), ...(c.ladderNeed ? { ladderStep: c.ladderStep, ladderNeed: c.ladderNeed } : {}) })),
      };
      if (d.ladder) condition.ladder = { step: d.ladder.step + 1, count: d.ladder.count, need: d.ladder.need, metric: d.ladder.metric, total: (rule.steps || []).length };
      const what = d.ladder ? `Bậc ${d.ladder.step + 1}: có ${d.ladder.count} ${unitWord(d.ladder.metric)}` : d.conds.length === 1 ? `${METRIC_LABEL[c0.metric]}${range} ${showValue(c0.actual, c0.metric)}` : `${d.conds.map((c) => `${METRIC_LABEL[c.metric]} ${showValue(c.actual, c.metric)}`).join(' · ')}${range ? ` –${range}` : ''}`;
      const r = await act(d.obj, d.action, `Rule: ${rule.name} [${what}]`, { ...(d.planCtx || {}), kind: 'rule', refId: rule.id, refName: rule.name, condition });
      if (d.ladder && r === 'ok') { st.ladder = st.ladder || {}; st.ladder[key] = { date: localNow().date, step: d.ladder.step, at: Date.now() }; store.save(); }
      // Rule tắt có hẹn bật lại: ghi nhớ để tickResumes bật lại vào giờ hẹn ngày hôm sau (chạy thử thì camp không bị tắt thật nên không cần)
      const s = store.get().settings;
      if (r === 'ok' && d.action.type === 'off' && rule.resume === 'nextday' && !(s.dryRun && !s.mock)) {
        st.resume = st.resume || {};
        st.resume[key] = { ruleId: rule.id, objId: d.obj.id, date: localNow().date };
        store.save();
      }
    }
  }
}

// Bật lại các mục mà rule đã tắt, khi tới giờ hẹn (resumeAt) của một ngày sau ngày tắt.
// Bỏ hẹn nếu rule đã bị xoá / không còn hẹn bật lại, hoặc mục đã được bật lại (bạn bật tay, hoàn tác...).
async function tickResumes() {
  const st = store.get().state, now = localNow();
  const pending = Object.entries(st.resume || {});
  if (!pending.length) return;
  const rules = store.get().rules;
  const due = [];
  for (const [key, p] of pending) {
    const rule = rules.find((r) => r.id === p.ruleId);
    if (!rule || rule.action !== 'pause' || rule.resume !== 'nextday') { delete st.resume[key]; store.save(); continue; }
    if (now.date > p.date && now.minutes >= toMin(rule.resumeAt || '06:00')) due.push([key, p, rule]);
  }
  if (!due.length) return;
  const objs = await fb.listObjects(true);
  if (blocked()) return; // Facebook đang giới hạn: thử lại ở lượt sau
  for (const [key, p, rule] of due) {
    const obj = objs.find((o) => o.id === p.objId);
    delete st.resume[key];
    st.lastRule[key] = 0; // ngày mới: rule được xét lại ngay, không chờ hết thời gian nghỉ của lần tắt hôm trước
    store.save();
    if (!obj || obj.status === 'ACTIVE') continue;
    await act(obj, { type: 'on' }, `Rule: ${rule.name} [bật lại theo hẹn ${rule.resumeAt || '06:00'}]`, { kind: 'rule', refId: rule.id, refName: rule.name });
  }
}

// Xem trước: rule này đang khớp camp nào NGAY BÂY GIỜ (không thay đổi gì, không cập nhật thời gian nghỉ)
async function previewRule(rule) {
  const objs = await fb.listObjects(false);
  const maps = await loadMaps([rule], false);
  const items = evaluateRule(rule, objs, (r) => maps[r], { running: await runningOf(objs) }).map((d) => ({
    id: d.obj.id, name: d.obj.name, level: d.obj.level, effective: d.obj.effective, learning: !!d.obj.learning, budget: d.obj.dailyBudget,
    status: d.status, code: d.code, reason: d.reason, hit: d.hit,
    value: Number.isFinite(d.value) ? d.value : null, inf: d.value === Infinity, spend: d.metrics.spend, results: d.metrics.results,
    // từng điều kiện: giá trị thực tế, ngưỡng, có đúng không (để giao diện hiện rõ điều kiện nào làm rule khớp / không khớp)
    conds: (d.conds || []).map((c) => ({ metric: c.metric, op: c.op, vs: c.vs, factor: c.factor, compareRange: c.compareRange, threshold: c.threshold, actual: Number.isFinite(c.actual) ? c.actual : null, inf: c.actual === Infinity, hit: c.hit, unknown: c.unknown, ...(c.tierMetric ? { tierMetric: c.tierMetric, tierCount: c.tierCount, tierAt: c.tier ? c.tier.count : null } : {}), ...(c.ladderNeed ? { ladderStep: c.ladderStep, ladderNeed: c.ladderNeed } : {}) })),
    result: d.plan && d.plan.kind === 'do' ? { detail: d.plan.detail, notify: !!d.plan.notify } : null,
  }));
  const s = store.get().settings;
  return { ruleId: rule.id || null, range: rule.range || 'today', mode: modeNow(), willChange: !(s.dryRun && !s.mock), items, counts: { match: items.filter((i) => i.status === 'match').length, total: items.length } };
}

// Hoạt động gần đây của mỗi rule (hiện trên thẻ rule): số lần tác động / lỗi trong 7 ngày, lần gần nhất, số mục đang chờ bật lại.
// Không tính dòng "Bỏ qua" (chỉ là ghi nhận). { [ruleId]: { acts, errors, last: { ts, name, detail, dry } | null, resumePending } }
function ruleActivity(nowMs = Date.now()) {
  const d = store.get(), since = nowMs - 7 * 86400e3, out = {};
  const get = (id) => (out[id] ||= { acts: 0, errors: 0, last: null, resumePending: 0 });
  for (const l of d.logs) { // mới nhất trước
    if (l.kind !== 'rule' || !l.refId || l.skipped) continue;
    const a = get(l.refId);
    if (!a.last) a.last = { ts: l.ts, name: l.name, detail: l.detail, ok: l.ok !== false, dry: !!l.dry };
    if (Date.parse(l.ts) < since) continue;
    if (l.ok === false) a.errors++; else a.acts++;
  }
  for (const p of Object.values(d.state.resume || {})) get(p.ruleId).resumePending++;
  return out;
}

/* ----------------------------------------------------------- Dừng khẩn */
// Khi tổng chi tiêu hôm nay của mọi camp ≥ mức đặt trước: tắt hết camp đang chạy (mỗi ngày tối đa một lần)
async function checkKillSwitch(objs) {
  const s = store.get().settings, st = store.get().state;
  if (!s.killSwitchEnabled) return false;
  const globalLimit = Number(s.dailySpendLimit) || 0;
  const today = localNow().date;
  const camps = objs.filter((o) => o.level === 'campaign');
  const dry = s.dryRun && !s.mock;

  // Phạm vi "từng tài khoản": mỗi tài khoản có mức riêng (Cài đặt → Mục tiêu), chưa đặt thì dùng mức chung; mỗi tài khoản tối đa một lần mỗi ngày
  if (s.killScope === 'account') {
    st.killFiredAcc = st.killFiredAcc || {};
    for (const k of Object.keys(st.killFiredAcc)) if (st.killFiredAcc[k] !== today) delete st.killFiredAcc[k];
    const byAcc = new Map();
    for (const o of camps) { const k = o.accountId || ''; if (!byAcc.has(k)) byAcc.set(k, []); byAcc.get(k).push(o); }
    let fired = false;
    for (const [id, list] of byAcc) {
      const limit = Number(((s.accountTargets || {})[id] || {}).dailySpendLimit) || globalLimit;
      if (limit <= 0 || st.killFiredAcc[id] === today) continue;
      const spend = list.reduce((t, o) => t + o.metrics.spend, 0);
      if (spend < limit) continue;
      st.killFiredAcc[id] = today; store.save();
      const active = list.filter((o) => o.effective === 'ACTIVE');
      const cur = list[0].currency ? ` ${list[0].currency}` : '';
      await record({
        kind: 'system', source: 'Dừng khẩn', name: `Tài khoản ${list[0].accountName || id}`, ok: true, dry, mode: modeNow(),
        detail: `Chi tiêu ${money(spend)}${cur} đã vượt mức ${money(limit)}${cur} của tài khoản này — ${dry ? '(chạy thử) sẽ tắt' : 'tắt'} ${active.length} camp đang chạy (các tài khoản khác không bị ảnh hưởng).`,
        condition: { metric: 'spend', op: '>', range: 'today', threshold: limit, actual: spend, minSpend: 0, spend },
      });
      for (const o of active) await act(o, { type: 'off' }, 'Dừng khẩn', { kind: 'system', silent: true });
      fired = true;
    }
    return fired;
  }

  // Phạm vi "tổng mọi tài khoản" (mặc định, như trước)
  if (globalLimit <= 0 || st.killFired === today) return false;
  const spend = camps.reduce((t, o) => t + o.metrics.spend, 0);
  if (spend < globalLimit) return false;
  st.killFired = today; store.save();
  const active = camps.filter((o) => o.effective === 'ACTIVE');
  await record({
    kind: 'system', source: 'Dừng khẩn', name: 'Tổng chi tiêu hôm nay', ok: true, dry, mode: modeNow(),
    detail: `Chi tiêu ${money(spend)} đã vượt mức ${money(globalLimit)} — ${dry ? '(chạy thử) sẽ tắt' : 'tắt'} ${active.length} camp đang chạy.`,
    condition: { metric: 'spend', op: '>', range: 'today', threshold: globalLimit, actual: spend, minSpend: 0, spend },
  });
  for (const o of active) await act(o, { type: 'off' }, 'Dừng khẩn', { kind: 'system', silent: true });
  return true;
}

/* -------------------------------------------------------------- Hoàn tác */
const undoTools = () => import('../shared/validate.mjs');

async function undoLog(id, opts = {}) {
  const { undoBlocker } = await undoTools();
  const l = store.get().logs.find((x) => x.id === id);
  if (!l) throw httpErr(404, 'Không tìm thấy dòng nhật ký này.');
  const why = undoBlocker(l, Date.now(), UNDO_MAX_DAYS);
  if (why) throw httpErr(400, why);
  const s = store.get().settings;
  if ((l.mode === 'mock') !== !!s.mock) throw httpErr(400, 'Dòng nhật ký này được ghi ở chế độ khác (Dùng thử/Thật) nên không thể hoàn tác ở chế độ hiện tại.');
  const objs = await fb.listObjects(true);
  const cur = objs.find((o) => o.id === l.target.id);
  if (!cur) throw httpErr(404, 'Không tìm thấy camp/nhóm này trên tài khoản (có thể đã bị xoá).');

  // Nếu sau đó có ai đổi tiếp thì hỏi lại trước khi ghi đè
  const drift = [];
  if (l.after.status !== undefined && cur.status !== l.after.status) drift.push(`trạng thái hiện tại là “${cur.status === 'ACTIVE' ? 'Đang chạy' : 'Tạm dừng'}”`);
  if (l.after.dailyBudget !== undefined && cur.dailyBudget != null && Math.round(cur.dailyBudget) !== Math.round(l.after.dailyBudget)) drift.push(`ngân sách hiện tại là ${money(cur.dailyBudget)}`);
  if (drift.length && !opts.force) { const e = httpErr(409, `Camp đã thay đổi kể từ lúc đó (${drift.join(', ')}). Hoàn tác vẫn sẽ đặt về giá trị trước đó.`); e.drift = true; throw e; }

  const base = { kind: 'undo', source: 'Hoàn tác', name: l.name, refLogId: l.id, target: l.target, mode: modeNow(), before: fb.snapshot(cur), action: { ...l.action, revert: true } };
  const after = {};
  let detail = '';
  try {
    if (l.after.status !== undefined) {
      const want = l.before.status === 'ACTIVE';
      await fb.setStatus(cur.id, want);
      after.status = want ? 'ACTIVE' : 'PAUSED';
      detail = want ? 'Hoàn tác: bật lại camp' : 'Hoàn tác: tắt lại camp';
    }
    if (l.after.dailyBudget !== undefined) {
      await fb.setBudget(cur.id, l.before.dailyBudget);
      after.dailyBudget = Math.round(l.before.dailyBudget);
      detail = `Hoàn tác: ngân sách ${money(cur.dailyBudget)} → ${money(l.before.dailyBudget)}`;
    }
  } catch (e) {
    await record({ ...base, detail: e.message, ok: false, error: fb.describeError(e) });
    throw e;
  }
  const entry = await record({ ...base, detail, ok: true, after });
  store.updateLog(l.id, { undone: { at: entry.ts, logId: entry.id } });
  // Nếu việc gốc do rule làm: tạm hoãn rule đó với camp này để nó không làm lại ngay ở lần kiểm tra sau
  if (l.kind === 'rule' && l.refId) {
    const st = store.get().state; st.hold = st.hold || {};
    st.hold[`${l.refId}:${l.target.id}`] = Date.now() + UNDO_HOLD_H * 3600e3;
    store.save();
  }
  return entry;
}

// Bỏ tạm hoãn (sau hoàn tác) của 1 rule với 1 camp/nhóm: rule xét lại camp đó từ lượt kiểm tra kế tiếp. Không gọi Facebook.
async function clearHold(ruleId, objId) {
  const st = store.get().state, key = `${ruleId}:${objId}`;
  const rule = store.get().rules.find((r) => r.id === ruleId);
  if (!rule) throw httpErr(404, 'Không tìm thấy rule này.');
  if (!st.hold || !(st.hold[key] > Date.now())) throw httpErr(404, 'Rule này không còn tạm hoãn với mục đó.');
  delete st.hold[key];
  store.save();
  const obj = (fb.peekObjects() || []).find((o) => o.id === objId);
  const name = (obj && obj.name) || objId;
  return record({ kind: 'unhold', refId: rule.id, refName: rule.name, source: `Rule: ${rule.name || 'không tên'}`, name, mode: modeNow(), ok: true,
    target: { id: objId, name, ...(obj ? { level: obj.level } : {}) }, detail: 'Bỏ tạm hoãn sau hoàn tác: rule sẽ xét lại mục này từ lượt kiểm tra kế tiếp', silent: true });
}

/* ---------------------------------------------------------------- Báo cáo */
// Nhiều tài khoản quảng cáo → mỗi tài khoản một phần (loại tiền có thể khác nhau nên không cộng chung)
async function sendReport() {
  const objs = (await fb.listObjects(true)).filter((o) => o.level === 'campaign');
  const groups = new Map();
  for (const o of objs) { const k = o.accountId || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(o); }
  const multi = groups.size > 1;
  const part = (list) => {
    const active = list.filter((o) => o.effective === 'ACTIVE');
    const spend = list.reduce((t, o) => t + o.metrics.spend, 0);
    const results = list.reduce((t, o) => t + o.metrics.results, 0);
    const cur = multi && list[0].currency ? ` ${esc(list[0].currency)}` : '';
    const lines = active.slice(0, multi ? 10 : 15).map((o) => `• ${esc(o.name)}: ${money(o.metrics.spend)} | KQ ${o.metrics.results}`);
    return `${multi ? `\n🏷 <b>${esc(list[0].accountName || list[0].accountId)}</b>\n` : ''}Đang chạy: ${active.length}/${list.length} camp\nChi tiêu hôm nay: ${money(spend)}${cur}\nKết quả: ${results}${results ? ` | CPA ${money(spend / results)}${cur}` : ''}${lines.length ? `\n${lines.join('\n')}` : ''}`;
  };
  const text = `📊 <b>Báo cáo Facebook Ads</b>\n${[...groups.values()].map(part).join('\n')}`;
  return notify.send(text); // { configured, results: [{ id, ok, error? }] } cho từng người nhận
}

// Báo cáo tuần: tuần trước (thứ Hai → Chủ nhật) so với tuần liền trước đó, theo từng tài khoản quảng cáo.
// Chỉ tính các camp còn trong danh sách hiện tại (camp đã xoá không có tên nên bỏ qua).
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10);
const dm = (iso) => `${iso.slice(8)}/${iso.slice(5, 7)}`;
function lastWeek(now = localNow()) {
  const until = addDays(now.date, -((now.day + 6) % 7) - 1); // Chủ nhật gần nhất đã qua
  return { since: addDays(until, -6), until, prevSince: addDays(until, -13), prevUntil: addDays(until, -7) };
}
const weekData = async (since, until) => (await fb.rangeData({ key: `r:${since}_${until}`, fbParams: { time_range: JSON.stringify({ since, until }) }, days: 7 })).data;
const change = (a, b) => (b > 0 ? `${a >= b ? '+' : ''}${Math.round((a / b - 1) * 100)}%` : '');
// Camp tốt nhất: CPA thấp nhất (có kết quả). Tệ nhất: chi tiêu mà không có kết quả, rồi tới CPA cao nhất.
function rankCamps(rows) {
  const spent = rows.filter((r) => r.m.spend > 0);
  const best = spent.filter((r) => r.m.results > 0).sort((a, b) => a.m.cpa - b.m.cpa).slice(0, 3);
  const cost = (r) => (r.m.results ? r.m.cpa : Infinity);
  const worst = spent.filter((r) => !best.includes(r)).sort((a, b) => cost(b) - cost(a) || b.m.spend - a.m.spend).slice(0, 3); // Infinity - Infinity = NaN → so chi tiêu
  return { best, worst };
}
// Số lần tool tự thao tác thật trong tuần (lịch, rule, dừng khẩn), không tính chạy thử và thao tác tay
function toolActions(since, until) {
  const mock = !!store.get().settings.mock, n = { on: 0, off: 0, budget: 0 };
  for (const l of store.get().logs) {
    if (!['rule', 'schedule', 'system'].includes(l.kind) || !l.ok || l.dry || (l.mode === 'mock') !== mock || !l.action || !(l.action.type in n)) continue;
    const d = localNow(new Date(l.ts)).date;
    if (d >= since && d <= until) n[l.action.type]++;
  }
  return n;
}
async function sendWeeklyReport(now = localNow()) {
  const w = lastWeek(now);
  const objs = (await fb.listObjects()).filter((o) => o.level === 'campaign');
  const cur = await weekData(w.since, w.until), prev = await weekData(w.prevSince, w.prevUntil);
  const groups = new Map();
  for (const o of objs) { const k = o.accountId || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(o); }
  const multi = groups.size > 1;
  const sum = (list, data) => list.reduce((t, o) => { const m = data[o.id]; if (m) { t.spend += m.spend; t.results += m.results; t.revenue += m.revenue || 0; } return t; }, { spend: 0, results: 0, revenue: 0 });
  const part = (list) => {
    const c = sum(list, cur), p = sum(list, prev), unit = list[0].currency ? ` ${esc(list[0].currency)}` : '';
    const cpa = c.results ? c.spend / c.results : null, pcpa = p.results ? p.spend / p.results : null;
    const lines = [
      `Chi tiêu: <b>${money(c.spend)}${unit}</b>${p.spend ? ` (${change(c.spend, p.spend)} so với tuần trước)` : ''}`,
      `Kết quả: <b>${c.results}</b>${p.results ? ` (${change(c.results, p.results)})` : ''}`,
    ];
    if (cpa != null) lines.push(`CPA: <b>${money(cpa)}${unit}</b>${pcpa ? ` (${change(cpa, pcpa)})` : ''}`);
    if (c.revenue > 0 && c.spend > 0) lines.push(`ROAS: <b>${(c.revenue / c.spend).toFixed(2)}</b>${p.revenue > 0 && p.spend > 0 ? ` (tuần trước ${(p.revenue / p.spend).toFixed(2)})` : ''}`);
    const { best, worst } = rankCamps(list.map((o) => ({ o, m: cur[o.id] })).filter((r) => r.m));
    const row = (r) => `• ${esc(r.o.name)}: ${r.m.results ? `CPA ${money(r.m.cpa)}, ${r.m.results} KQ` : `chi ${money(r.m.spend)}, chưa có KQ`}`;
    if (best.length) lines.push(`👍 Tốt nhất:\n${best.map(row).join('\n')}`);
    if (worst.length) lines.push(`👎 Cần xem lại:\n${worst.map(row).join('\n')}`);
    return `${multi ? `\n🏷 <b>${esc(list[0].accountName || list[0].accountId)}</b>\n` : ''}${lines.join('\n')}`;
  };
  const n = toolActions(w.since, w.until), total = n.on + n.off + n.budget;
  const acts = total ? `\n\n🤖 Tool đã tự thao tác ${total} lần: bật ${n.on}, tắt ${n.off}, đổi ngân sách ${n.budget}.` : '\n\n🤖 Tuần qua tool không tự thao tác lần nào.';
  const body = groups.size ? [...groups.values()].map(part).join('\n') : 'Chưa có camp nào.';
  return notify.send(`🗓 <b>Báo cáo tuần ${dm(w.since)} – ${dm(w.until)}</b>\n${body}${acts}`);
}

async function tickReport() {
  const s = store.get().settings, st = store.get().state, now = localNow();
  if (!s.reportTime || !s.telegramToken) return;
  const at = toMin(s.reportTime);
  if (now.minutes < at || now.minutes - at > GRACE_MIN) return;
  if (st.lastReport !== now.date) {
    st.lastReport = now.date; store.save();
    await sendReport();
  }
  if (s.weeklyReport !== false && now.day === 1 && st.lastWeekly !== now.date) { // thứ Hai
    st.lastWeekly = now.date; store.save();
    await sendWeeklyReport(now);
  }
}

let lastRules = 0, busy = false;
async function tick() {
  if (busy) return;
  busy = true;
  watch.tickStarted();
  let failed = null;
  try {
    await tickSchedules();
    const every = (store.get().settings.ruleIntervalMin || 15) * 60e3;
    await tickResumes();
    if (Date.now() - lastRules >= every) { lastRules = Date.now(); await runRules(); }
    await tickReport();
    try { await watch.tickToken(localNow()); } catch (e) { console.error('Lỗi kiểm tra token:', e.message); } // không tính là lượt lỗi
    try { await alerts.tickAlerts(localNow()); } catch (e) { console.error('Lỗi kiểm tra cảnh báo:', e.message); }
    try { await company.tick(localNow()); } catch (e) { console.error('Lỗi báo cáo công ty:', e.message); }
  } catch (e) {
    failed = e;
    console.error('Lỗi tick:', e.message);
    store.log({ kind: 'system', source: 'Hệ thống', name: '-', detail: e.message, ok: false, mode: modeNow(), error: fb.describeError(e) });
  } finally { busy = false; }
  // bị Facebook giới hạn số lần gọi: tool đã tự chờ, không tính là lỗi của vòng tự động
  try { await watch.tickDone(failed && !fb.isRateLimited(failed) ? failed : null); } catch (e) { console.error('Lỗi báo trạng thái vòng tự động:', e.message); }
}

function start() { setInterval(tick, 30000); setTimeout(tick, 3000); watch.start(); }

module.exports = { start, localNow, tickSchedules, tickResumes, tickReport, ruleActivity, runSchedule, runRules, sendReport, sendWeeklyReport, lastWeek, rankCamps, previewRule, undoLog, clearHold, checkKillSwitch, plan, evaluateRule, act, metricValue };
