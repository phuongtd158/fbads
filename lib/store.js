const fs = require('fs');
const path = require('path');
const remote = require('./remote');
const ctx = require('./ctx');
const secrets = require('./secrets');

const defaults = () => ({
  settings: {
    mock: true,            // true = dữ liệu giả để dùng thử, không chạm vào Facebook
    dryRun: true,          // true = tự động hoá chỉ ghi log, không thực thi thật
    accessToken: '',
    adAccountId: '',       // tài khoản đầu tiên trong adAccountIds (giữ cho phần cũ)
    adAccountIds: [],      // các tài khoản quảng cáo đang quản lý
    fbAppId: '',            // ứng dụng Meta dùng cho nút "Đăng nhập bằng Facebook"
    fbAppSecret: '',
    fbConfigId: '',         // Configuration ID (chỉ khi dùng Facebook Login for Business), trống = dùng scope
    apiVersion: 'v21.0',
    timezone: 'Asia/Ho_Chi_Minh',
    resultAction: 'purchase', // loại kết quả để tính CPA/ROAS: purchase, lead, ...
    ruleIntervalMin: 15,
    telegramToken: '',
    telegramChatId: '',
    telegramCommands: false, // nhận lệnh và nút bấm từ Telegram (lib/tgbot.js); chỉ bật ở MỘT bản tool cho mỗi bot
    reportTime: '08:00',
    weeklyReport: true,     // báo cáo tuần qua Telegram, sáng thứ Hai cùng giờ với báo cáo hằng ngày
    passwordHash: '',       // mật khẩu đăng nhập (băm scrypt), trống = không cần đăng nhập
    skipLearning: true,     // rule đổi ngân sách bỏ qua camp/nhóm đang trong giai đoạn học
    dailyChangeCapPct: 30,  // tổng thay đổi ngân sách tối đa mỗi ngày cho 1 camp do rule (%)
    killSwitchEnabled: false, // dừng khẩn: tự tắt mọi camp khi tổng chi tiêu hôm nay vượt mức
    dailySpendLimit: 0,
    killScope: 'total',     // dừng khẩn tính trên 'total' (tổng mọi tài khoản) hay 'account' (từng tài khoản, mức riêng ở accountTargets)
    accountTargets: {},     // { [mã tài khoản]: { cpa, roas, dailySpendLimit } }: mục tiêu hoà vốn + mức dừng khẩn riêng theo tài khoản
    alertAccount: true,     // cảnh báo: tài khoản quảng cáo bị vô hiệu hoá / nợ thanh toán… (lib/alerts.js)
    alertDisapproved: true, // cảnh báo: quảng cáo bị từ chối
    alertSpike: true,       // cảnh báo: chi tiêu hôm nay tăng vọt so với cùng giờ hôm qua
    spikePct: 50,           // tăng hơn 50% thì báo
    spikeMinSpend: 100000,  // chỉ báo khi hôm nay đã chi từ mức này (tránh báo camp chi vài chục nghìn)
  },
  schedules: [],
  rules: [],
  logs: [],
  state: { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', resume: {} },
});

// Gộp dữ liệu đã lưu với mặc định: bản cập nhật thêm khoá mới mà không làm mất dữ liệu cũ
const normalize = (saved) => {
  const d = defaults();
  const out = { ...d, ...saved, settings: { ...d.settings, ...saved.settings }, state: { ...d.state, ...saved.state } };
  // dữ liệu cũ chỉ có 1 tài khoản quảng cáo → chuyển sang danh sách
  if (!(out.settings.adAccountIds || []).length && out.settings.adAccountId) out.settings.adAccountIds = [out.settings.adAccountId];
  return out;
};
const stamp = () => new Date().toISOString().replace(/[:.]/g, '-');
const KEEP_BACKUPS = 7;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Danh sách tài khoản đăng nhập + phiên (lib/auth.js). Dữ liệu quảng cáo của từng tài khoản nằm ở bộ riêng (xem fileOf/keyOf).
const accountsDefaults = () => ({ users: [], nextId: ctx.ADMIN + 1, sessions: {} });
const normalizeAccounts = (saved) => ({ ...accountsDefaults(), ...saved });

// Nơi lưu từng bộ dữ liệu. Tài khoản admin (1) giữ đúng tên cũ để bản cũ của tool vẫn đọc được nếu cần quay lại.
const fileOf = (id) => (id === ctx.ADMIN ? 'data.json' : `data-u${id}.json`);
const keyOf = (id) => (id === ctx.ADMIN ? 'fbads:data' : `fbads:data:u:${id}`);

// Hai chế độ lưu:
//  - file (mặc định): các file JSON trong DATA_DIR; mỗi ngày giữ 1 bản sao, file hỏng thì cách ly + khôi phục từ bản sao.
//  - remote: đặt UPSTASH_REDIS_REST_URL/TOKEN + DATA_KEY → lưu (đã mã hoá) ở Upstash, dùng cho nơi không có ổ đĩa bền.
// Mỗi tài khoản một bộ dữ liệu; store.get() trả bộ của tài khoản đang làm việc (lib/ctx.js).
// `create` nhận tuỳ chọn để kiểm thử; module xuất sẵn một bản dùng biến môi trường thật.
function create(opts = {}) {
  const env = opts.env || process.env;
  const dir = opts.dir || env.DATA_DIR || path.join(__dirname, '..');
  const debounceMs = opts.debounceMs ?? 1500;
  const retryBaseMs = opts.retryBaseMs ?? 2000;
  const secretKey = env.SECRET_KEY || ''; // mã hoá token khi lưu file (lib/secrets.js)

  let cfg = null, cfgError = null;
  try { cfg = remote.config(env); } catch (e) { cfgError = e; }
  const client = cfg && (opts.client || remote.upstash(cfg));

  const notices = []; // cảnh báo lúc khởi động → ghi vào nhật ký của admin để người dùng thấy
  const parseObject = (text) => {
    const v = JSON.parse(text);
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('không phải đối tượng JSON');
    return v;
  };

  // Một bộ dữ liệu = 1 file hoặc 1 khoá Upstash, ghi gộp (debounce), thử lại khi lỗi, bản sao hằng ngày.
  // `upload`: lần đầu dùng Upstash thì đưa file cục bộ lên (và ghi ngay cả khi trống, như bản cũ).
  function bucket({ name, key, init, norm, upload = true, seal = false }) {
    const FILE = path.join(dir, name);
    const b = { data: init(), lastSavedAt: null, lastError: '', loaded: false };

    // ----- Chế độ file -----
    const bakRe = new RegExp(`^${esc(name)}\\.bak-\\d{4}-\\d{2}-\\d{2}$`);
    const bakFiles = () => {
      try { return fs.readdirSync(dir).filter((f) => bakRe.test(f)).sort().reverse(); } catch { return []; }
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
          ? `${name} bị lỗi (${e.message}). File hỏng được giữ lại ở ${path.basename(q)}; đã khôi phục từ bản sao ${from}.`
          : `${name} bị lỗi (${e.message}). File hỏng được giữ lại ở ${path.basename(q)}; chưa có bản sao nên tool bắt đầu với dữ liệu trống.`;
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
      fs.writeFileSync(tmp, JSON.stringify(seal ? secrets.sealData(b.data, secretKey) : b.data, null, 2));
      fs.renameSync(tmp, FILE);
      b.lastSavedAt = new Date().toISOString();
    }

    // ----- Chế độ remote: ghi gộp (debounce), thử lại khi lỗi, ghi nốt trước khi tắt -----
    let timer = null, flushing = null, dirty = false, fails = 0, lastJson = null;

    function schedule(ms) {
      if (timer || flushing) return; // đang chờ ghi, hoặc vòng ghi đang chạy sẽ tự gom thay đổi mới
      timer = setTimeout(() => { timer = null; flush(); }, ms);
      if (timer.unref) timer.unref();
    }

    async function run() {
      while (dirty) {
        dirty = false;
        const json = JSON.stringify(b.data);
        if (json === lastJson) continue; // không có gì khác → khỏi tốn lệnh
        try {
          await client.set(key, remote.encode(json, cfg.secret));
          lastJson = json; b.lastSavedAt = new Date().toISOString(); b.lastError = ''; fails = 0;
        } catch (e) {
          dirty = true; b.lastError = e.message; fails++;
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
        try { raw = await client.get(key); err = null; break; } catch (e) { err = e; if (i < 4) await sleep(opts.startRetryMs ?? 1000 * 2 ** i); }
      }
      if (err) throw new Error(`Không đọc được dữ liệu từ Upstash: ${err.message}. Tool dừng lại để không ghi đè dữ liệu cũ bằng dữ liệu trống; hãy kiểm tra URL/token rồi chạy lại (Render sẽ tự thử khởi động lại).`);
      if (raw == null) { // lần đầu: nếu chạy ở máy có sẵn file thì đưa lên luôn
        const local = readFile();
        b.data = norm(local ? (seal ? secrets.openData(local, secretKey) : local) : {});
        if (upload || local) {
          const json = JSON.stringify(b.data);
          await client.set(key, remote.encode(json, cfg.secret));
          lastJson = json; b.lastSavedAt = new Date().toISOString();
          if (key === keyOf(ctx.ADMIN)) console.log(local ? '  Lần đầu dùng Upstash: đã đưa dữ liệu từ data.json trên máy này lên.' : '  Lần đầu dùng Upstash: bắt đầu với dữ liệu trống.');
        }
      } else {
        const json = remote.decode(raw, cfg.secret); // sai khoá / hỏng → ném lỗi, không ghi gì
        b.data = norm(parseObject(json));
        lastJson = json;
        try { await client.set(`${key}:backup`, raw); } catch { /* bản sao đầu phiên chỉ là phụ */ }
      }
      b.loaded = true;
    }

    b.load = load;
    b.flush = flush;
    b.pending = () => dirty || !!flushing;
    b.save = () => {
      if (!b.loaded) throw new Error('store.init() chưa chạy xong: không được lưu khi chưa nạp dữ liệu từ Upstash (sẽ ghi đè dữ liệu thật bằng dữ liệu trống).');
      if (!client) return saveFile();
      dirty = true;
      schedule(debounceMs);
    };
    // Bộ mới tạo lúc đang chạy (tài khoản mới): không có gì để nạp
    b.fresh = () => { b.data = norm({}); b.loaded = true; };
    b.readLocal = () => { const saved = readFile(); if (saved) b.data = norm(seal ? secrets.openData(saved, secretKey) : saved); b.loaded = true; };
    // Xoá tài khoản: file đổi tên giữ lại (cứu được bằng tay), khoá Upstash để nguyên
    b.retire = () => {
      if (timer) { clearTimeout(timer); timer = null; }
      dirty = false;
      if (!client && fs.existsSync(FILE)) fs.renameSync(FILE, `${FILE}.deleted-${stamp()}`);
    };
    return b;
  }

  const accounts = bucket({ name: 'accounts.json', key: 'fbads:accounts', init: accountsDefaults, norm: normalizeAccounts, upload: false });
  const users = new Map(); // id tài khoản → bộ dữ liệu
  const dataBucket = (id) => bucket({ name: fileOf(id), key: keyOf(id), init: defaults, norm: normalize, seal: true });
  users.set(ctx.ADMIN, dataBucket(ctx.ADMIN));

  const uid = () => Math.random().toString(36).slice(2, 10);
  const ids = () => [...users.keys()];

  // Tài khoản đang làm việc. Ngoài ngữ cảnh (lúc khởi động, kiểm thử) chỉ chấp nhận khi tool có đúng 1 tài khoản:
  // khi đã có nhiều tài khoản thì báo lỗi ngay chứ không đoán, để không đọc/ghi nhầm dữ liệu của người khác.
  function userId() {
    const id = ctx.current();
    if (id != null) return id;
    if (users.size === 1) return ctx.ADMIN;
    throw new Error('Chưa xác định tài khoản cho thao tác này');
  }
  function cur() {
    const id = userId(), b = users.get(id);
    if (!b) throw new Error(`Không tìm thấy dữ liệu của tài khoản ${id}`);
    return b;
  }
  const get = () => cur().data;
  const modeOf = (d) => (d.settings.mock ? 'mock' : d.settings.dryRun ? 'dry' : 'live');

  function save() { cur().save(); }

  // Cập nhật một dòng nhật ký (vd đánh dấu đã hoàn tác)
  function updateLog(id, patch) {
    const l = get().logs.find((x) => x.id === id);
    if (!l) return null;
    Object.assign(l, patch);
    save();
    return l;
  }

  function log(entry) {
    const d = get();
    const e = { id: uid(), ts: new Date().toISOString(), ...entry };
    d.logs.unshift(e);
    if (d.logs.length > 1000) d.logs.length = 1000;
    save();
    return e;
  }

  function flushNotices() {
    if (!notices.length) return;
    ctx.run(ctx.ADMIN, () => { for (const n of notices.splice(0)) log({ kind: 'system', source: 'Hệ thống', name: '-', detail: n, ok: false, mode: modeOf(get()) }); });
  }

  // Bộ dữ liệu của các tài khoản (trừ admin, có sẵn) theo danh sách trong accounts
  const listedIds = () => accounts.data.users.map((u) => u.id).filter((id) => id !== ctx.ADMIN);

  let ready = null;
  async function load() {
    await accounts.load();
    await users.get(ctx.ADMIN).load();
    for (const id of listedIds()) { const b = dataBucket(id); await b.load(); users.set(id, b); }
    flushNotices();
  }

  // Gọi 1 lần lúc khởi động. Chế độ file đã nạp xong ngay khi tạo nên không làm gì.
  function init() {
    if (cfgError) return Promise.reject(cfgError);
    if (!client) return Promise.resolve();
    return (ready = ready || load());
  }

  const all = () => [accounts, ...users.values()];
  const flush = () => Promise.all(all().map((b) => b.flush()));

  const status = () => {
    const bs = all(), saved = bs.map((b) => b.lastSavedAt).filter(Boolean).sort();
    return { mode: client ? 'remote' : 'file', provider: client ? 'Upstash' : null, lastSavedAt: saved[saved.length - 1] || null, lastError: (bs.find((b) => b.lastError) || {}).lastError || '', pending: bs.some((b) => b.pending()) };
  };

  // Tài khoản mới → bộ dữ liệu trống của riêng họ; xoá tài khoản → bỏ bộ dữ liệu khỏi bộ nhớ (file được giữ lại, đổi tên)
  function addUser(id) {
    if (users.has(id)) return;
    const b = dataBucket(id);
    b.fresh();
    users.set(id, b);
    b.save();
  }
  function removeUser(id) {
    if (id === ctx.ADMIN) throw new Error('Không xoá được dữ liệu của admin');
    const b = users.get(id);
    if (!b) return;
    b.retire();
    users.delete(id);
  }

  if (!client && !cfgError) { // chế độ file: nạp ngay khi khởi tạo (lỗi, vd sai SECRET_KEY → init() báo, tool không chạy)
    try {
      accounts.readLocal();
      users.get(ctx.ADMIN).readLocal();
      for (const id of listedIds()) { const b = dataBucket(id); b.readLocal(); users.set(id, b); }
      flushNotices();
    } catch (e) { cfgError = e; }
  }

  return {
    get, save, log, updateLog, uid, init, flush, status, ids, userId, addUser, removeUser,
    accounts: () => accounts.data,
    saveAccounts: () => accounts.save(),
    perUser: (init) => ctx.perUser(init, userId),
    run: ctx.run,
  };
}

module.exports = create();
module.exports.create = create;
