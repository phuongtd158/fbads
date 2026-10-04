// Báo cáo lên hệ thống nội bộ của công ty theo các mốc 9h / 12h / 17h / 22h.
//  - Đến mốc: với mỗi Team đã cấu hình, cộng số Facebook của các chiến dịch thuộc Team (shared/companyReport.mjs) thành một
//    bản báo cáo, rồi gửi qua Telegram. Đơn hàng = kết quả, DSO sau VAT = doanh thu; sửa tay được trước khi gửi.
//  - Chế độ "Chỉ xem": chỉ tạo bản báo cáo + báo Telegram, không bao giờ gửi lên công ty.
//  - Chế độ "Duyệt trước": chỉ gửi khi người dùng bấm Gửi (web, hoặc Telegram có hỏi lại).
//  - Chế độ "Tự động gửi": đến mốc gửi luôn; số bất thường (shared anomalies) thì dừng cho người dùng xem;
//    lỗi mạng / hệ thống công ty lỗi 5xx thì thử lại tối đa 3 lần, sai mật khẩu / bị từ chối thì báo ngay.
//  - Trước khi gửi luôn hỏi hệ thống công ty mốc đó đã có báo cáo chưa; có rồi thì không gửi đè.
const store = require('./store');
const fb = require('./fb');
const notify = require('./notify');
const api = require('./companyApi');

let sharedMod = null; // shared/companyReport.mjs là ES module → nạp 1 lần khi cần
const C = () => (sharedMod ||= import('../shared/companyReport.mjs'));

const GRACE_MIN = 10; // máy bật trễ tối đa 10 phút vẫn tạo báo cáo của mốc (như lịch tự động)
const KEEP = 300;     // giữ tối đa 300 bản báo cáo gần nhất
const inflight = new Set();

