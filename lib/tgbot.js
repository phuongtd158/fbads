// Điều khiển tool qua Telegram (bật ở Cài đặt → Telegram → "Nhận lệnh từ Telegram", mặc định tắt).
// Tool tự hỏi Telegram có tin mới (long polling getUpdates) nên không cần địa chỉ công khai: chạy được trên VPS, Render, máy nhà.
//  - Chỉ nhận lệnh từ các Chat ID đã nhập trong Cài đặt. Người lạ nhắn /start hoặc /id chỉ được trả lời Chat ID của họ.
//  - Lệnh: /status, /camps (mỗi camp một nút Tắt), /report, /id, /help.
//  - Nút trên tin báo (lib/notify.js): Hoàn tác (u:<mã nhật ký>), Tắt camp (o:<mã camp>). Bấm nút thì bot hỏi lại
//    "Chắc chắn?" (yu:/yo:), xác nhận quá 10 phút thì hết hạn. Thao tác ghi Nhật ký với nguồn "Telegram".
//  - Báo cáo công ty (lib/companyReport.js): Nhập Đơn/DSO (ce:<mã báo cáo>) → bot hỏi, người dùng nhắn lại 2 số;
//    Gửi lên công ty (cs:) → bot hỏi lại (ycs:) rồi mới gửi.
//    Cập nhật báo cáo đã có trên công ty (cu:) → bot hỏi lý do, người dùng nhắn lý do → bot hỏi lại (ycu:) rồi mới cập nhật.
//  - Chỉ một bản tool được hỏi tin với cùng bot: hai bản cùng hỏi thì Telegram báo 409, tool ghi cảnh báo và chờ.
const crypto = require('crypto');
const store = require('./store');
const fb = require('./fb');
const engine = require('./engine');
const notify = require('./notify');
const watch = require('./watch');
const company = require('./companyReport');

const POLL_S = 25;              // Telegram giữ kết nối tối đa 25 giây nếu chưa có tin
const CONFIRM_TTL_S = 10 * 60;  // nút "Có" chỉ dùng được trong 10 phút
const MAX_CAMPS = 20;

const esc = (t) => String(t == null ? '' : t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const money = (n) => Math.round(n).toLocaleString('vi-VN');
const short = (t, n = 28) => (String(t).length > n ? `${String(t).slice(0, n - 1)}…` : String(t));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const modeOf = (s) => (s.mock ? 'mock' : s.dryRun ? 'dry' : 'live');
const MODE_TEXT = { mock: 'Dùng thử (dữ liệu giả)', dry: 'Chạy thử (lịch/rule chỉ ghi nhật ký)', live: 'Thật' };

// Trạng thái cho giao diện (Cài đặt → Telegram)
const status = { polling: false, lastPollAt: null, lastError: '' };

// ---------- Gọi Telegram ----------
async function tg(method, body, timeoutMs = 15e3) {
  const token = store.get().settings.telegramToken;
  let r;
  try {
    r = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}), signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (e) {
    const err = new Error(e && (e.name === 'TimeoutError' || e.name === 'AbortError') ? 'Telegram không trả lời.' : 'Không kết nối được tới Telegram.');
    err.network = true; throw err;
  }
  let j = {};
  try { j = await r.json(); } catch { /* không phải JSON */ }
  if (!j.ok) {
    const err = new Error(notify.friendlyTg(j.error_code || r.status, j.description).split(token).join('***'));
    err.code = j.error_code || r.status; throw err;
  }
  return j.result;
}
const keyboard = (rows) => ({ inline_keyboard: rows.map((row) => row.map((b) => ({ text: b.text, callback_data: b.data }))) });
const say = (chat, text, rows) => tg('sendMessage', { chat_id: chat, text, parse_mode: 'HTML', disable_web_page_preview: true, ...(rows ? { reply_markup: keyboard(rows) } : {}) });
const edit = (chat, msgId, text, rows) => tg('editMessageText', { chat_id: chat, message_id: msgId, text, parse_mode: 'HTML', reply_markup: keyboard(rows || []) });

