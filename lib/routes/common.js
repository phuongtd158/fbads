// Tiện ích dùng chung cho các nhóm route.
const store = require('../store');

const SECRETS = ['accessToken', 'telegramToken', 'passwordHash', 'fbAppSecret'];

// Kết quả kiểm tra không hợp lệ (từ shared/validate.mjs) → 400 kèm lỗi từng trường
const bad = (res, r) => res.status(400).json({ error: r.first || r.error, errors: r.errors || {} });

// Cài đặt gửi về giao diện: không bao giờ lộ bí mật, chỉ cho biết đã có hay chưa (has_…)
function publicSettings() {
  const out = { ...store.get().settings };
  for (const k of SECRETS) { out[`has_${k}`] = !!out[k]; out[k] = ''; }
  return out;
}

// Thêm mới (chưa có id) hoặc thay thế mục cùng id rồi lưu
function upsert(list, item) {
  if (!item.id) { item.id = store.uid(); list.push(item); }
  else { const i = list.findIndex((x) => x.id === item.id); if (i >= 0) list[i] = item; else list.push(item); }
  store.save();
  return item;
}

module.exports = { bad, publicSettings, upsert };