const money = (n) => Math.round(Number(n) || 0).toLocaleString('vi-VN');
const esc = (t) => String(t == null ? '' : t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const dm = (iso) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}`;
const httpErr = (status, message) => { const e = new Error(message); e.status = status; return e; };
const modeNow = () => { const s = store.get().settings; return s.mock ? 'mock' : s.dryRun ? 'dry' : 'live'; };

const config = () => store.get().company;
const reports = () => store.get().companyReports;
// Cài đặt gửi về giao diện: không bao giờ lộ mật khẩu
function publicConfig() {
  const { password, ...rest } = config();
  return { ...rest, has_password: !!password };
}

const teamLabel = (r) => [r.teamCode, r.teamName].filter(Boolean).join(' · ') || r.teamId;
const find = (id) => reports().find((r) => r.id === id) || null;

// Số Facebook của một Team cho một mốc → { metrics (5 số Facebook), campaigns: [tên chiến dịch] }
async function build(team, slot) {
  const S = await C();
  const objs = (await fb.listObjects()).filter((o) => o.level === 'campaign');
  const camps = S.teamCampaigns(team, objs);
  const data = await fb.rangeMetrics(S.rangeOf(slot));
  return { metrics: S.sumMetrics(camps, data), campaigns: camps.map((o) => o.name) };
}

// Tin Telegram của một bản báo cáo
async function summary(r, head = '📋') {
  const S = await C();
  const val = (m) => (r.metrics[m.key] == null ? '<i>chưa nhập</i>' : `<b>${money(r.metrics[m.key])}</b>`);
  const by = Object.fromEntries(S.METRICS.map((m) => [m.key, m]));
  const lines = [
    `${head} <b>Báo cáo công ty · ${r.slot}h ngày ${dm(r.date)}</b>`,
    `<b>${esc(teamLabel(r))}</b> (${r.campaigns ? r.campaigns.length : 0} chiến dịch)`,
    `Chi tiêu Ads: ${val(by.spend)}`,
    `Tin nhắn: ${val(by.messages)} · SĐT: ${val(by.phones)}`,
    `Hiển thị: ${val(by.impressions)} · Nhấp: ${val(by.clicks)}`,
    `Đơn hàng: ${val(by.orders)} · DSO sau VAT: ${val(by.dso_after)}`,
  ];
  if (r.notes) lines.push(`Ghi chú: ${esc(r.notes)}`);
  return lines.join('\n');
}

// Nút dưới tin (chỉ hiện khi bật "Nhận lệnh từ Telegram"): sửa Đơn/DSO, gửi tay (bot hỏi lại trước khi gửi)
const sendable = () => ['approve', 'auto'].includes(config().mode);
function buttonsOf(r) {
  if (!sendable() || r.status === 'sent' || r.status === 'exists') return undefined;
  return [[{ text: '✏️ Sửa Đơn/DSO', data: `ce:${r.id}` }, { text: '✅ Gửi lên công ty', data: `cs:${r.id}` }]];
}
const manualHint = () => (store.get().settings.telegramCommands ? 'Kiểm tra số rồi bấm <b>Gửi lên công ty</b>.' : 'Mở tool → <b>Báo cáo công ty</b> để kiểm tra và gửi.');

// Tin báo bản báo cáo vừa tạo (chế độ Chỉ xem / Duyệt trước, hoặc Tự động gửi khi đang Dùng thử)
async function notifyDraft(r) {
  let foot;
  if (!sendable()) foot = '\n\n<i>Chế độ Chỉ xem: tool không gửi báo cáo này lên công ty.</i>';
  else if (store.get().settings.mock) foot = '\n\n<i>Đang dùng dữ liệu giả (Dùng thử): tool không gửi lên công ty.</i>';
  else foot = `\n\n${manualHint()}`;
  return notify.send((await summary(r)) + foot, { buttons: buttonsOf(r) });
}

// Tạo (hoặc làm mới số Facebook của) bản báo cáo cho mọi Team ở một mốc. Bản đã gửi thì giữ nguyên.
// Số đã sửa tay (r.edited) và ghi chú được giữ lại khi làm mới. → danh sách bản báo cáo của mốc
async function createDrafts(slot, today, { silent = false } = {}) {
  const S = await C();
  const c = config(), date = S.reportDate(slot, today), out = [];
  for (const team of c.teams || []) {
    let r = reports().find((x) => x.teamId === team.id && x.date === date && Number(x.slot) === Number(slot));
    if (r && (r.status === 'sent' || r.status === 'exists')) { out.push(r); continue; }
    let built;
    try { built = await build(team, slot); } catch (e) {
      const label = team.code || team.name || team.id;
      store.log({ kind: 'company', source: 'Báo cáo công ty', name: label, detail: `Không lấy được số Facebook cho mốc ${slot}h: ${e.message}`, ok: false, mode: modeNow(), error: fb.describeError(e) });
      if (!silent || c.mode === 'auto') await notify.send(`❌ <b>Báo cáo công ty · ${slot}h ngày ${dm(date)}</b>\n<b>${esc(label)}</b>: không lấy được số Facebook nên chưa tạo báo cáo.\n${esc(e.message)}`);
      continue;
    }
    const now = new Date().toISOString();
    if (!r) {
      r = { id: store.uid(), teamId: team.id, teamCode: team.code, teamName: team.name, date, slot: Number(slot), metrics: {}, edited: [], notes: '', issue: '', resolution: '', status: 'pending', createdAt: now };
      reports().unshift(r);
    }
    Object.assign(r, { teamCode: team.code, teamName: team.name, campaigns: built.campaigns, updatedAt: now, builtAt: now, status: 'pending', error: '', reasons: [], attempts: 0, nextTryAt: null });
    const edited = new Set(r.edited || []);
    for (const [k, v] of Object.entries(built.metrics)) if (!edited.has(k)) r.metrics[k] = v;
    out.push(r);
  }
  if (reports().length > KEEP) reports().length = KEEP;
  store.save();
  if (!silent) for (const r of out) if (r.status === 'pending') await notifyDraft(r);
  return out;
}

/* ------------------------------------------------------------------ Tự động gửi */
const RETRY_MAX = 3;       // lỗi mạng / hệ thống công ty lỗi 5xx: thử tối đa 3 lần
const RETRY_GAP_MIN = 10;  // cách nhau 10 phút
const retryable = (e) => e && (e.status === 502 || e.status === 504);
const AUTO_SOURCE = 'Báo cáo công ty (tự động)';

async function attemptAuto(r) {
  r.attempts = (r.attempts || 0) + 1;
  try {
    await send(r.id, { source: AUTO_SOURCE });
    return notify.send(`${await summary(r, '✅')}\n\nĐã tự gửi lên công ty${r.remoteStatus === 'LATE' ? ' (công ty ghi nhận nộp muộn)' : ''}.`);
  } catch (e) {
    if (r.status === 'exists') return notify.send(`ℹ️ <b>Báo cáo công ty · ${r.slot}h ngày ${dm(r.date)}</b>\n<b>${esc(teamLabel(r))}</b>: hệ thống công ty đã có báo cáo của mốc này nên tool không gửi đè.`);
    if (retryable(e) && r.attempts < RETRY_MAX) {
      Object.assign(r, { status: 'retry', nextTryAt: new Date(Date.now() + RETRY_GAP_MIN * 60e3).toISOString() });
      store.save();
      return null;
    }
    Object.assign(r, { status: 'failed', nextTryAt: null });
    store.save();
    const tries = r.attempts > 1 ? ` (đã thử ${r.attempts} lần)` : '';
    return notify.send(`${await summary(r, '❌')}\n\nKhông tự gửi được${tries}: ${esc(e.message)}\n${manualHint()}`, { buttons: buttonsOf(r) });
  }
}

// Chế độ Tự động gửi: số bình thường thì gửi luôn; số bất thường thì dừng, nhắn để người dùng xem rồi gửi tay
async function autoSend(list) {
  const S = await C();
  for (const r of list) {
    if (r.status !== 'pending') continue;
    if (store.get().settings.mock) { await notifyDraft(r); continue; } // Dùng thử: không gửi, chỉ báo
    const reasons = S.anomalies(r);
    if (reasons.length) {
      Object.assign(r, { status: 'review', reasons });
      store.save();
      store.log({ kind: 'company', source: AUTO_SOURCE, name: teamLabel(r), detail: `Chưa tự gửi báo cáo ${r.slot}h ngày ${dm(r.date)}: ${reasons.join('; ')}`, ok: false, skipped: true, mode: modeNow() });
      await notify.send(`${await summary(r, '⚠️')}\n\nChưa tự gửi vì số trông bất thường:\n${reasons.map((x) => `• ${esc(x)}`).join('\n')}\n${manualHint()}`, { buttons: buttonsOf(r) });
      continue;
    }
    await attemptAuto(r);
  }
}

// Gọi mỗi vòng tự động (lib/engine.js) với giờ địa phương { date, minutes }
async function tick(now) {
  const c = config();
  if (!c.enabled || !(c.teams || []).length) return;
  const st = store.get().state;
  st.companyFired = st.companyFired || {};
  for (const slot of c.slots || []) {
    const at = slot * 60, key = `${now.date}:${slot}`;
    if (now.minutes < at || now.minutes - at > GRACE_MIN || st.companyFired[key]) continue;
    st.companyFired[key] = true;
    for (const k of Object.keys(st.companyFired)) if (k.slice(0, 10) < now.date) delete st.companyFired[k]; // chỉ giữ hôm nay
    store.save();
    const auto = c.mode === 'auto';
    const list = await createDrafts(slot, now.date, { silent: auto });
    if (auto) await autoSend(list);
  }
  // Bản đang chờ thử lại (lỗi mạng / hệ thống công ty lỗi) đã đến giờ
  if (c.mode === 'auto') {
    for (const r of reports().filter((x) => x.status === 'retry' && x.nextTryAt && Date.parse(x.nextTryAt) <= Date.now())) await attemptAuto(r);
  }
}

// Sửa số / ghi chú (web hoặc Telegram). Bản đã gửi thì không sửa được nữa. Số đã sửa tay không bị số Facebook ghi đè khi làm mới.
async function update(id, patch) {
  const S = await C();
  const r = find(id);
  if (!r) throw httpErr(404, 'Không tìm thấy bản báo cáo này');
  if (r.status === 'sent') throw httpErr(400, 'Báo cáo đã gửi lên công ty, không sửa ở tool được nữa. Muốn sửa hãy vào web công ty.');
  const v = S.validateReportPatch(patch);
  if (!v.ok) { const e = httpErr(400, v.first); e.errors = v.errors; throw e; }
  if (v.value.metrics) {
    const edited = new Set(r.edited || []);
    for (const [k, val] of Object.entries(v.value.metrics)) if (r.metrics[k] !== val) { r.metrics[k] = val; edited.add(k); }
    r.edited = [...edited];
  }
  for (const k of S.TEXT_KEYS) if (k in v.value) r[k] = v.value[k];
  r.updatedAt = new Date().toISOString();
  store.save();
  return r;
}

function logSend(r, ok, detail, source, error) {
  store.log({ kind: 'company', source, name: teamLabel(r), detail, ok, mode: modeNow(), ...(error ? { error: { message: error } } : {}) });
}

// Gửi lên hệ thống công ty: người dùng bấm Gửi, hoặc chế độ Tự động gửi. source: nguồn ghi Nhật ký.
async function send(id, { source = 'Báo cáo công ty' } = {}) {
  const S = await C();
  const r = find(id);
  if (!r) throw httpErr(404, 'Không tìm thấy bản báo cáo này');
  if (r.status === 'sent') throw httpErr(400, 'Báo cáo này đã gửi rồi.');
  if (!sendable()) throw httpErr(400, 'Đang ở chế độ Chỉ xem nên tool không gửi lên công ty. Đổi chế độ gửi ở Cài đặt → Báo cáo công ty.');
  if (store.get().settings.mock) throw httpErr(400, 'Tool đang dùng dữ liệu giả (chế độ Dùng thử) nên không gửi báo cáo lên công ty.');
  const miss = S.missingMetrics(r);
  if (miss.length) throw httpErr(400, `Còn thiếu: ${miss.map((m) => m.label).join(', ')}. Nhập đủ rồi mới gửi.`);
  if (inflight.has(id)) throw httpErr(409, 'Báo cáo này đang được gửi.');
  inflight.add(id);
  const what = `báo cáo ${r.slot}h ngày ${dm(r.date)}`;
  try {
    const exist = await api.findReport(r.teamId, r.date, r.slot);
    if (exist) {
      Object.assign(r, { status: 'exists', remoteId: exist.id || '', remoteStatus: exist.status || '', error: 'Hệ thống công ty đã có báo cáo của mốc này (tool không gửi đè). Muốn sửa hãy vào web công ty.', updatedAt: new Date().toISOString() });
      store.save();
      logSend(r, false, `Không gửi ${what}: công ty đã có báo cáo của mốc này`, source);
      throw httpErr(409, r.error);
    }
    const res = await api.submitReport(S.payloadOf(r));
    Object.assign(r, { status: 'sent', sentAt: new Date().toISOString(), remoteId: (res && res.id) || '', remoteStatus: (res && res.status) || '', error: '', reasons: [], nextTryAt: null });
    store.save();
    logSend(r, true, `Đã gửi ${what} lên công ty${r.remoteStatus === 'LATE' ? ' (công ty ghi nhận nộp muộn)' : ''}`, source);
    return r;
  } catch (e) {
    if (r.status !== 'exists') {
      Object.assign(r, { status: 'failed', error: e.message, updatedAt: new Date().toISOString() });
      store.save();
      logSend(r, false, `Gửi ${what} thất bại: ${e.message}`, source, e.message);
    }
    throw e;
  } finally { inflight.delete(id); }
}

function remove(id) {
  const i = reports().findIndex((r) => r.id === id);
  if (i < 0) throw httpErr(404, 'Không tìm thấy bản báo cáo này');
  reports().splice(i, 1);
  store.save();
}

module.exports = { publicConfig, build, createDrafts, autoSend, tick, update, send, remove, find, summary, notifyDraft, teamLabel };
