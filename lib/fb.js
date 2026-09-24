// Client cho Facebook Marketing API + chế độ giả lập (mock) để dùng thử.
const store = require('./store');

const NO_DECIMAL = new Set(['VND', 'JPY', 'KRW', 'CLP', 'ISK', 'PYG']);
let currency = 'VND';
const offset = () => (NO_DECIMAL.has(currency) ? 1 : 100);

// ---------- Graph API ----------
// Dịch lỗi Facebook sang tiếng Việt dễ hiểu, kèm việc cần làm.
function friendly(err) {
  const c = err.code, sub = err.error_subcode, raw = err.error_user_msg || err.message || 'Lỗi không xác định';
  if (c === 190) return 'Token đã hết hạn hoặc không hợp lệ. Hãy tạo token mới và dán lại trong Cài đặt.';
  if ([10, 200, 294, 278].includes(c)) return 'Token chưa đủ quyền. Cần tick quyền ads_management và ads_read, và tài khoản quảng cáo phải được gán cho token/người dùng này.';
  if ([4, 17, 32, 613, 80004].includes(c)) return 'Facebook đang giới hạn số lần gọi. Chờ vài phút rồi thử lại.';
  if (c === 100 && /act_|ad account|nonexisting|does not exist/i.test(raw)) return 'Ad Account ID không đúng, hoặc token không có quyền vào tài khoản này. Hãy chọn lại tài khoản từ danh sách.';
  if (c === 100 && sub === 1487225) return 'Không thể đổi ngân sách ở cấp này (camp đang dùng ngân sách chiến dịch - CBO).';
  return raw;
}

// Bỏ mọi tham số nhạy cảm và cắt ngắn giá trị dài trước khi ghi vào nhật ký
const SECRET_KEYS = new Set(['access_token', 'client_secret', 'fb_exchange_token', 'input_token']);
function cleanParams(p) {
  const o = {};
  for (const [k, v] of Object.entries(p || {})) if (!SECRET_KEYS.has(k)) o[k] = String(v).slice(0, 300);
  return o;
}
function fbError(message, fb) { const e = new Error(message); e.fb = fb; return e; }

async function graph(method, path, params = {}, tokenOverride) {
  const s = store.get().settings;
  const token = tokenOverride || s.accessToken;
  if (!token) throw new Error('Chưa nhập Access Token trong Cài đặt');
  const url = new URL(`https://graph.facebook.com/${s.apiVersion}/${path}`);
  const body = new URLSearchParams({ ...params, access_token: token });
  const request = { method, path: `/${s.apiVersion}/${path}`, params: cleanParams(params) };
  let res;
  try {
    if (method === 'GET') {
      url.search = body.toString();
      res = await fetch(url);
    } else {
      res = await fetch(url, { method, body });
    }
  } catch (e) {
    throw fbError('Không kết nối được tới Facebook. Kiểm tra mạng internet.', { network: true, systemMessage: String(e && e.cause && e.cause.code || e && e.message || ''), request });
  }
  let json;
  try { json = await res.json(); } catch { throw fbError(`Facebook trả về dữ liệu không đọc được (HTTP ${res.status}).`, { httpStatus: res.status, request }); }
  if (json.error) {
    const er = json.error;
    throw fbError(friendly(er), { code: er.code, subcode: er.error_subcode, type: er.type, fbtraceId: er.fbtrace_id, userMsg: er.error_user_msg, rawMessage: er.message, httpStatus: res.status, request });
  }
  return json;
}

async function graphAll(path, params, token) {
  let json = await graph('GET', path, { limit: 200, ...params }, token);
  const out = [...json.data];
  while (json.paging && json.paging.next) {
    const r = await fetch(json.paging.next);
    json = await r.json();
    if (json.error) throw new Error(json.error.message);
    out.push(...json.data);
  }
  return out;
}

const acct = () => {
  const id = store.get().settings.adAccountId.trim();
  return id.startsWith('act_') ? id : `act_${id}`;
};

function metricsFrom(row, resultAction) {
  const spend = parseFloat(row.spend || 0);
  const pick = (arr) => (arr || []).filter((a) => a.action_type === resultAction).reduce((t, a) => t + parseFloat(a.value), 0);
  const results = pick(row.actions);
  const value = pick(row.action_values);
  return {
    spend,
    impressions: parseInt(row.impressions || 0, 10),
    clicks: parseInt(row.clicks || 0, 10),
    results,
    cpa: results > 0 ? spend / results : null,
    roas: spend > 0 ? value / spend : null,
  };
}