// Chat đang được hỏi Đơn/DSO cho một bản báo cáo công ty: { [chat]: { id, until } }
const awaiting = {};
const reasons = {}; // mã báo cáo → lý do cập nhật vừa nhắn (chờ bấm xác nhận)
let sharedMod = null;
const C = () => (sharedMod ||= import('../shared/companyReport.mjs'));

const allowed = (chatId) => notify.chatIdsOf(store.get().settings.telegramChatId).map(String).includes(String(chatId));

// ---------- Lệnh ----------
async function cmdStatus(chat) {
  const s = store.get().settings, d = store.get();
  const h = watch.health();
  const lines = [`📟 <b>Trạng thái tool</b>`, `Chế độ: ${MODE_TEXT[modeOf(s)]}`,
    `Vòng tự động: ${h.ok ? 'đang chạy' : '⚠️ không chạy'}${h.lastTickAt ? `, lượt gần nhất ${new Date(h.lastTickAt).toLocaleTimeString('vi-VN', { timeZone: s.timezone || 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit' })}` : ''}`,
    `Lịch đang bật: ${d.schedules.filter((x) => x.enabled !== false).length} · Rule đang bật: ${d.rules.filter((x) => x.enabled).length}`];
  try {
    const camps = (await fb.listObjects(false)).filter((o) => o.level === 'campaign');
    const groups = new Map();
    for (const o of camps) { const k = o.accountId || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(o); }
    for (const list of groups.values()) {
      const spend = list.reduce((t, o) => t + o.metrics.spend, 0), results = list.reduce((t, o) => t + o.metrics.results, 0);
      const running = list.filter((o) => o.effective === 'ACTIVE').length;
      lines.push(`\n🏷 <b>${esc(list[0].accountName || list[0].accountId || 'Tài khoản')}</b>`, `Hôm nay chi ${money(spend)} ${esc(list[0].currency || '')} · ${results} kết quả · ${running}/${list.length} camp đang chạy`);
    }
  } catch (e) { lines.push(`\nChưa lấy được số liệu Facebook: ${esc(e.message)}`); }
  return say(chat, lines.join('\n'));
}

async function cmdCamps(chat) {
  const camps = (await fb.listObjects(false)).filter((o) => o.level === 'campaign' && o.effective === 'ACTIVE').sort((a, b) => b.metrics.spend - a.metrics.spend);
  if (!camps.length) return say(chat, 'Không có camp nào đang chạy.');
  const top = camps.slice(0, MAX_CAMPS);
  const text = [`▶️ <b>${camps.length} camp đang chạy</b>${camps.length > MAX_CAMPS ? ` (hiện ${MAX_CAMPS} camp chi nhiều nhất)` : ''}`,
    ...top.map((o) => `• ${esc(o.name)}: ${money(o.metrics.spend)} | KQ ${o.metrics.results}`)].join('\n');
  return say(chat, text, top.map((o) => [{ text: `⏸ Tắt: ${short(o.name)}`, data: `o:${o.id}` }]));
}

const HELP = ['🤖 <b>Lệnh điều khiển tool</b>', '/status: chế độ, vòng tự động, chi tiêu hôm nay', '/camps: camp đang chạy, kèm nút tắt', '/report: gửi báo cáo ngay', '/id: xem Chat ID của cuộc trò chuyện này',
  '\nTin báo của lịch/rule có nút <b>Hoàn tác</b>; bấm nút nào bot cũng hỏi lại trước khi làm.'].join('\n');

// Người dùng nhắn số Đơn/DSO sau khi bấm "Nhập Đơn/DSO"
async function onOrdersDso(chat, text) {
  const w = awaiting[chat];
  delete awaiting[chat];
  const S = await C();
  const v = S.parseOrdersDso(text);
  if (!v) {
    awaiting[chat] = w; // cho nhập lại
    return say(chat, 'Chưa hiểu. Hãy nhắn 2 số: <b>số đơn</b> rồi <b>DSO sau VAT</b>, ví dụ <code>12 3500000</code> hoặc <code>12 3,5tr</code>.');
  }
  let r;
  try { r = await company.update(w.id, { metrics: v }); } catch (e) { return say(chat, `❌ ${esc(e.message)}`); }
  const main = company.updatable(r) ? { text: '🔁 Cập nhật lên công ty', data: `cu:${r.id}` } : { text: '✅ Gửi lên công ty', data: `cs:${r.id}` };
  return say(chat, `${await company.summary(r, '✏️')}\n\nĐã lưu Đơn/DSO. Kiểm tra lại số rồi bấm ${company.updatable(r) ? 'cập nhật' : 'gửi'}.`, [[main, { text: '✏️ Sửa', data: `ce:${r.id}` }]]);
}

// Người dùng nhắn lý do sau khi bấm "Cập nhật lên công ty"
async function onReason(chat, text) {
  const w = awaiting[chat];
  delete awaiting[chat];
  const S = await C();
  const bad = S.validateReason(text);
  if (bad) { awaiting[chat] = w; return say(chat, `${esc(bad)}. Nhắn lại lý do nhé.`); }
  const r = company.find(w.id);
  if (!r) return say(chat, 'Không tìm thấy bản báo cáo này (có thể đã bị xoá).');
  reasons[r.id] = text.trim();
  const diff = S.diffRemote(r);
  const by = Object.fromEntries(S.METRICS.map((m) => [m.key, m]));
  const lines = diff.filter((k) => by[k]).map((k) => `• ${by[k].label}: ${money(r.remote.metrics[k] || 0)} → <b>${money(r.metrics[k])}</b>`);
  const changes = lines.length ? `\n\nThay đổi so với số trên công ty:\n${lines.join('\n')}` : diff.length ? '\n\nChỉ đổi ghi chú / vấn đề / hướng xử lý.' : '\n\n<i>Số trên tool giống hệt số đã nộp.</i>';
  return say(chat, `${await company.summary(r, '❓')}${changes}\nLý do: ${esc(reasons[r.id])}\n\nCập nhật báo cáo này lên hệ thống công ty? Công ty sẽ tính lại KPI.`, [[{ text: '🔁 Có, cập nhật', data: `ycu:${r.id}` }, { text: 'Huỷ', data: 'x' }]]);
}

async function onMessage(m) {
  const chat = m.chat && m.chat.id;
  const text = String(m.text || '').trim();
  if (chat && text && !text.startsWith('/') && awaiting[chat] && allowed(chat)) {
    if (Date.now() > awaiting[chat].until) delete awaiting[chat];
    else return awaiting[chat].kind === 'reason' ? onReason(chat, text) : onOrdersDso(chat, text);
  }
  if (!chat || !text.startsWith('/')) return;
  const cmd = text.split(/\s+/)[0].split('@')[0].toLowerCase();
  if (cmd === '/id') return say(chat, `Chat ID của cuộc trò chuyện này: <code>${esc(chat)}</code>`);
  if (!allowed(chat)) {
    // người lạ: chỉ cho biết Chat ID (để chủ tool thêm vào Cài đặt nếu muốn), không lộ gì khác
    if (cmd === '/start') return say(chat, `Chat ID của bạn là <code>${esc(chat)}</code>.\nNhập số này vào Cài đặt → Telegram của tool để nhận thông báo và điều khiển tool.`);
    return;
  }
  if (cmd === '/status') return cmdStatus(chat);
  if (cmd === '/camps') return cmdCamps(chat);
  if (cmd === '/report') { await engine.sendReport(); return; }
  return say(chat, HELP);
}

// ---------- Nút bấm ----------
const findObj = async (id) => (await fb.listObjects(false)).find((o) => o.id === id);

// Tắt camp từ Telegram: như bật/tắt tay trên giao diện (làm thật, kể cả ở chế độ Chạy thử), ghi Nhật ký nguồn "Telegram"
async function pauseObj(id) {
  const s = store.get().settings;
  const cur = await findObj(id);
  if (!cur) throw new Error('Không tìm thấy camp/nhóm QC này (có thể đã bị xoá).');
  const unit = cur.level === 'adset' ? 'nhóm QC' : 'camp';
  if (cur.status !== 'ACTIVE') return `${esc(cur.name)} đã tắt từ trước, không cần làm gì.`;
  const base = { kind: 'manual', source: 'Telegram', name: cur.name, target: { id, name: cur.name, level: cur.level, ...(cur.accountId ? { accountId: cur.accountId, accountName: cur.accountName } : {}) },
    action: { type: 'off' }, before: fb.snapshot(cur), mode: modeOf(s) };
  try { await fb.setStatus(id, false); } catch (e) {
    store.log({ ...base, detail: e.message, ok: false, error: fb.describeError(e) });
    throw e;
  }
  store.log({ ...base, detail: `Tắt ${unit} (từ Telegram)`, ok: true, after: { status: 'PAUSED' } });
  return `✅ Đã tắt <b>${esc(cur.name)}</b>.`;
}

async function onCallback(q) {
  const msg = q.message || {}, chat = msg.chat && msg.chat.id, data = String(q.data || '');
  const answer = (text) => tg('answerCallbackQuery', { callback_query_id: q.id, ...(text ? { text } : {}) }).catch(() => {});
  if (!chat || !allowed(chat)) return answer('Chat này không có quyền điều khiển tool.');
  const [kind, ...rest] = data.split(':');
  const arg = rest.join(':');

  // Bước 1: hỏi lại
  if (kind === 'o' || kind === 'u') {
    await answer();
    if (kind === 'o') {
      const o = await findObj(arg);
      if (!o) return say(chat, 'Không tìm thấy camp/nhóm QC này (có thể đã bị xoá).');
      return say(chat, `Tắt <b>${esc(o.name)}</b>?\nHôm nay đã chi ${money(o.metrics.spend)}, ${o.metrics.results} kết quả.`, [[{ text: '⏸ Có, tắt', data: `yo:${arg}` }, { text: 'Huỷ', data: 'x' }]]);
    }
    const l = store.get().logs.find((x) => x.id === arg);
    if (!l) return say(chat, 'Không tìm thấy dòng nhật ký này (có thể đã quá cũ).');
    return say(chat, `Hoàn tác thay đổi này?\n<b>${esc(l.target && l.target.name || l.name)}</b>: ${esc(l.detail)}`, [[{ text: '↩️ Có, hoàn tác', data: `yu:${arg}` }, { text: 'Huỷ', data: 'x' }]]);
  }
  if (kind === 'x') { await answer(); return edit(chat, msg.message_id, 'Đã huỷ.'); }

  // Báo cáo công ty: nhập Đơn/DSO, hỏi lại trước khi gửi
  if (kind === 'ce' || kind === 'cs') {
    await answer();
    const r = company.find(arg);
    if (!r) return say(chat, 'Không tìm thấy bản báo cáo này (có thể đã bị xoá).');
    if ((r.status === 'sent' || r.status === 'exists') && !(kind === 'ce' && company.updatable(r))) {
      return say(chat, `Báo cáo <b>${esc(company.teamLabel(r))}</b> · ${r.slot}h đã có trên công ty rồi.${company.updatable(r) ? ' Muốn sửa thì bấm Cập nhật lên công ty.' : ''}`, company.updatable(r) ? [[{ text: '🔁 Cập nhật lên công ty', data: `cu:${r.id}` }]] : undefined);
    }
    if (kind === 'ce') {
      awaiting[chat] = { id: r.id, until: Date.now() + CONFIRM_TTL_S * 1000 };
      return tg('sendMessage', { chat_id: chat, parse_mode: 'HTML', text: `Nhắn <b>số đơn</b> và <b>DSO sau VAT</b> cho <b>${esc(company.teamLabel(r))}</b> · ${r.slot}h, cách nhau bằng dấu cách.\nVí dụ: <code>12 3500000</code> hoặc <code>12 3,5tr</code>`, reply_markup: { force_reply: true, input_field_placeholder: '12 3500000' } });
    }
    const miss = (await C()).missingMetrics(r);
    if (miss.length) return say(chat, `Còn thiếu: ${miss.map((x) => x.label).join(', ')}. Nhập đủ rồi mới gửi được.`, [[{ text: '✏️ Nhập Đơn/DSO', data: `ce:${r.id}` }]]);
    return say(chat, `${await company.summary(r, '❓')}\n\n${Number(r.slot) === 9 ? `Ghi số này vào báo cáo 9h ngày ${r.date.slice(8, 10)}/${r.date.slice(5, 7)} trên hệ thống công ty? (đã có thì cập nhật, chưa có thì tạo mới)` : 'Gửi báo cáo này lên hệ thống công ty?'}`, [[{ text: '✅ Có, gửi', data: `ycs:${r.id}` }, { text: 'Huỷ', data: 'x' }]]);
  }
  if (kind === 'cu') {
    await answer();
    const r = company.find(arg);
    if (!r) return say(chat, 'Không tìm thấy bản báo cáo này (có thể đã bị xoá).');
    if (!company.updatable(r)) return say(chat, r.remote && r.remote.locked ? 'Báo cáo trên công ty đã khoá, muốn sửa hãy gửi yêu cầu chỉnh sửa trên web công ty.' : 'Báo cáo này chưa có trên hệ thống công ty, hãy dùng nút Gửi.');
    awaiting[chat] = { id: r.id, kind: 'reason', until: Date.now() + CONFIRM_TTL_S * 1000 };
    return tg('sendMessage', { chat_id: chat, parse_mode: 'HTML', text: `Nhắn <b>lý do cập nhật</b> báo cáo <b>${esc(company.teamLabel(r))}</b> · ${r.slot}h (công ty bắt buộc).\nMuốn sửa Đơn/DSO trước thì bấm nút Sửa bên dưới.`, reply_markup: { force_reply: true, input_field_placeholder: 'Cập nhật số chốt muộn' } })
      .then(() => say(chat, 'Hoặc sửa số trước:', [[{ text: '✏️ Sửa Đơn/DSO', data: `ce:${r.id}` }]]));
  }
  if (kind === 'ycu') {
    if (!msg.date || Date.now() / 1000 - msg.date > CONFIRM_TTL_S) { await answer('Đã hết hạn, hãy bấm lại nút trên tin gốc.'); return edit(chat, msg.message_id, 'Đã hết hạn xác nhận.'); }
    const reason = reasons[arg];
    if (!reason) { await answer(); return edit(chat, msg.message_id, 'Chưa có lý do, hãy bấm lại Cập nhật lên công ty.'); }
    await answer('Đang cập nhật…');
    try {
      const r = await company.updateRemote(arg, { reason, source: 'Báo cáo công ty (Telegram)' });
      delete reasons[arg];
      return edit(chat, msg.message_id, `✅ Đã cập nhật báo cáo <b>${esc(company.teamLabel(r))}</b> · ${r.slot}h ngày ${r.date.slice(8, 10)}/${r.date.slice(5, 7)} lên công ty${r.remote && r.remote.revision ? ` (lần sửa ${r.remote.revision})` : ''}.`);
    } catch (e) { return edit(chat, msg.message_id, `❌ Không cập nhật được: ${esc(e.message)}`); }
  }
  if (kind === 'ycs') {
    if (!msg.date || Date.now() / 1000 - msg.date > CONFIRM_TTL_S) { await answer('Đã hết hạn, hãy bấm lại nút trên tin gốc.'); return edit(chat, msg.message_id, 'Đã hết hạn xác nhận.'); }
    await answer('Đang gửi…');
    try {
      const r = await company.send(arg, { source: 'Báo cáo công ty (Telegram)' });
      return edit(chat, msg.message_id, `✅ Đã ${r.sentAs === 'update' ? 'cập nhật' : 'gửi'} báo cáo <b>${esc(company.teamLabel(r))}</b> · ${r.slot}h ngày ${r.date.slice(8, 10)}/${r.date.slice(5, 7)} lên công ty${r.remoteStatus === 'LATE' ? ' (công ty ghi nhận nộp muộn)' : ''}.`);
    } catch (e) { return edit(chat, msg.message_id, `❌ Không gửi được: ${esc(e.message)}`); }
  }

  // Bước 2: xác nhận (hết hạn sau 10 phút, tránh bấm nhầm tin cũ)
  if (kind === 'yo' || kind === 'yu' || kind === 'yU') {
    if (!msg.date || Date.now() / 1000 - msg.date > CONFIRM_TTL_S) { await answer('Đã hết hạn, hãy bấm lại nút trên tin gốc.'); return edit(chat, msg.message_id, 'Đã hết hạn xác nhận.'); }
    await answer('Đang làm…');
    try {
      if (kind === 'yo') return edit(chat, msg.message_id, await pauseObj(arg));
      const entry = await engine.undoLog(arg, { force: kind === 'yU' });
      return edit(chat, msg.message_id, `✅ ${esc(entry.detail)} (<b>${esc(entry.target && entry.target.name || entry.name)}</b>).`);
    } catch (e) {
      if (e.drift) return edit(chat, msg.message_id, `⚠️ ${esc(e.message)}`, [[{ text: 'Vẫn hoàn tác', data: `yU:${arg}` }, { text: 'Huỷ', data: 'x' }]]);
      return edit(chat, msg.message_id, `❌ Không làm được: ${esc(e.message)}`);
    }
  }
  return answer();
}

async function handleUpdate(u) {
  if (u.message) return onMessage(u.message);
  if (u.callback_query) return onCallback(u.callback_query);
}

// ---------- Vòng hỏi tin ----------
let offset = 0, tokenFp = '', running = false, warned409 = false;
const fp = (t) => crypto.createHash('sha256').update(String(t)).digest('hex').slice(0, 12);
const enabled = () => { const s = store.get().settings; return !!(s.telegramCommands && s.telegramToken && notify.chatIdsOf(s.telegramChatId).length); };

async function pollOnce() {
  const t = store.get().settings.telegramToken;
  if (fp(t) !== tokenFp) { tokenFp = fp(t); offset = 0; } // đổi bot: đọc lại từ đầu
  const first = offset === 0;
  const updates = await tg('getUpdates', { offset, timeout: POLL_S, allowed_updates: ['message', 'callback_query'] }, (POLL_S + 10) * 1000);
  status.lastPollAt = new Date().toISOString(); status.lastError = ''; warned409 = false;
  for (const u of updates) {
    offset = u.update_id + 1;
    // Lần đầu (vừa khởi động / đổi bot): bỏ tin nhắn cũ hơn 5 phút, không chạy lại lệnh gửi lúc tool đang tắt
    if (first && u.message && Date.now() / 1000 - (u.message.date || 0) > 300) continue;
    try { await handleUpdate(u); } catch (e) { console.error('Lỗi xử lý lệnh Telegram:', e.message); }
  }
}

async function loop() {
  while (running) {
    if (!enabled()) { status.polling = false; await sleep(10e3); continue; }
    status.polling = true;
    try { await pollOnce(); } catch (e) {
      status.lastError = e.code === 409
        ? 'Có một bản tool khác (hoặc webhook) đang nhận tin của bot này. Chỉ để một bản tool bật "Nhận lệnh từ Telegram".'
        : e.message;
      if (e.code === 409 && !warned409) {
        warned409 = true;
        store.log({ kind: 'system', source: 'Telegram', name: '-', detail: status.lastError, ok: false, mode: modeOf(store.get().settings) });
      }
      await sleep(e.code === 409 ? 60e3 : e.code === 401 ? 5 * 60e3 : 10e3);
    }
  }
}

function start() { if (!running) { running = true; loop(); } }
const reset = () => { for (const k of Object.keys(awaiting)) delete awaiting[k]; for (const k of Object.keys(reasons)) delete reasons[k]; offset = 0; tokenFp = ''; warned409 = false; Object.assign(status, { polling: false, lastPollAt: null, lastError: '' }); };

module.exports = { start, handleUpdate, pollOnce, status, reset, CONFIRM_TTL_S };
