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
  if (isRateLimit(c)) return `Facebook đang giới hạn số lần gọi. Tool tạm ngưng gọi và tự thử lại sau khoảng ${Math.max(1, Math.ceil((limits.blockedUntil - Date.now()) / 60000))} phút.`;
  if (c === 100 && /act_|ad account|nonexisting|does not exist/i.test(raw)) return 'Ad Account ID không đúng, hoặc token không có quyền vào tài khoản này. Hãy chọn lại tài khoản từ danh sách.';
  if (c === 100 && sub === 1487225) return 'Không thể đổi ngân sách ở cấp này (camp đang dùng ngân sách chiến dịch - CBO).';
  return raw;
}

// Bỏ mọi tham số nhạy cảm và cắt ngắn giá trị dài trước khi ghi vào nhật ký
const SECRET_KEYS = new Set(['access_token', 'client_secret', 'fb_exchange_token', 'input_token', 'code']);
function cleanParams(p) {
  const o = {};
  for (const [k, v] of Object.entries(p || {})) if (!SECRET_KEYS.has(k)) o[k] = String(v).slice(0, 300);
  return o;
}
function fbError(message, fb) { const e = new Error(message); e.fb = fb; return e; }

// ----- Giới hạn số lần gọi -----
// Facebook báo mức đã dùng (%) trong tiêu đề của mỗi phản hồi; ứng dụng ở mức development_access có hạn mức rất thấp.
// Khi bị chặn, Facebook báo số phút phải chờ (estimated_time_to_regain_access) → tool ngưng đọc số liệu đến lúc đó.
const RATE_CODES = [4, 17, 32, 613];
const isRateLimit = (c) => RATE_CODES.includes(c) || (c >= 80000 && c <= 80014);
const limits = { pct: 0, tier: '', at: 0, blockedUntil: 0 };
function readUsage(res) {
  let pct = null, regainMin = 0;
  for (const h of ['x-business-use-case-usage', 'x-ad-account-usage', 'x-app-usage']) {
    const raw = res.headers.get(h);
    if (!raw) continue;
    try {
      const j = JSON.parse(raw);
      for (const r of h === 'x-business-use-case-usage' ? Object.values(j).flat() : [j]) {
        pct = Math.max(pct || 0, r.call_count || 0, r.total_cputime || 0, r.total_time || 0, r.acc_id_util_pct || 0);
        regainMin = Math.max(regainMin, r.estimated_time_to_regain_access || 0);
        if (r.ads_api_access_tier) limits.tier = r.ads_api_access_tier;
      }
    } catch { /* tiêu đề lạ: bỏ qua */ }
  }
  if (pct != null) Object.assign(limits, { pct, at: Date.now() });
  if (regainMin) limits.blockedUntil = Math.max(limits.blockedUntil, Date.now() + regainMin * 60e3);
}

// Gửi 1 yêu cầu tới Graph API, đọc mức dùng và chuyển lỗi Facebook sang tiếng Việt (dùng cho cả trang tiếp theo khi phân trang)
async function send(url, init, request) {
  let res;
  try { res = await fetch(url, init); } catch (e) {
    throw fbError('Không kết nối được tới Facebook. Kiểm tra mạng internet.', { network: true, systemMessage: String(e && e.cause && e.cause.code || e && e.message || ''), request });
  }
  readUsage(res);
  let json;
  try { json = await res.json(); } catch { throw fbError(`Facebook trả về dữ liệu không đọc được (HTTP ${res.status}).`, { httpStatus: res.status, request }); }
  if (json.error) {
    const er = json.error;
    if (isRateLimit(er.code)) limits.blockedUntil = Math.max(limits.blockedUntil, Date.now() + 5 * 60e3); // không báo thời gian chờ → nghỉ 5 phút
    throw fbError(friendly(er), { code: er.code, subcode: er.error_subcode, type: er.type, fbtraceId: er.fbtrace_id, userMsg: er.error_user_msg, rawMessage: er.message, httpStatus: res.status, request });
  }
  return json;
}