const EMPTY = { spend: 0, impressions: 0, clicks: 0, results: 0, cpa: null, roas: null };

async function realList() {
  const s = store.get().settings;
  const info = await graph('GET', acct(), { fields: 'currency,name' });
  currency = info.currency;
  const [camps, adsets, ic, ia] = await Promise.all([
    graphAll(`${acct()}/campaigns`, { fields: 'id,name,status,effective_status,daily_budget' }),
    graphAll(`${acct()}/adsets`, { fields: 'id,name,status,effective_status,daily_budget,campaign_id' }),
    graphAll(`${acct()}/insights`, { level: 'campaign', date_preset: 'today', fields: 'campaign_id,spend,impressions,clicks,actions,action_values' }),
    graphAll(`${acct()}/insights`, { level: 'adset', date_preset: 'today', fields: 'adset_id,spend,impressions,clicks,actions,action_values' }),
  ]);
  const mc = Object.fromEntries(ic.map((r) => [r.campaign_id, metricsFrom(r, s.resultAction)]));
  const ma = Object.fromEntries(ia.map((r) => [r.adset_id, metricsFrom(r, s.resultAction)]));
  const conv = (v) => (v ? parseInt(v, 10) / offset() : null);
  return [
    ...camps.map((c) => ({ id: c.id, name: c.name, level: 'campaign', status: c.status, effective: c.effective_status, dailyBudget: conv(c.daily_budget), metrics: mc[c.id] || EMPTY })),
    ...adsets.map((a) => ({ id: a.id, name: a.name, level: 'adset', campaignId: a.campaign_id, status: a.status, effective: a.effective_status, dailyBudget: conv(a.daily_budget), metrics: ma[a.id] || EMPTY })),
  ];
}

// ---------- Mock ----------
let mockObjs = null;
function mockInit() {
  const names = ['[Sale] Mua hàng - Khách lạnh', '[Retarget] Người xem 7 ngày', '[Lead] Form đăng ký tư vấn', '[Sale] Lookalike 1%', '[Brand] Video giới thiệu', '[Sale] Combo cuối tuần'];
  mockObjs = names.map((name, i) => ({
    id: `mock_${i + 1}`, name, level: 'campaign', status: i % 3 === 2 ? 'PAUSED' : 'ACTIVE',
    effective: i % 3 === 2 ? 'PAUSED' : 'ACTIVE', dailyBudget: [500000, 300000, 200000, 800000, 150000, 400000][i], seed: i + 1,
  }));
}
function mockList() {
  if (!mockObjs) mockInit();
  const h = new Date().getHours() + new Date().getMinutes() / 60;
  return mockObjs.map((o) => {
    const active = o.effective === 'ACTIVE';
    const spend = active ? Math.round(o.dailyBudget * Math.min(1, h / 24) * (0.7 + (o.seed % 4) * 0.12)) : 0;
    const results = active ? Math.floor(spend / (60000 + o.seed * 25000 + (o.seed % 2) * 90000)) : 0;
    return { ...o, metrics: { spend, impressions: spend * 9, clicks: Math.round(spend / 900), results,
      cpa: results ? spend / results : null, roas: spend ? (results * 380000) / spend : null } };
  });
}

// ---------- Public API ----------
let cache = { at: 0, data: null };
const isMock = () => store.get().settings.mock;

async function listObjects(force = false) {
  if (!force && cache.data && Date.now() - cache.at < 45000) return cache.data;
  const data = isMock() ? mockList() : await realList();
  cache = { at: Date.now(), data };
  return data;
}

// Danh sách đã cache (không gọi Facebook); null nếu chưa có — dùng để validate theo khả năng
const peekObjects = () => cache.data;

// Ảnh chụp trạng thái một camp/nhóm tại thời điểm thao tác (để xem trước/sau trong nhật ký)
const snapshot = (o) => (o ? { level: o.level, status: o.status, effective: o.effective, dailyBudget: o.dailyBudget, metrics: o.metrics ? { spend: o.metrics.spend, results: o.metrics.results, cpa: o.metrics.cpa, roas: o.metrics.roas } : null } : undefined);

