const fs = require('fs');
const path = require('path');

// DATA_DIR cho phép đặt file dữ liệu ở ổ đĩa riêng khi deploy (Docker/VPS).
const FILE = path.join(process.env.DATA_DIR || path.join(__dirname, '..'), 'data.json');

const defaults = () => ({
  settings: {
    mock: true,            // true = dữ liệu giả để dùng thử, không chạm vào Facebook
    dryRun: true,          // true = tự động hoá chỉ ghi log, không thực thi thật
    accessToken: '',
    adAccountId: '',
    apiVersion: 'v21.0',
    timezone: 'Asia/Ho_Chi_Minh',
    resultAction: 'purchase', // loại kết quả để tính CPA/ROAS: purchase, lead, ...
    ruleIntervalMin: 15,
    telegramToken: '',
    telegramChatId: '',
    reportTime: '08:00',
    passwordHash: '',       // mật khẩu đăng nhập (băm scrypt), trống = không cần đăng nhập
  },
  schedules: [],
  rules: [],
  logs: [],
  state: { fired: {}, lastRule: {}, lastReport: '' },
});

let data = defaults();
try {
  const saved = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  data = { ...data, ...saved, settings: { ...data.settings, ...saved.settings }, state: { ...data.state, ...saved.state } };
} catch { /* first run */ }

function save() {
  const tmp = FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, FILE);
}

function log(entry) {
  data.logs.unshift({ ts: new Date().toISOString(), ...entry });
  if (data.logs.length > 1000) data.logs.length = 1000;
  save();
}

const uid = () => Math.random().toString(36).slice(2, 10);

module.exports = { get: () => data, save, log, uid };
