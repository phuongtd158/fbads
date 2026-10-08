const store = require('./store');

// Danh sách Chat ID người nhận: cách nhau bằng dấu phẩy / chấm phẩy / khoảng trắng, bỏ trùng.
// Giống parseChatIds trong shared/validate.mjs (tests/notify.test.mjs kiểm tra hai bản luôn khớp nhau).
function chatIdsOf(v) {
  const seen = new Map();
  for (const raw of String(v == null ? '' : v).split(/[\s,;]+/)) {
    const id = raw.trim();
    if (id && !seen.has(id.toLowerCase())) seen.set(id.toLowerCase(), id);
  }
  return [...seen.values()];
}

// Telegram trả mã lỗi + mô tả tiếng Anh → đổi thành việc cần làm
function friendlyTg(code, desc) {
  const d = String(desc || '').toLowerCase();
  if (code === 401) return 'Bot Token sai hoặc đã bị thu hồi.';
  if (code === 400 && /chat not found/.test(d)) return 'Không tìm thấy chat này: ID sai, hoặc người đó chưa từng nhắn cho bot.';
  if (code === 400 && /too long/.test(d)) return 'Tin nhắn quá dài.';
  if (code === 403 && /blocked/.test(d)) return 'Người này đã chặn bot.';
  if (code === 403 && /initiate conversation/.test(d)) return 'Người này chưa từng nhắn cho bot nên bot chưa gửi tin được.';
  if (code === 403 && /not a member|kicked|write|rights|deactivated/.test(d)) return 'Bot chưa ở trong nhóm/kênh này hoặc không có quyền gửi tin.';
  if (code === 429) return 'Telegram đang giới hạn tốc độ gửi, thử lại sau ít phút.';
  return desc ? `Telegram báo: ${desc}` : `Lỗi Telegram ${code || ''}`.trim();
}

const net = { timeoutMs: 10e3 }; // đổi được trong test

// Tin gửi ở chế độ HTML: chữ lấy từ tên camp, tên rule, nội dung bạn gõ, lỗi Facebook… phải đi qua esc(),
// nếu không một ký tự "<" (vd "CPA <50k") làm Telegram từ chối cả tin.
const esc = (t) => String(t == null ? '' : t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
// Bản chữ thường của tin HTML (bỏ thẻ, trả lại ký tự gốc) để gửi lại khi Telegram không đọc được HTML
const plain = (html) => String(html).replace(/<\/?[a-z][^>]*>/gi, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');

async function post(token, body) {
  return fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(net.timeoutMs), // Telegram treo thì không giữ chân vòng tự động
  });
}

// Gửi cho 1 Chat ID → { id, ok } hoặc { id, ok: false, error }. Không bao giờ ném lỗi và không đưa token vào kết quả.
async function sendOne(token, id, text, markup) {
  try {
    const extra = markup ? { reply_markup: markup } : {};
    let r = await post(token, { chat_id: id, text, parse_mode: 'HTML', ...extra });
    if (r.ok) return { id, ok: true };
    let j = {};
    try { j = await r.json(); } catch { /* không phải JSON */ }
    // Lưới an toàn: còn chỗ nào quên esc() thì vẫn gửi được tin (dạng chữ thường) thay vì mất hẳn, và ghi lại tin nào lỗi để sửa
    if (r.status === 400 && /can't parse entities/i.test(j.description || '')) {
      console.warn(`Telegram không đọc được HTML (${j.description}), gửi lại dạng chữ thường. Đầu tin: ${JSON.stringify(String(text).slice(0, 120))}`);
      r = await post(token, { chat_id: id, text: plain(text), ...extra });
      if (r.ok) return { id, ok: true };
      j = {};
      try { j = await r.json(); } catch { /* không phải JSON */ }
    }
    // che token phòng khi mô tả lỗi nhắc lại nó: kết quả này được đưa ra giao diện và nhật ký
    return { id, ok: false, error: friendlyTg(j.error_code || r.status, j.description).split(token).join('***') };
  } catch (e) {
    if (e && (e.name === 'TimeoutError' || e.name === 'AbortError')) return { id, ok: false, error: `Telegram không trả lời sau ${Math.round(net.timeoutMs / 1000)} giây.` };
    return { id, ok: false, error: 'Không kết nối được tới Telegram. Kiểm tra mạng internet.' };
  }
}

// Gửi cho tất cả người nhận đã cài. Một người lỗi không làm mất tin của những người còn lại.
// opts.buttons: [[{ text, data }]] nút bấm dưới tin (chỉ gắn khi bật "Nhận lệnh từ Telegram", xem lib/tgbot.js)
// → { configured, results: [{ id, ok, error? }] }
async function send(text, opts = {}) {
  const { telegramToken, telegramChatId, telegramCommands } = store.get().settings;
  const ids = chatIdsOf(telegramChatId);
  if (!telegramToken || !ids.length) return { configured: false, results: [] };
  const markup = telegramCommands && opts.buttons && opts.buttons.length
    ? { inline_keyboard: opts.buttons.map((row) => row.map((b) => ({ text: b.text, callback_data: b.data }))) } : undefined;
  const results = await Promise.all(ids.map((id) => sendOne(telegramToken, id, text, markup)));
  for (const r of results) if (!r.ok) console.error(`Telegram lỗi (${r.id}): ${r.error}`);
  return { configured: true, results };
}

// Giữ tên cũ cho nơi chỉ cần biết "có gửi được cho ít nhất một người không"
async function telegram(text, opts) {
  return (await send(text, opts)).results.some((r) => r.ok);
}

// Đổi kết quả gửi thành phản hồi cho giao diện: gửi được cho ít nhất một người thì 200 (kèm chi tiết từng người), không ai nhận được thì 400.
function reply(r) {
  if (!r.configured) return { status: 400, body: { error: 'Cần nhập Bot Token và Chat ID trước.' } };
  const sent = r.results.filter((x) => x.ok).length;
  if (sent) return { status: 200, body: { ok: true, sent, total: r.results.length, results: r.results } };
  const why = r.results[0].error;
  return { status: 400, body: { error: r.results.length > 1 ? `Không gửi được cho ai cả. Lỗi đầu tiên: ${why}` : `Gửi thất bại: ${why}`, results: r.results } };
}

module.exports = { _net: net, telegram, send, reply, chatIdsOf, friendlyTg, esc, plain };
