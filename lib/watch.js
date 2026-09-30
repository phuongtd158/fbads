// Canh cho tool chạy 24/7: token Facebook sắp hết hạn, vòng tự động bị treo hoặc lỗi liên tục.
// Báo qua Telegram (và nhật ký), mỗi sự việc chỉ báo một lần; /api/health đọc trạng thái ở đây.
const crypto = require('crypto');
const store = require('./store');
const fb = require('./fb');
const notify = require('./notify');

const TOKEN_WARN_DAYS = 7;   // còn ≤ 7 ngày thì báo, mỗi ngày một lần tới khi đổi token
const TOKEN_RETRY_MS = 3600e3; // kiểm tra token lỗi (mạng, Facebook) → thử lại sau 1 giờ, không gọi mỗi lượt
const STALL_MS = 5 * 60e3;   // một lượt chạy quá 5 phút, hoặc 5 phút không xong lượt nào → coi là đứng
const ERRORS_ALERT = 3;      // lỗi 3 lượt liên tiếp thì báo

const modeNow = () => { const s = store.get().settings; return s.mock ? 'mock' : s.dryRun ? 'dry' : 'live'; };
const fingerprint = (token) => crypto.createHash('sha256').update(String(token)).digest('hex').slice(0, 12); // nhận ra token đã đổi mà không lưu token

async function alert(name, detail, text, ok = false) {
  store.log({ kind: 'system', source: 'Hệ thống', name, detail, ok, mode: modeNow() });
  await notify.telegram(text);
}

// Riêng từng tài khoản: lần kiểm tra token lỗi gần nhất, lỗi liên tiếp của vòng tự động
const U = store.perUser(() => ({ tokenFailedAt: 0, errorsInRow: 0, lastError: '', errorAlerted: false, stallAlerted: false, lastOkAt: 0 }));

// ---------- Token Facebook ----------
async function tickToken(now) {
  const s = store.get().settings, st = store.get().state;
  if (s.mock || !s.accessToken) return;
  const fp = fingerprint(s.accessToken);
  const ta = (st.tokenAlert && st.tokenAlert.fp === fp) ? st.tokenAlert : (st.tokenAlert = { fp }); // token mới → báo lại từ đầu
  const bad = async (why) => {
    if (ta.bad) return;
    ta.bad = true; store.save();
    await alert('Token Facebook', `Token không còn dùng được: ${why}`, `❌ <b>Token Facebook không còn dùng được</b>\n${why}\nLịch và rule sẽ không chạy được. Vào Cài đặt → Kết nối để dán token mới.`);
  };
  // Facebook vừa báo token hỏng (lỗi 190) khi đang chạy → báo ngay, không đợi lượt kiểm tra hằng ngày
  const err = fb.tokenError();
  if (err) return bad(err);
  if (ta.date === now.date || Date.now() - U().tokenFailedAt < TOKEN_RETRY_MS) return;
  let t;
  try { t = await fb.inspectToken(s.accessToken); } catch (e) {
    if (e.fb && e.fb.code === 190) { ta.date = now.date; return bad(e.message); }
    U().tokenFailedAt = Date.now(); // mạng/Facebook lỗi: thử lại sau 1 giờ
    return;
  }
  ta.date = now.date; store.save();
  if (!t.valid) return bad('Facebook báo token không hợp lệ (có thể đã bị thu hồi hoặc đổi mật khẩu).');
  if (t.daysLeft == null || t.daysLeft > TOKEN_WARN_DAYS) return;
  const when = t.daysLeft >= 1 ? `Còn ${t.daysLeft} ngày` : 'Hết hạn trong hôm nay';
  const on = new Date(t.expiresAt).toLocaleString('vi-VN', { timeZone: s.timezone || 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short' });
  await alert('Token Facebook', `Token sắp hết hạn: ${when.toLowerCase()} (${on}).`, `⏳ <b>Token Facebook sắp hết hạn</b>\n${when} (${on}). Vào Cài đặt → Kết nối để tạo token mới, nếu không lịch và rule sẽ ngừng.`);
}

// ---------- Vòng tự động ----------
// Vòng tự động chạy lần lượt từng tài khoản: engine gọi tickStarted/tickDone quanh lượt của mỗi tài khoản (trong ngữ cảnh
// tài khoản đó). `run` là của cả vòng (cho /api/health và phát hiện kẹt), lỗi liên tiếp tính riêng từng tài khoản.
const startedAt = Date.now();
const run = { busySince: 0, busyUser: null, lastDoneAt: 0 };

function tickStarted() { run.busySince = Date.now(); run.busyUser = store.userId(); }
async function tickDone(err) {
  run.busySince = 0; run.busyUser = null;
  run.lastDoneAt = Date.now();
  const u = U();
  const wasDown = u.stallAlerted || u.errorAlerted;
  if (err) {
    u.errorsInRow++;
    u.lastError = err.message || String(err);
    if (u.errorsInRow >= ERRORS_ALERT && !u.errorAlerted) {
      u.errorAlerted = true;
      await notify.telegram(`⚠️ <b>Vòng tự động lỗi ${u.errorsInRow} lượt liên tiếp</b>\n${u.lastError}\nLịch và rule có thể không chạy. Xem Nhật ký để biết chi tiết.`);
    }
    return;
  }
  u.errorsInRow = 0; u.lastError = ''; u.lastOkAt = Date.now();
  u.stallAlerted = false; u.errorAlerted = false;
  if (wasDown) await alert('Vòng tự động', 'Vòng tự động đã chạy lại bình thường.', '✅ <b>Vòng tự động đã chạy lại bình thường</b>', true);
}

// Lượt đang chạy quá lâu: gọi mỗi phút từ bộ hẹn giờ riêng (vòng tự động đang kẹt thì không tự báo được).
// Báo cho tài khoản đang bị kẹt (lịch, rule của các tài khoản sau nó cũng đang phải chờ).
async function checkStall() {
  if (!run.busySince || run.busyUser == null || Date.now() - run.busySince < STALL_MS) return;
  return store.run(run.busyUser, async () => {
    if (U().stallAlerted) return;
    U().stallAlerted = true;
    const min = Math.round((Date.now() - run.busySince) / 60e3);
    await alert('Vòng tự động', `Một lượt chạy đã kéo dài ${min} phút, lịch và rule đang phải chờ.`, `⚠️ <b>Vòng tự động bị kẹt</b>\nMột lượt đã chạy ${min} phút chưa xong, lịch và rule đang phải chờ.`);
  });
}

// Cho /api/health: ok = vòng tự động còn chạy (xong một lượt trong 5 phút gần nhất, hoặc vừa khởi động)
function health() {
  const now = Date.now();
  const stuck = run.busySince && now - run.busySince >= STALL_MS;
  const fresh = run.lastDoneAt ? now - run.lastDoneAt < STALL_MS : now - startedAt < STALL_MS;
  return { ok: !stuck && fresh, lastTickAt: run.lastDoneAt ? new Date(run.lastDoneAt).toISOString() : null };
}

function start() { setInterval(() => { checkStall().catch((e) => console.error('Lỗi canh vòng tự động:', e.message)); }, 60e3).unref(); }

// Đặt lại trạng thái (dùng cho kiểm thử)
function reset() {
  Object.assign(run, { busySince: 0, busyUser: null, lastDoneAt: 0 });
  U.reset();
}

module.exports = { tickToken, tickStarted, tickDone, checkStall, health, start, reset, TOKEN_WARN_DAYS, STALL_MS };
