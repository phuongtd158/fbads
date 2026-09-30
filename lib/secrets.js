// Mã hoá token trong file dữ liệu (chế độ file) bằng SECRET_KEY: token Facebook, App Secret, token Telegram.
// Giờ một máy chứa token của nhiều tài khoản, ai lấy được file data*.json (bản sao lưu, chép nhầm…) cũng không dùng được token.
//  - Không đặt SECRET_KEY: ghi nguyên văn như trước.
//  - Giá trị dạng "enc:v1:<base64(iv | tag | dữ liệu)>" (AES-256-GCM). Giá trị không có tiền tố là dữ liệu cũ → dùng nguyên văn,
//    lần lưu sau sẽ được mã hoá.
// Chế độ Upstash không cần: cả bộ dữ liệu đã được mã hoá bằng DATA_KEY (lib/remote.js).
const crypto = require('crypto');

const PREFIX = 'enc:v1:';
const FIELDS = ['accessToken', 'fbAppSecret', 'telegramToken'];

let cached = null;
function keyOf(secret) {
  if (!cached || cached.secret !== secret) cached = { secret, key: crypto.scryptSync(secret, 'fbads.secrets.v1', 32) };
  return cached.key;
}

const isEncrypted = (v) => typeof v === 'string' && v.startsWith(PREFIX);

function encrypt(plain, secret) {
  if (!plain || !secret || isEncrypted(plain)) return plain;
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', keyOf(secret), iv);
  const body = Buffer.concat([c.update(String(plain), 'utf8'), c.final()]);
  return PREFIX + Buffer.concat([iv, c.getAuthTag(), body]).toString('base64');
}

function decrypt(stored, secret) {
  if (!isEncrypted(stored)) return stored;
  if (!secret) throw new Error('Token trong file dữ liệu đã được mã hoá nhưng chưa đặt SECRET_KEY. Đặt lại đúng SECRET_KEY cũ rồi chạy lại.');
  const buf = Buffer.from(stored.slice(PREFIX.length), 'base64');
  try {
    const d = crypto.createDecipheriv('aes-256-gcm', keyOf(secret), buf.subarray(0, 12));
    d.setAuthTag(buf.subarray(12, 28));
    return Buffer.concat([d.update(buf.subarray(28)), d.final()]).toString('utf8');
  } catch {
    throw new Error('Không giải mã được token trong file dữ liệu: SECRET_KEY khác với lúc đã lưu. Đặt lại đúng SECRET_KEY cũ rồi chạy lại.');
  }
}

// Bản sao của dữ liệu với các token đã mã hoá (để ghi file); dữ liệu trong bộ nhớ giữ nguyên văn
function sealData(data, secret) {
  if (!secret || !data || !data.settings) return data;
  const settings = { ...data.settings };
  for (const k of FIELDS) settings[k] = encrypt(settings[k], secret);
  return { ...data, settings };
}

// Dữ liệu vừa đọc từ file → giải mã token tại chỗ
function openData(data, secret) {
  if (data && data.settings) for (const k of FIELDS) data.settings[k] = decrypt(data.settings[k], secret);
  return data;
}

module.exports = { encrypt, decrypt, sealData, openData, isEncrypted, FIELDS };
