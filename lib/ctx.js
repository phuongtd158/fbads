// Tài khoản đang làm việc của đoạn code hiện tại. Mỗi tài khoản có bộ dữ liệu riêng (token, lịch, rule, nhật ký, Telegram),
// store.get() và các bộ nhớ đệm (lib/fb.js…) dựa vào đây để chỉ thấy dữ liệu của tài khoản đó.
// Nơi đặt: middleware đăng nhập (mỗi request), vòng tự động (lần lượt từng tài khoản), vòng hỏi tin Telegram, callback Facebook.
// AsyncLocalStorage đi theo cả await/setTimeout nên code bên trong không phải truyền tay.
const { AsyncLocalStorage } = require('async_hooks');

const ADMIN = 1; // tài khoản có sẵn từ bản 1 người dùng: dữ liệu cũ (data.json) thuộc về tài khoản này
const als = new AsyncLocalStorage();

const current = () => als.getStore();
const run = (userId, fn) => als.run(userId, fn);

// Trạng thái trong bộ nhớ riêng cho từng tài khoản (cache Facebook, bộ đếm…): const S = perUser(() => ({ cache: null }))
// rồi dùng S().cache. `who` trả tài khoản hiện tại (store.perUser truyền sẵn store.userId).
function perUser(init, who) {
  const m = new Map();
  const S = () => {
    const id = who();
    let v = m.get(id);
    if (!v) m.set(id, (v = init()));
    return v;
  };
  S.reset = () => m.clear();
  S.drop = (id) => m.delete(id);
  return S;
}

module.exports = { ADMIN, current, run, perUser };
