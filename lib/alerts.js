// Cảnh báo bất thường (30 phút kiểm tra một lần, mỗi sự việc chỉ báo một lần), gửi Telegram và ghi Nhật ký:
//  - tài khoản quảng cáo có vấn đề (bị vô hiệu hoá, nợ thanh toán…) và khi hoạt động lại
//  - quảng cáo bị từ chối (báo mỗi quảng cáo một lần; được duyệt rồi bị từ chối lại thì báo lại)
//  - chi tiêu hôm nay tính đến giờ này cao hơn X% so với cùng giờ hôm qua (mỗi tài khoản tối đa 1 lần/ngày)
// Chỉ chạy khi kết nối Facebook thật (dữ liệu giả không có các thông tin này).
const store = require('./store');
const fb = require('./fb');
const notify = require('./notify');

const EVERY_MS = 30 * 60e3;
const MAX_LINES = 10; // tin Telegram liệt kê tối đa 10 quảng cáo, còn lại ghi "và N quảng cáo khác"

const { esc } = notify;
const money = (n) => Math.round(n).toLocaleString('vi-VN');
const modeNow = () => { const s = store.get().settings; return s.mock ? 'mock' : s.dryRun ? 'dry' : 'live'; };

async function alert(name, detail, text, ok = false) {
  store.log({ kind: 'system', source: 'Cảnh báo', name, detail, ok, mode: modeNow() });
  await notify.telegram(text);
}

// Chi tiêu hôm nay so với cùng giờ hôm qua. Giờ hiện tại = giờ muộn nhất đã có chi tiêu hôm nay (theo múi giờ của tài khoản),
// hôm qua tính tới hết giờ đó (tính dư một phần giờ hiện tại, nên chỉ báo khi tăng thật).
function spikeOf(today, yesterday, pct, minSpend) {
  if (!today.length) return null;
  const hour = Math.max(...today.map((r) => r.hour));
  const now = today.reduce((a, r) => a + r.spend, 0);
  const before = yesterday.filter((r) => r.hour <= hour).reduce((a, r) => a + r.spend, 0);
  if (now < minSpend || before <= 0 || now <= before * (1 + pct / 100)) return null;
  return { hour, now, before, up: Math.round((now / before - 1) * 100) };
}

async function checkAccount(id, st, s, now) {
  const h = await fb.accountHealth(id);
  const nm = h.name, cur = (fb.objectsMeta().accounts.find((a) => a.id === id) || {}).currency || '';

  if (s.alertAccount !== false) {
    const prev = st.acc[id];
    if (!h.active && prev !== h.status) {
      await alert(nm, `Tài khoản quảng cáo đang ở trạng thái: ${h.statusText}.`, `🚫 <b>Tài khoản quảng cáo có vấn đề</b>\n${esc(nm)} (${esc(id)}): <b>${esc(h.statusText)}</b>.\nQuảng cáo trong tài khoản này có thể đã ngừng chạy. Kiểm tra trong Trình quản lý quảng cáo.`);
    } else if (h.active && prev != null && prev !== 1) {
      await alert(nm, 'Tài khoản quảng cáo đã hoạt động lại.', `✅ <b>Tài khoản quảng cáo đã hoạt động lại</b>\n${esc(nm)} (${esc(id)}).`, true);
    }
  }
  st.acc[id] = h.status;

  if (s.alertDisapproved !== false) {
    const ads = await fb.disapprovedAds(id);
    const seen = new Set(st.ads[id] || []);
    const fresh = ads.filter((a) => !seen.has(a.id));
    st.ads[id] = ads.map((a) => a.id); // quảng cáo được duyệt lại thì bỏ khỏi danh sách → bị từ chối lần nữa sẽ báo lại
    if (fresh.length) {
      const line = (a) => `• ${esc(a.name)} (camp ${esc(a.campaign)}${a.adset ? `, nhóm ${esc(a.adset)}` : ''})${a.reason ? `: ${esc(a.reason)}` : ''}`;
      const more = fresh.length > MAX_LINES ? `\n…và ${fresh.length - MAX_LINES} quảng cáo khác` : '';
      await alert(nm, `${fresh.length} quảng cáo bị từ chối: ${fresh.slice(0, 5).map((a) => a.name).join(', ')}${fresh.length > 5 ? '…' : ''}`,
        `⛔ <b>${fresh.length} quảng cáo bị từ chối</b> ở ${esc(nm)}\n${fresh.slice(0, MAX_LINES).map(line).join('\n')}${more}`);
    }
  }

  if (s.alertSpike !== false && st.spike[id] !== now.date) {
    const today = await fb.hourlySpend(id, 'today');
    if (today.length) {
      const sp = spikeOf(today, await fb.hourlySpend(id, 'yesterday'), Number(s.spikePct) || 50, Number(s.spikeMinSpend) || 0);
      if (sp) {
        st.spike[id] = now.date;
        const until = `${String(sp.hour + 1).padStart(2, '0')}:00`;
        await alert(nm, `Chi tiêu hôm nay tới ${until} là ${money(sp.now)} ${cur}, cao hơn ${sp.up}% so với cùng giờ hôm qua (${money(sp.before)} ${cur}).`,
          `📈 <b>Chi tiêu tăng vọt</b> ở ${esc(nm)}\nHôm nay tới ${until}: <b>${money(sp.now)} ${esc(cur)}</b>, cao hơn ${sp.up}% so với cùng giờ hôm qua (${money(sp.before)} ${esc(cur)}).`);
      }
    }
  }
}

let lastAt = 0; // không lưu vào dữ liệu: khởi động lại thì kiểm tra ngay lượt đầu
async function tickAlerts(now) {
  const s = store.get().settings;
  if (s.mock || !s.accessToken || (s.alertAccount === false && s.alertDisapproved === false && s.alertSpike === false)) return;
  if (Date.now() - lastAt < EVERY_MS) return;
  if (fb.objectsMeta().blockedUntil) return; // Facebook đang giới hạn số lần gọi: để lượt sau
  lastAt = Date.now();
  const d = store.get();
  const st = d.state.alerts = d.state.alerts || {};
  st.acc = st.acc || {}; st.ads = st.ads || {}; st.spike = st.spike || {};
  for (const id of fb.accountIds()) {
    try { await checkAccount(id, st, s, now); } catch (e) {
      if (fb.isRateLimited(e)) break; // các tài khoản còn lại để lượt sau
      console.error(`Lỗi kiểm tra cảnh báo (tài khoản ${id}):`, e.message);
    }
  }
  store.save();
}

const reset = () => { lastAt = 0; };

module.exports = { tickAlerts, spikeOf, reset, EVERY_MS };