async function graph(method, path, params = {}, tokenOverride) {
  const s = store.get().settings;
  const token = tokenOverride || s.accessToken;
  if (!token) throw new Error('Chưa nhập Access Token trong Cài đặt');
  const url = new URL(`https://graph.facebook.com/${s.apiVersion}/${path}`);
  const body = new URLSearchParams({ ...params, access_token: token });
  const request = { method, path: `/${s.apiVersion}/${path}`, params: cleanParams(params) };
  if (method === 'GET') {
    url.search = body.toString();
    return send(url, undefined, request);
  }
  return send(url, { method, body }, request);
}

// Lấy hết các trang. 500 mục/trang để ít lần gọi (tài khoản vài trăm camp, cả nghìn nhóm QC).
async function graphAll(path, params, token) {
  let json = await graph('GET', path, { limit: 500, ...params }, token);
  const out = [...json.data];
  while (json.paging && json.paging.next) {
    json = await send(json.paging.next, undefined, { method: 'GET', path: `/${path}`, params: { page: 'next' } });
    out.push(...json.data);
  }
  return out;
}

const acct = () => {
  const id = store.get().settings.adAccountId.trim();
  return id.startsWith('act_') ? id : `act_${id}`;
};

// Một loại "kết quả" được Facebook báo dưới nhiều tên tuỳ nơi phát sinh: web (pixel), app, hay ngay trên Meta (Messenger/Shop).
// Vd tài khoản chốt đơn qua tin nhắn không có "purchase" mà chỉ có onsite_conversion.purchase / omni_purchase ("Lượt mua trên Meta").
// Lấy tên ĐẦU TIÊN có trong số liệu theo thứ tự dưới — không cộng các tên lại vì cùng một đơn được báo ở nhiều tên.
const RESULT_ALIASES = {
  purchase: ['omni_purchase', 'purchase', 'onsite_conversion.purchase', 'offsite_conversion.fb_pixel_purchase', 'onsite_web_purchase'],
  lead: ['lead', 'onsite_conversion.lead_grouped', 'offsite_conversion.fb_pixel_lead', 'onsite_web_lead'],
  initiate_checkout: ['omni_initiated_checkout', 'initiate_checkout', 'onsite_conversion.initiate_checkout', 'offsite_conversion.fb_pixel_initiate_checkout'],
};
function metricsFrom(row, resultAction) {
  const spend = parseFloat(row.spend || 0);
  const names = RESULT_ALIASES[resultAction] || [resultAction];
  const pick = (arr) => {
    for (const n of names) {
      const hit = (arr || []).find((a) => a.action_type === n);
      if (hit) return parseFloat(hit.value) || 0;
    }
    return 0;
  };
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
  // Đơn vị tiền gần như không đổi → chỉ hỏi lại mỗi giờ (bớt 1 lần gọi mỗi lượt tải)
  if (Date.now() - currencyAt > 3600e3) { currency = (await graph('GET', acct(), { fields: 'currency' })).currency; currencyAt = Date.now(); }
  const [camps, adsets, ic, ia] = await Promise.all([
    graphAll(`${acct()}/campaigns`, { fields: 'id,name,status,effective_status,daily_budget' }),
    // learning_stage_info là thông tin bổ sung; nếu Facebook từ chối trường này thì vẫn lấy danh sách nhóm QC như cũ (không có nhãn "đang học").
    // Bị giới hạn số lần gọi thì KHÔNG thử lại (thử lại chỉ tốn thêm lượt).
    graphAll(`${acct()}/adsets`, { fields: 'id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time,learning_stage_info' })
      .catch((e) => {
        if (e.fb && isRateLimit(e.fb.code)) throw e;
        return graphAll(`${acct()}/adsets`, { fields: 'id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time' });
      }),
    graphAll(`${acct()}/insights`, { level: 'campaign', date_preset: 'today', fields: 'campaign_id,spend,impressions,clicks,actions,action_values' }),
    graphAll(`${acct()}/insights`, { level: 'adset', date_preset: 'today', fields: 'adset_id,spend,impressions,clicks,actions,action_values' }),
  ]);
  const mc = Object.fromEntries(ic.map((r) => [r.campaign_id, metricsFrom(r, s.resultAction)]));
  const ma = Object.fromEntries(ia.map((r) => [r.adset_id, metricsFrom(r, s.resultAction)]));
  const conv = (v) => (v ? parseInt(v, 10) / offset() : null);
  // Giai đoạn học: nhóm QC có learning_stage_info.status = LEARNING; camp coi là đang học nếu có nhóm nào đang học
  const isLearning = (a) => !!(a.learning_stage_info && a.learning_stage_info.status === 'LEARNING');
  const learningCamps = new Set(adsets.filter(isLearning).map((a) => a.campaign_id));
  // Giờ bắt đầu/kết thúc của nhóm QC (để cột Phân phối biết "Đã lên lịch" / "Hoàn tất"). Facebook trả dạng +0700 → đổi sang mili giây.
  const ms = (t) => { const v = t ? Date.parse(String(t).replace(/([+-]\d{2})(\d{2})$/, '$1:$2')) : NaN; return Number.isFinite(v) ? v : null; };
  return [
    ...camps.map((c) => ({ id: c.id, name: c.name, level: 'campaign', status: c.status, effective: c.effective_status, dailyBudget: conv(c.daily_budget), learning: learningCamps.has(c.id), metrics: mc[c.id] || EMPTY })),
    ...adsets.map((a) => ({ id: a.id, name: a.name, level: 'adset', campaignId: a.campaign_id, status: a.status, effective: a.effective_status, dailyBudget: conv(a.daily_budget), learning: isLearning(a), startTime: ms(a.start_time), endTime: ms(a.end_time), metrics: ma[a.id] || EMPTY })),
  ];
}

