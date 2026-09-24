const fs = require('fs');
const path = require('path');
const remote = require('./remote');

const defaults = () => ({
  settings: {
    mock: true,            // true = dữ liệu giả để dùng thử, không chạm vào Facebook
    dryRun: true,          // true = tự động hoá chỉ ghi log, không thực thi thật
    accessToken: '',
    adAccountId: '',
    fbAppId: '',            // ứng dụng Meta dùng cho nút "Đăng nhập bằng Facebook"
    fbAppSecret: '',
    fbConfigId: '',         // Configuration ID (chỉ khi dùng Facebook Login for Business), trống = dùng scope
    apiVersion: 'v21.0',
    timezone: 'Asia/Ho_Chi_Minh',
    resultAction: 'purchase', // loại kết quả để tính CPA/ROAS: purchase, lead, ...
    ruleIntervalMin: 15,
    telegramToken: '',
    telegramChatId: '',
    reportTime: '08:00',
    passwordHash: '',       // mật khẩu đăng nhập (băm scrypt), trống = không cần đăng nhập
    skipLearning: true,     // rule đổi ngân sách bỏ qua camp/nhóm đang trong giai đoạn học
    dailyChangeCapPct: 30,  // tổng thay đổi ngân sách tối đa mỗi ngày cho 1 camp do rule (%)
    killSwitchEnabled: false, // dừng khẩn: tự tắt mọi camp khi tổng chi tiêu hôm nay vượt mức
    dailySpendLimit: 0,
  },
  schedules: [],
  rules: [],
  logs: [],
  state: { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '' },
});

