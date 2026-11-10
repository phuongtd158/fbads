const remote = require('./remote');

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
  // Báo cáo lên hệ thống công ty (lib/companyReport.js): cài đặt + các bản báo cáo theo mốc
  company: { enabled: false, mode: 'approve', slots: [9, 12, 17, 22], leadMin: 0, baseUrl: 'https://mkt.companyos.site', email: '', password: '', teams: [] },
  companyReports: [],
  state: { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', resume: {} },
});

// Gộp dữ liệu đã lưu với mặc định: bản cập nhật thêm khoá mới mà không làm mất dữ liệu cũ
const normalize = (saved) => {
  const d = defaults();
  const out = { ...d, ...saved, settings: { ...d.settings, ...saved.settings }, state: { ...d.state, ...saved.state }, company: { ...d.company, ...saved.company } };
  // dữ liệu cũ chỉ có 1 tài khoản quảng cáo → chuyển sang danh sách
  if (!(out.settings.adAccountIds || []).length && out.settings.adAccountId) out.settings.adAccountIds = [out.settings.adAccountId];
  // báo cáo công ty mốc 9h tạo theo cách tính ngày cũ (dateRule < 3, vd bản ghi nhầm ngày nộp) mà chưa gửi → bỏ, tránh gửi sai ngày
  if (Array.isArray(out.companyReports)) out.companyReports = out.companyReports.filter((r) => !(Number(r.slot) === 9 && (r.dateRule || 1) < 3 && r.status !== 'sent'));
  return out;
};
const KEY = 'fbads:data', BACKUP = 'fbads:data:backup';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Dữ liệu chỉ lưu ở Upstash (đã nén + mã hoá bằng DATA_KEY), KHÔNG ghi file nào ra ổ đĩa.
// Thiếu cấu hình UPSTASH_REDIS_REST_URL/TOKEN + DATA_KEY thì init() từ chối, tool không khởi động.
// Riêng kiểm thử: STORE_MEMORY=1 → giữ dữ liệu trong bộ nhớ, không lưu đi đâu (mất khi tắt tiến trình).
// `create` nhận tuỳ chọn để kiểm thử; module xuất sẵn một bản dùng biến môi trường thật.
function create(opts = {}) {
  const env = opts.env || process.env;
  const memory = env.STORE_MEMORY === '1';
  const debounceMs = opts.debounceMs ?? 1500;
  const retryBaseMs = opts.retryBaseMs ?? 2000;

  let cfg = null, cfgError = null;
  if (!memory) { try { cfg = remote.config(env); } catch (e) { cfgError = e; } } // chế độ bộ nhớ bỏ qua Upstash để test không đụng dữ liệu thật
  if (!cfg && !cfgError && !memory) cfgError = new Error('Chưa cấu hình Upstash: cần đặt UPSTASH_REDIS_REST_URL, UPSTASH_REDIS_REST_TOKEN và DATA_KEY (tool không lưu dữ liệu ra file).');
  const client = cfg && (opts.client || remote.upstash(cfg));

  let data = defaults();
  let lastSavedAt = null, lastError = '';

  const uid = () => Math.random().toString(36).slice(2, 10);

  const parseObject = (text) => {
    const v = JSON.parse(text);
    if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('không phải đối tượng JSON');
    return v;
  };

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
    if (err) throw new Error(`Không đọc được dữ liệu từ Upstash: ${err.message}. Tool dừng lại để không ghi đè dữ liệu cũ bằng dữ liệu trống; hãy kiểm tra URL/token rồi chạy lại.`);
    if (raw == null) { // lần đầu: bắt đầu với dữ liệu trống
      data = normalize({});
      const json = JSON.stringify(data);
      await client.set(KEY, remote.encode(json, cfg.secret));
      lastJson = json; lastSavedAt = new Date().toISOString();
      console.log('  Lần đầu dùng Upstash: bắt đầu với dữ liệu trống.');
    } else {
      const json = remote.decode(raw, cfg.secret); // sai khoá / hỏng → ném lỗi, không ghi gì
      data = normalize(parseObject(json));
      lastJson = json;
      try { await client.set(BACKUP, raw); } catch { /* bản sao đầu phiên chỉ là phụ */ }
    }
    loaded = true;
  }

  // Gọi 1 lần lúc khởi động: nạp dữ liệu từ Upstash (chế độ bộ nhớ thì không làm gì)
  function init() {
    if (cfgError) return Promise.reject(cfgError);
    if (!client) return Promise.resolve();
    return (ready = ready || load());
  }

  function save() {
    if (!client) return; // chế độ bộ nhớ (kiểm thử)
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

  const status = () => ({ mode: client ? 'remote' : 'memory', provider: client ? 'Upstash' : null, lastSavedAt, lastError, pending: dirty || !!flushing });

  return { get: () => data, save, log, updateLog, uid, init, flush, status };
}

module.exports = create();
module.exports.create = create;