// ---------- Mock ----------
let mockObjs = null;
function mockInit() {
  const names = ['[Sale] Mua hàng - Khách lạnh', '[Retarget] Người xem 7 ngày', '[Lead] Form đăng ký tư vấn', '[Sale] Lookalike 1%', '[Brand] Video giới thiệu', '[Sale] Combo cuối tuần'];
  mockObjs = names.map((name, i) => ({
    id: `mock_${i + 1}`, name, level: 'campaign', status: i % 3 === 2 ? 'PAUSED' : 'ACTIVE',
    effective: i % 3 === 2 ? 'PAUSED' : 'ACTIVE', dailyBudget: [500000, 300000, 200000, 800000, 150000, 400000][i], seed: i + 1, learning: i === 4,
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

// Số liệu giả cho các khoảng thời gian dài (không phụ thuộc trạng thái hiện tại của camp)
function mockRange(range) {
  if (!mockObjs) mockInit();
  const days = { yesterday: 1, last_3d: 3, last_7d: 7 }[range] || 1;
  return Object.fromEntries(mockObjs.map((o) => {
    const spend = Math.round(o.dailyBudget * days * (0.75 + (o.seed % 4) * 0.1));
    const results = Math.floor(spend / (60000 + o.seed * 25000 + (o.seed % 2) * 90000));
    return [o.id, { spend, impressions: spend * 9, clicks: Math.round(spend / 900), results, cpa: results ? spend / results : null, roas: spend ? (results * 380000) / spend : null }];
  }));
}

// ---------- Public API ----------
let cache = { at: 0, data: null, stale: false };
let currencyAt = 0;
const isMock = () => store.get().settings.mock;

// Thời gian dùng lại số liệu đã tải thay vì gọi Facebook:
//  - tự làm mới (không ép): 2 phút; khi mức dùng API ≥ 60% thì 5 phút
//  - bấm "Làm mới" / engine cần số mới (ép): vẫn dùng lại nếu vừa tải trong 15 giây
const FORCE_MIN_MS = 15e3, TTL_MS = 120e3, TTL_BUSY_MS = 300e3;

async function listObjects(force = false) {
  const now = Date.now(), age = now - cache.at;
  if (isMock()) {
    if (!force && cache.data && age < 45000) return cache.data;
    cache = { at: now, data: mockList(), stale: false };
    return cache.data;
  }
  const ttl = force ? FORCE_MIN_MS : limits.pct >= 60 ? TTL_BUSY_MS : TTL_MS;
  if (cache.data && age < ttl) return cache.data;
  // Đang bị Facebook chặn: không gọi (gọi chỉ kéo dài thời gian bị chặn), dùng số liệu gần nhất
  if (now < limits.blockedUntil) {
    if (cache.data) { cache.stale = true; return cache.data; }
    throw fbError(friendly({ code: 17 }), { code: 17 });
  }
  try {
    cache = { at: Date.now(), data: await realList(), stale: false };
  } catch (e) {
    if (cache.data && e.fb && isRateLimit(e.fb.code)) { cache.stale = true; return cache.data; }
    throw e;
  }
  return cache.data;
}

// Thông tin kèm danh sách cho giao diện: số liệu lúc nào, có phải số cũ vì bị giới hạn không, mức dùng API
const objectsMeta = () => ({
  at: cache.at || null,
  stale: !!cache.stale,
  blockedUntil: limits.blockedUntil > Date.now() ? limits.blockedUntil : null,
  usage: limits.at ? { pct: limits.pct, tier: limits.tier } : null,
});

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
const resetCache = () => { cache = { at: 0, data: null, stale: false }; currencyAt = 0; for (const k of Object.keys(rangeCache)) delete rangeCache[k]; };

// Số liệu theo khoảng thời gian cho rule: { [id]: metrics }. Cache 4 phút để không gọi Facebook quá dày.
const rangeCache = {};
async function rangeMetrics(range, force = false) {
  if (!range || range === 'today') return Object.fromEntries((await listObjects(force)).map((o) => [o.id, o.metrics]));
  const c = rangeCache[range];
  if (!force && c && c.mock === isMock() && Date.now() - c.at < 240000) return c.data;
  let data = {};
  if (isMock()) data = mockRange(range);
  else {
    const rs = store.get().settings.resultAction;
    const [ic, ia] = await Promise.all([
      graphAll(`${acct()}/insights`, { level: 'campaign', date_preset: range, fields: 'campaign_id,spend,impressions,clicks,actions,action_values' }),
      graphAll(`${acct()}/insights`, { level: 'adset', date_preset: range, fields: 'adset_id,spend,impressions,clicks,actions,action_values' }),
    ]);
    for (const r of ic) data[r.campaign_id] = metricsFrom(r, rs);
    for (const r of ia) data[r.adset_id] = metricsFrom(r, rs);
  }
  rangeCache[range] = { at: Date.now(), data, mock: isMock() };
  return data;
}

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

// ---------- Đăng nhập bằng Facebook (OAuth) ----------
// Trang đăng nhập của Facebook; sau khi cho phép, Facebook chuyển về redirectUri kèm ?code=…&state=…
function oauthUrl({ appId, configId, redirectUri, state }) {
  const u = new URL(`https://www.facebook.com/${store.get().settings.apiVersion}/dialog/oauth`);
  u.search = new URLSearchParams({
    client_id: appId, redirect_uri: redirectUri, state, response_type: 'code',
    ...(configId ? { config_id: configId } : { scope: NEED_SCOPES.join(','), auth_type: 'rerequest' }),
  }).toString();
  return u.toString();
}

// Đổi code lấy token (ngắn hạn) rồi gia hạn lên ~60 ngày. Gọi bằng app token vì lúc này chưa có token người dùng.
async function exchangeCode({ appId, appSecret, redirectUri, code }) {
  const appToken = `${appId}|${appSecret}`;
  const r = await graph('GET', 'oauth/access_token', { client_id: appId, client_secret: appSecret, redirect_uri: redirectUri, code }, appToken);
  if (!r.access_token) throw new Error('Facebook không trả về token.');
  return extendToken(appId, appSecret, r.access_token).catch(() => r.access_token); // gia hạn lỗi thì vẫn dùng token ngắn hạn
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
  currency = info.currency; currencyAt = Date.now();
  return {
    ok: true, user: me.name, name: info.name, currency: info.currency,
    status: ACC_STATUS[info.account_status] || String(info.account_status), accountActive: info.account_status === 1,
    token: tk, checkedAt: Date.now(),
  };
}

// Đặt lại dữ liệu giả về ban đầu (dùng cho kiểm thử)
const resetMock = () => { mockObjs = null; };

module.exports = { metricsFrom, objectsMeta, isRateLimited: (e) => !!(e && e.fb && isRateLimit(e.fb.code)), resetMock, rangeMetrics, snapshot, describeError, resetCache, peekObjects, listObjects, setStatus, setBudget, testConnection, listAccounts, extendToken, inspectToken, oauthUrl, exchangeCode, getCurrency: () => currency };