// Gộp dữ liệu đã lưu với mặc định: bản cập nhật thêm khoá mới mà không làm mất dữ liệu cũ
const normalize = (saved) => {
  const d = defaults();
  return { ...d, ...saved, settings: { ...d.settings, ...saved.settings }, state: { ...d.state, ...saved.state } };
};
const stamp = () => new Date().toISOString().replace(/[:.]/g, '-');
const KEEP_BACKUPS = 7;
const KEY = 'fbads:data', BACKUP = 'fbads:data:backup';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Hai chế độ lưu:
//  - file (mặc định): data.json trong DATA_DIR; mỗi ngày giữ 1 bản sao, file hỏng thì cách ly + khôi phục từ bản sao.
//  - remote: đặt UPSTASH_REDIS_REST_URL/TOKEN + DATA_KEY → lưu (đã mã hoá) ở Upstash, dùng cho nơi không có ổ đĩa bền.
// `create` nhận tuỳ chọn để kiểm thử; module xuất sẵn một bản dùng biến môi trường thật.
function create(opts = {}) {
  const env = opts.env || process.env;
  const dir = opts.dir || env.DATA_DIR || path.join(__dirname, '..');
  const FILE = path.join(dir, 'data.json');
  const debounceMs = opts.debounceMs ?? 1500;
  const retryBaseMs = opts.retryBaseMs ?? 2000;

  let cfg = null, cfgError = null;
  try { cfg = remote.config(env); } catch (e) { cfgError = e; }
  const client = cfg && (opts.client || remote.upstash(cfg));

  let data = defaults();
  const notices = []; // cảnh báo lúc khởi động → ghi vào nhật ký để người dùng thấy
  let lastSavedAt = null, lastError = '';

  const uid = () => Math.random().toString(36).slice(2, 10);
  const modeOf = () => (data.settings.mock ? 'mock' : data.settings.dryRun ? 'dry' : 'live');

  // ----- Chế độ file -----
  const bakFiles = () => {
    try { return fs.readdirSync(dir).filter((f) => /^data\.json\.bak-\d{4}-\d{2}-\d{2}$/.test(f)).sort().reverse(); } catch { return []; }
  };
  const parseObject = (text) => {
    const v = JSON.parse(text);
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('không phải đối tượng JSON');
    return v;
  };

  // → đối tượng dữ liệu, hoặc null nếu chưa có file. File hỏng: đổi tên giữ lại (không xoá, không ghi đè) rồi khôi phục từ bản sao.
  function readFile() {
    let text;
    try { text = fs.readFileSync(FILE, 'utf8'); } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
    try { return parseObject(text); } catch (e) {
      const q = `${FILE}.corrupt-${stamp()}`;
      fs.renameSync(FILE, q);
      let restored = null, from = '';
      for (const f of bakFiles()) { try { restored = parseObject(fs.readFileSync(path.join(dir, f), 'utf8')); from = f; break; } catch { /* thử bản cũ hơn */ } }
      const msg = restored
        ? `data.json bị lỗi (${e.message}). File hỏng được giữ lại ở ${path.basename(q)}; đã khôi phục từ bản sao ${from}.`
        : `data.json bị lỗi (${e.message}). File hỏng được giữ lại ở ${path.basename(q)}; chưa có bản sao nên tool bắt đầu với dữ liệu trống.`;
      notices.push(msg);
      console.error(`\n  ⚠ ${msg}\n`);
      return restored;
    }
  }

  let bakDay = '';
  // Lần lưu đầu tiên trong ngày: chép file hiện có (= trạng thái cuối ngày hôm trước) làm bản sao, giữ 7 bản gần nhất
  function dailyBackup() {
    const day = new Date().toISOString().slice(0, 10);
    if (bakDay === day) return;
    bakDay = day;
    try {
      if (!fs.existsSync(FILE)) return;
      const dst = `${FILE}.bak-${day}`;
      if (!fs.existsSync(dst)) fs.copyFileSync(FILE, dst);
      for (const f of bakFiles().slice(KEEP_BACKUPS)) fs.rmSync(path.join(dir, f), { force: true });
    } catch (e) { console.error('Không tạo được bản sao dữ liệu:', e.message); }
  }

  function saveFile() {
    fs.mkdirSync(dir, { recursive: true });
    dailyBackup();
    const tmp = FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
    fs.renameSync(tmp, FILE);
    lastSavedAt = new Date().toISOString();
  }

  // ----- Chế độ remote: ghi gộp (debounce), thử lại khi lỗi, ghi nốt trước khi tắt -----
  let timer = null, flushing = null, dirty = false, fails = 0, lastJson = null, ready = null, loaded = false;

  function schedule(ms) {
    if (timer || flushing) return; // đang chờ ghi, hoặc vòng ghi đang chạy sẽ tự gom thay đổi mới
    timer = setTimeout(() => { timer = null; flush(); }, ms);
    if (timer.unref) timer.unref();
  }

  async function run() {
    while (dirty) {
      dirty = false;
      const json = JSON.stringify(data);
      if (json === lastJson) continue; // không có gì khác → khỏi tốn lệnh
      try {
        await client.set(KEY, remote.encode(json, cfg.secret));
        lastJson = json; lastSavedAt = new Date().toISOString(); lastError = ''; fails = 0;
      } catch (e) {
        dirty = true; lastError = e.message; fails++;
        console.error(`  ⚠ Chưa lưu được dữ liệu lên Upstash (lần ${fails}): ${e.message}`);
        return;
      }
    }
  }

  function flush() {
    if (!client) return Promise.resolve();
    if (timer) { clearTimeout(timer); timer = null; }
    if (flushing) return flushing;
    if (!dirty) return Promise.resolve();
    // .finally chạy sau khi `flushing` đã được gán, kể cả khi run() xong ngay lập tức
    flushing = run().finally(() => { flushing = null; if (dirty) schedule(Math.min(60000, retryBaseMs * 2 ** Math.min(fails, 5))); });
    return flushing;
  }

  async function load() {
    let raw, err;
    for (let i = 0; i < 5; i++) {
      try { raw = await client.get(KEY); err = null; break; } catch (e) { err = e; if (i < 4) await sleep(opts.startRetryMs ?? 1000 * 2 ** i); }
    }
    if (err) throw new Error(`Không đọc được dữ liệu từ Upstash: ${err.message}. Tool dừng lại để không ghi đè dữ liệu cũ bằng dữ liệu trống; hãy kiểm tra URL/token rồi chạy lại (Render sẽ tự thử khởi động lại).`);
    if (raw == null) { // lần đầu: nếu chạy ở máy có sẵn data.json thì đưa lên luôn
      const local = readFile();
      data = normalize(local || {});
      const json = JSON.stringify(data);
      await client.set(KEY, remote.encode(json, cfg.secret));
      lastJson = json; lastSavedAt = new Date().toISOString();
      console.log(local ? '  Lần đầu dùng Upstash: đã đưa dữ liệu từ data.json trên máy này lên.' : '  Lần đầu dùng Upstash: bắt đầu với dữ liệu trống.');
    } else {
      const json = remote.decode(raw, cfg.secret); // sai khoá / hỏng → ném lỗi, không ghi gì
      data = normalize(parseObject(json));
      lastJson = json;
      try { await client.set(BACKUP, raw); } catch { /* bản sao đầu phiên chỉ là phụ */ }
    }
    loaded = true;
    flushNotices();
  }

  // Gọi 1 lần lúc khởi động. Chế độ file đã nạp xong ngay khi tạo nên không làm gì.
  function init() {
    if (cfgError) return Promise.reject(cfgError);
    if (!client) return Promise.resolve();
    return (ready = ready || load());
  }

  function save() {
    if (!client) return saveFile();
    if (!loaded) throw new Error('store.init() chưa chạy xong: không được lưu khi chưa nạp dữ liệu từ Upstash (sẽ ghi đè dữ liệu thật bằng dữ liệu trống).');
    dirty = true;
    schedule(debounceMs);
  }

  // Cập nhật một dòng nhật ký (vd đánh dấu đã hoàn tác)
  function updateLog(id, patch) {
    const l = data.logs.find((x) => x.id === id);
    if (!l) return null;
    Object.assign(l, patch);
    save();
    return l;
  }

  function log(entry) {
    const e = { id: uid(), ts: new Date().toISOString(), ...entry };
    data.logs.unshift(e);
    if (data.logs.length > 1000) data.logs.length = 1000;
    save();
    return e;
  }

  function flushNotices() {
    for (const n of notices.splice(0)) log({ kind: 'system', source: 'Hệ thống', name: '-', detail: n, ok: false, mode: modeOf() });
  }

  const status = () => ({ mode: client ? 'remote' : 'file', provider: client ? 'Upstash' : null, lastSavedAt, lastError, pending: dirty || !!flushing });

  if (!client && !cfgError) { // chế độ file: nạp ngay khi khởi tạo
    const saved = readFile();
    if (saved) data = normalize(saved);
    flushNotices();
  }

  return { get: () => data, save, log, updateLog, uid, init, flush, status };
}

module.exports = create();
module.exports.create = create;