// Chuyển lỗi bất kỳ thành object gọn để lưu nhật ký (lỗi Facebook giữ nguyên mã; lỗi nội bộ kèm vài dòng stack)
function describeError(e) {
  const fb = (e && e.fb) || {};
  const out = { message: (e && e.message) || String(e), ...fb };
  if (!fb.code && !fb.network && !fb.httpStatus) out.stack = String((e && e.stack) || '').split('\n').slice(0, 6).join('\n').slice(0, 800);
  return out;
}
const resetCache = () => { cache = { at: 0, data: null }; };

async function setStatus(id, on) {
  if (isMock()) {
    if (!mockObjs) mockInit();
    const o = mockObjs.find((x) => x.id === id);
    if (o) o.status = o.effective = on ? 'ACTIVE' : 'PAUSED';
  } else {
    await graph('POST', id, { status: on ? 'ACTIVE' : 'PAUSED' });
  }
  cache.at = 0;
}

async function setBudget(id, amount) {
  amount = Math.round(amount);
  if (isMock()) {
    if (!mockObjs) mockInit();
    const o = mockObjs.find((x) => x.id === id);
    if (o) o.dailyBudget = amount;
  } else {
    await graph('POST', id, { daily_budget: String(Math.round(amount * offset())) });
  }
  cache.at = 0;
}

// ---------- Kết nối & token ----------
const ACC_STATUS = { 1: 'Đang hoạt động', 2: 'Bị vô hiệu hoá', 3: 'Nợ thanh toán', 7: 'Đang xét duyệt rủi ro', 8: 'Đang xử lý thanh toán', 9: 'Trong thời gian gia hạn', 100: 'Đang chờ đóng', 101: 'Đã đóng', 201: 'Đang chờ', 202: 'Đã đóng' };
const NEED_SCOPES = ['ads_management', 'ads_read'];

async function inspectToken(token) {
  const r = await graph('GET', 'debug_token', { input_token: token }, token);
  const d = r.data || {};
  const scopes = d.scopes || [];
  const exp = d.expires_at ? d.expires_at * 1000 : 0; // 0 = không hết hạn
  return {
    valid: d.is_valid !== false,
    expiresAt: exp || null,
    daysLeft: exp ? Math.floor((exp - Date.now()) / 864e5) : null,
    scopes,
    missing: NEED_SCOPES.filter((x) => !scopes.includes(x)),
    type: d.type || '',
    appId: d.app_id || '',
  };
}

async function listAccounts(token) {
  const me = await graph('GET', 'me', { fields: 'name' }, token);
  const list = await graphAll('me/adaccounts', { fields: 'account_id,name,currency,account_status' }, token);
  const tk = await inspectToken(token).catch(() => null);
  return {
    user: me.name,
    token: tk,
    accounts: list.map((a) => ({ id: a.account_id, name: a.name, currency: a.currency, status: ACC_STATUS[a.account_status] || String(a.account_status), active: a.account_status === 1 })),
  };
}

// Đổi token ngắn hạn (vài giờ) thành token dài hạn (~60 ngày).
async function extendToken(appId, appSecret, token) {
  const r = await graph('GET', 'oauth/access_token', { grant_type: 'fb_exchange_token', client_id: appId, client_secret: appSecret, fb_exchange_token: token }, token);
  return r.access_token;
}

async function testConnection() {
  if (isMock()) return { ok: true, mock: true, name: 'Chế độ dùng thử (dữ liệu giả)', currency: 'VND' };
  const s = store.get().settings;
  if (!s.accessToken) throw new Error('Chưa có Access Token.');
  if (!s.adAccountId) throw new Error('Chưa chọn tài khoản quảng cáo.');
  const [me, info, tk] = await Promise.all([
    graph('GET', 'me', { fields: 'name' }),
    graph('GET', acct(), { fields: 'name,currency,account_status,amount_spent' }),
    inspectToken(s.accessToken).catch(() => null),
  ]);
  currency = info.currency;
  return {
    ok: true, user: me.name, name: info.name, currency: info.currency,
    status: ACC_STATUS[info.account_status] || String(info.account_status), accountActive: info.account_status === 1,
    token: tk, checkedAt: Date.now(),
  };
}

module.exports = { snapshot, describeError, resetCache, peekObjects, listObjects, setStatus, setBudget, testConnection, listAccounts, extendToken, inspectToken, getCurrency: () => currency };
