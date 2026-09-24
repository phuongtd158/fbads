// Lưu dữ liệu ở dịch vụ ngoài (Upstash Redis qua REST) cho nơi không có ổ đĩa bền, vd Render gói Free.
// Dịch vụ chỉ nhận bản đã nén + mã hoá bằng DATA_KEY (AES-256-GCM), nên không đọc được token hay cấu hình.
// Không dùng thư viện ngoài: chỉ crypto/zlib/fetch có sẵn trong Node.
const crypto = require('crypto');
const zlib = require('zlib');

const PREFIX = 'v1:';
const LOCAL = ['localhost', '127.0.0.1', '::1', '[::1]'];

function fail(code, message) { const e = new Error(message); e.code = code; return e; }

let cached = null;
function keyOf(secret) {
  if (!cached || cached.secret !== secret) cached = { secret, key: crypto.scryptSync(secret, 'fbads.data.v1', 32) };
  return cached.key;
}

// chuỗi JSON → 'v1:' + base64(iv | tag | dữ liệu nén đã mã hoá)
function encode(json, secret) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', keyOf(secret), iv);
  const body = Buffer.concat([c.update(zlib.gzipSync(json)), c.final()]);
  return PREFIX + Buffer.concat([iv, c.getAuthTag(), body]).toString('base64');
}

function decode(text, secret) {
  if (typeof text !== 'string' || !text.startsWith(PREFIX)) throw fail('BAD_FORMAT', 'Dữ liệu trên Upstash không đúng định dạng của tool này (khoá fbads:data bị ghi bởi chương trình khác?).');
  const buf = Buffer.from(text.slice(PREFIX.length), 'base64');
  if (buf.length < 29) throw fail('BAD_FORMAT', 'Dữ liệu trên Upstash bị cắt cụt hoặc hỏng.');
  try {
    const d = crypto.createDecipheriv('aes-256-gcm', keyOf(secret), buf.subarray(0, 12));
    d.setAuthTag(buf.subarray(12, 28));
    return zlib.gunzipSync(Buffer.concat([d.update(buf.subarray(28)), d.final()])).toString('utf8');
  } catch {
    throw fail('BAD_KEY', 'Không giải mã được dữ liệu trên Upstash: DATA_KEY sai (khác khoá đã dùng lúc lưu) hoặc dữ liệu bị hỏng. Tool dừng lại và KHÔNG ghi đè gì cả.');
  }
}

// null = không cấu hình (dùng file). Có cấu hình mà thiếu/sai → ném lỗi rõ ràng, tool không khởi động.
function config(env = process.env) {
  const url = (env.UPSTASH_REDIS_REST_URL || '').trim(), token = (env.UPSTASH_REDIS_REST_TOKEN || '').trim();
  if (!url && !token) return null;
  if (!url || !token) throw fail('BAD_CONFIG', 'Cần đặt cả UPSTASH_REDIS_REST_URL và UPSTASH_REDIS_REST_TOKEN.');
  let u;
  try { u = new URL(url); } catch { throw fail('BAD_CONFIG', 'UPSTASH_REDIS_REST_URL không phải địa chỉ hợp lệ (dạng https://xxxx.upstash.io).'); }
  if (u.protocol !== 'https:' && !(u.protocol === 'http:' && LOCAL.includes(u.hostname))) throw fail('BAD_CONFIG', 'UPSTASH_REDIS_REST_URL phải bắt đầu bằng https:// (token không được gửi qua http).');
  const secret = env.DATA_KEY || '';
  if (secret.length < 16) throw fail('BAD_CONFIG', 'Thiếu DATA_KEY (ít nhất 16 ký tự). Đây là khoá mã hoá dữ liệu trước khi gửi lên Upstash; hãy lưu nó ở nơi khác, mất khoá thì không đọc lại được dữ liệu.');
  return { url, token, secret };
}

// Client REST của Upstash: POST một lệnh Redis dạng mảng JSON, ví dụ ["SET","khoá","giá trị"].
function upstash({ url, token, timeoutMs = 10000 }) {
  const base = url.replace(/\/+$/, '');
  async function cmd(args) {
    let res;
    try {
      res = await fetch(base, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(args),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (e) {
      throw new Error(e.name === 'TimeoutError' ? `quá ${timeoutMs / 1000}s không có phản hồi` : `không kết nối được (${(e.cause && e.cause.code) || e.message})`);
    }
    let body = null;
    try { body = await res.json(); } catch { /* không phải JSON */ }
    if (!res.ok || (body && body.error)) throw new Error(`Upstash trả lỗi ${res.status}${body && body.error ? `: ${body.error}` : ''}`);
    return body ? body.result : null;
  }
  return { get: (k) => cmd(['GET', k]), set: (k, v) => cmd(['SET', k, v]) };
}

module.exports = { encode, decode, config, upstash };
