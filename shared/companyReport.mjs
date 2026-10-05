// Báo cáo lên hệ thống nội bộ của công ty (form "Nhập báo cáo"): luật dùng chung cho server và giao diện.
// Server: lib/companyReport.js (gom số, lịch các mốc), lib/companyApi.js (gọi API công ty).
// Mỗi báo cáo = 1 Team công ty × 1 ngày × 1 mốc. Mốc 9h chốt số cả ngày hôm qua; 12h, 17h, 22h là lũy kế hôm nay.
// Cả 7 số đều lấy từ Facebook: Đơn hàng = Kết quả, DSO sau VAT = Doanh thu (theo "Loại kết quả" ở Cài đặt → Chung).

export const SLOTS = [9, 12, 17, 22]
export const SLOT_LABEL = {9: '9h · chốt hôm qua', 12: '12h', 17: '17h', 22: '22h · cuối ngày'}
// Chỉ xem (không gửi lên công ty) | Duyệt trước (bấm Gửi mới gửi) | Tự động gửi đến mốc (số bất thường thì dừng cho người xem)
export const MODES = ['preview', 'approve', 'auto']
export const MODE_LABEL = {preview: 'Chỉ xem', approve: 'Duyệt trước khi gửi', auto: 'Tự động gửi'}
export const canSendMode = (mode) => mode === 'approve' || mode === 'auto'
export const DEFAULT_BASE_URL = 'https://mkt.companyos.site'
export const MAX_TEAMS = 30
export const MAX_TEXT = 2000 // ghi chú / vấn đề / hướng xử lý (giới hạn của form công ty)
export const MAX_NUMBER = 1e14 // giới hạn của form công ty
export const MAX_LEAD_MIN = 60 // làm báo cáo sớm hơn mốc tối đa 60 phút
export const MAX_REASON = 500

// Phút trong ngày tool làm báo cáo của mốc: đúng giờ mốc trừ đi số phút làm sớm (Cài đặt → Báo cáo công ty)
export const fireMinute = (slot, leadMin = 0) => Number(slot) * 60 - Math.max(0, Math.min(MAX_LEAD_MIN, Number(leadMin) || 0))

// 7 số của form, đúng tên trường API công ty, đều lấy từ Facebook (sửa tay được trước khi gửi)
export const METRICS = [
    {key: 'spend', label: 'Chi tiêu Ads', money: true, fb: true},
    {key: 'messages', label: 'Tin nhắn', fb: true},
    {key: 'phones', label: 'Số điện thoại', fb: true},
    {key: 'orders', label: 'Số đơn hàng', fb: true},
    {key: 'dso_after', label: 'DSO sau VAT', money: true, fb: true},
    {key: 'impressions', label: 'Lượt hiển thị', fb: true},
    {key: 'clicks', label: 'Lượt nhấp', fb: true},
]
export const METRIC_KEYS = METRICS.map((m) => m.key)
export const TEXT_KEYS = ['notes', 'issue', 'resolution']

const isBlank = (v) => v === '' || v === null || v === undefined
const uniq = (a) => [...new Set(a)]
const addDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 864e5).toISOString().slice(0, 10)

// Ngày của báo cáo = ngày nộp (như web công ty, kể cả mốc 9h). Mốc 9h báo số chốt cả ngày hôm qua (xem dataDate)
export const reportDate = (slot, today) => today
// Số liệu của báo cáo thuộc ngày nào: mốc 9h chốt cả ngày hôm qua (so với ngày báo cáo), các mốc khác là luỹ kế của chính ngày đó
export const dataDate = (slot, date) => (Number(slot) === 9 ? addDays(date, -1) : date)
export const DATE_RULE = 2 // bản báo cáo tạo theo cách tính ngày mới (9h ghi ngày nộp). Bản cũ chưa gửi của mốc 9h bị bỏ.
// Khoảng số liệu Facebook của mốc
export const rangeOf = (slot) => (Number(slot) === 9 ? 'yesterday' : 'today')

// Từ khoá tên chiến dịch: "CT01, hoạt huyết" → ['ct01', 'hoạt huyết']
export const matchWords = (s) => uniq(String(s ?? '').split(/[,;\n]+/).map((w) => w.trim().toLowerCase()).filter(Boolean))

// Chiến dịch thuộc một Team: đúng tài khoản QC (nếu có chọn) VÀ tên chứa một trong các từ khoá (nếu có nhập).
// Team chưa chọn gì thì không có chiến dịch nào (tránh lỡ cộng cả tài khoản vào một Team).
export function teamCampaigns(team, objs) {
    const accs = (team && team.accountIds) || [], words = matchWords(team && team.match)
    if (!accs.length && !words.length) return []
    return (objs || []).filter((o) => o.level === 'campaign'
        && (!accs.length || accs.includes(String(o.accountId || '')))
        && (!words.length || words.some((w) => String(o.name || '').toLowerCase().includes(w))))
}

// Cộng số Facebook của các chiến dịch → các số của form. Tin nhắn = cuộc trò chuyện bắt đầu, SĐT = khách hàng tiềm năng,
// Đơn hàng = kết quả, DSO sau VAT = doanh thu (giá trị chuyển đổi của loại kết quả đã chọn).
export function sumMetrics(camps, data) {
    const t = {spend: 0, messages: 0, phones: 0, orders: 0, dso_after: 0, impressions: 0, clicks: 0}
    for (const o of camps) {
        const m = (data && data[o.id]) || null
        if (!m) continue
        t.spend += Number(m.spend) || 0
        t.messages += Number(m.conversations) || 0
        t.phones += Number(m.leads) || 0
        t.orders += Number(m.results) || 0
        t.dso_after += Number(m.revenue) || 0
        t.impressions += Number(m.impressions) || 0
        t.clicks += Number(m.clicks) || 0
    }
    for (const k of Object.keys(t)) t[k] = Math.round(t[k])
    return t
}

// Chiến dịch nằm trong nhiều Team → số bị cộng hai lần. Trả về [{ name, teams: [tên Team] }]
export function overlaps(teams, objs) {
    const by = new Map()
    for (const t of teams || []) for (const o of teamCampaigns(t, objs)) {
        if (!by.has(o.id)) by.set(o.id, {name: o.name, teams: []})
        by.get(o.id).teams.push(t.code || t.name || t.id)
    }
    return [...by.values()].filter((x) => x.teams.length > 1)
}

const done = (e, value) => {
    const keys = Object.keys(e)
    return {ok: !keys.length, errors: e, first: keys.length ? e[keys[0]] : '', value}
}

// Cài đặt báo cáo công ty. patch: dữ liệu giao diện gửi lên; current: cài đặt đang lưu (để biết đã có mật khẩu chưa).
// Mật khẩu để trống = giữ mật khẩu cũ.
export function validateCompanyConfig(patch = {}, current = {}) {
    const e = {}, v = {}
    const has = (k) => Object.prototype.hasOwnProperty.call(patch, k)
    if (has('enabled')) v.enabled = !!patch.enabled
    if (has('mode')) { if (MODES.includes(patch.mode)) v.mode = patch.mode; else e.mode = 'Chế độ gửi không hợp lệ' }
    if (has('slots')) {
        const s = uniq((Array.isArray(patch.slots) ? patch.slots : []).map(Number))
        if (s.some((x) => !SLOTS.includes(x))) e.slots = 'Mốc báo cáo không hợp lệ'
        else v.slots = SLOTS.filter((x) => s.includes(x))
    }
    if (has('leadMin')) {
        const n = Number(patch.leadMin === '' || patch.leadMin == null ? 0 : patch.leadMin)
        if (!Number.isInteger(n) || n < 0 || n > MAX_LEAD_MIN) e.leadMin = `Số phút làm sớm phải từ 0 đến ${MAX_LEAD_MIN}`
        else v.leadMin = n
    }
    if (has('baseUrl')) {
        const u = String(patch.baseUrl ?? '').trim().replace(/\/+$/, '') || DEFAULT_BASE_URL
        let ok = false
        try { const p = new URL(u); ok = p.protocol === 'https:' && !p.username && !p.password && (p.pathname === '/' || p.pathname === '') } catch { /* sai dạng */ }
        if (ok) v.baseUrl = u; else e.baseUrl = 'Địa chỉ hệ thống phải là https://tên-miền (không kèm đường dẫn)'
    }
    if (has('email')) {
        const m = String(patch.email ?? '').trim()
        if (m && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(m)) e.email = 'Email không hợp lệ'; else v.email = m
    }
    if (has('password')) {
        const p = String(patch.password ?? '')
        if (p.length > 200) e.password = 'Mật khẩu quá dài'; else if (p) v.password = p
    }
    if (has('teams')) {
        const raw = Array.isArray(patch.teams) ? patch.teams : null
        if (!raw) e.teams = 'Danh sách Team không hợp lệ'
        else if (raw.length > MAX_TEAMS) e.teams = `Tối đa ${MAX_TEAMS} Team`
        else {
            const out = []
            for (const [i, t] of raw.entries()) {
                const id = String((t && t.id) ?? '').trim()
                const label = String((t && (t.code || t.name)) || `Team ${i + 1}`).slice(0, 40)
                if (!/^[A-Za-z0-9-]{1,64}$/.test(id)) { e.teams = `${label}: hãy chọn Team của hệ thống công ty`; break }
                if (out.some((x) => x.id === id)) { e.teams = `${label} bị chọn hai lần`; break }
                const accountIds = uniq((Array.isArray(t.accountIds) ? t.accountIds : []).map((a) => String(a ?? '').trim().replace(/^act_/i, '')).filter(Boolean))
                if (accountIds.some((a) => !/^[A-Za-z0-9_]{1,40}$/.test(a))) { e.teams = `${label}: tài khoản quảng cáo không hợp lệ`; break }
                const match = matchWords(t.match).join(', ')
                if (match.length > 300) { e.teams = `${label}: từ khoá tên chiến dịch quá dài`; break }
                if (!accountIds.length && !match) { e.teams = `${label}: hãy chọn tài khoản quảng cáo hoặc nhập từ khoá tên chiến dịch`; break }
                out.push({id, code: String(t.code ?? '').trim().slice(0, 40), name: String(t.name ?? '').trim().slice(0, 120), accountIds, match})
            }
            if (!e.teams) v.teams = out
        }
    }
    const eff = {...current, ...v}
    if (eff.enabled && !e.email && !e.password) {
        if (!eff.email) e.email = 'Cần email đăng nhập hệ thống công ty'
        else if (!eff.password && !eff.has_password) e.password = 'Cần mật khẩu đăng nhập hệ thống công ty'
    }
    if (eff.enabled && !e.teams && !(eff.teams || []).length) e.teams = 'Thêm ít nhất một Team để bật báo cáo tự động'
    if (eff.enabled && !e.slots && !(eff.slots || []).length) e.slots = 'Chọn ít nhất một mốc báo cáo'
    return done(e, v)
}

// Phần Phuong sửa trên một bản báo cáo: 7 số (trống = chưa nhập) và 3 ô chữ
export function validateReportPatch(patch = {}) {
    const e = {}, v = {}
    const has = (k) => Object.prototype.hasOwnProperty.call(patch, k)
    const metrics = {}
    for (const m of METRICS) {
        const src = patch.metrics && Object.prototype.hasOwnProperty.call(patch.metrics, m.key) ? patch.metrics[m.key] : undefined
        if (src === undefined) continue
        if (isBlank(src)) { metrics[m.key] = null; continue }
        const n = Number(src)
        if (!Number.isInteger(n) || n < 0 || n > MAX_NUMBER) { e[m.key] = `${m.label} phải là số nguyên không âm`; continue }
        metrics[m.key] = n
    }
    if (Object.keys(metrics).length) v.metrics = metrics
    for (const k of TEXT_KEYS) if (has(k)) {
        const s = String(patch[k] ?? '')
        if (s.length > MAX_TEXT) e[k] = `Tối đa ${MAX_TEXT} ký tự`; else v[k] = s
    }
    return done(e, v)
}

// Các số còn thiếu (chưa nhập) → không được gửi. Gửi 0 cho Đơn/DSO làm hệ thống công ty tính sai CP/DS.
export const missingMetrics = (r) => METRICS.filter((m) => r && r.metrics && (r.metrics[m.key] === null || r.metrics[m.key] === undefined))

// Lý do KHÔNG tự gửi (chế độ Tự động gửi): số trông bất thường → để người dùng xem rồi gửi tay. Mảng rỗng = gửi được.
export function anomalies(r) {
    const out = []
    const miss = missingMetrics(r)
    if (miss.length) out.push(`Còn thiếu ${miss.map((m) => m.label).join(', ')}`)
    if (!r.campaigns || !r.campaigns.length) out.push('Team không khớp chiến dịch nào (kiểm tra cấu hình Team)')
    if (r.metrics && Number(r.metrics.orders) > 0 && !(Number(r.metrics.dso_after) > 0)) out.push('Có đơn nhưng doanh thu bằng 0 (tài khoản chưa báo giá trị đơn về Facebook)')
    return out
}

// Body của POST /api/reports
export function payloadOf(r) {
    const metrics = {}
    for (const k of METRIC_KEYS) metrics[k] = Number(r.metrics[k])
    return {team_id: r.teamId, date: r.date, slot: Number(r.slot), metrics, notes: r.notes || '', issue: r.issue || '', resolution: r.resolution || ''}
}

// Body cập nhật báo cáo đã có trên công ty: như gửi mới, kèm lần sửa hiện tại và lý do (giống nút "Lưu & tính lại KPI" của web công ty)
export function updatePayloadOf(r, revision, reason) {
    return {...payloadOf(r), revision, reason: String(reason || '').trim()}
}

export function validateReason(reason) {
    const s = String(reason ?? '').trim()
    if (!s) return 'Nhập lý do cập nhật (công ty bắt buộc)'
    if (s.length > MAX_REASON) return `Lý do tối đa ${MAX_REASON} ký tự`
    return ''
}

// Báo cáo trên hệ thống công ty → phần tool lưu để hiển thị / so sánh
export function remoteOf(x, fallbackMetrics) {
    if (!x) return null
    const metrics = {}
    const src = x.metrics || fallbackMetrics || {}
    for (const k of METRIC_KEYS) metrics[k] = src[k] == null ? null : Number(src[k])
    return {
        id: String(x.id || ''), status: String(x.status || ''), revision: x.revision == null ? null : Number(x.revision),
        locked: !!x.locked, metrics, notes: x.notes || '', issue: x.issue || '', resolution: x.resolution || '',
        updatedAt: x.updated_at || '', syncedAt: new Date().toISOString(),
    }
}

// Số trên tool khác số đã nộp lên công ty → danh sách các ô khác (để hiện và bật nút Cập nhật)
export function diffRemote(r) {
    if (!r || !r.remote) return []
    const out = METRICS.filter((m) => r.metrics[m.key] != null && Number(r.metrics[m.key]) !== Number(r.remote.metrics[m.key])).map((m) => m.key)
    for (const k of TEXT_KEYS) if ((r[k] || '') !== (r.remote[k] || '')) out.push(k)
    return out
}

// "12 3.500.000" / "12 3500000" / "đơn 12 dso 3,5tr" → { orders, dso_after } hoặc null. Dùng cho trả lời trên Telegram.
export function parseOrdersDso(text) {
    const s = String(text ?? '').toLowerCase().replace(/(\d)[.,](?=\d{3}(\D|$))/g, '$1')
    const nums = []
    const re = /(\d+(?:[.,]\d+)?)\s*(tr|triệu|m|k|nghìn|ngàn)?/g
    let m
    while ((m = re.exec(s))) {
        let n = Number(m[1].replace(',', '.'))
        if (m[2] === 'tr' || m[2] === 'triệu' || m[2] === 'm') n *= 1e6
        else if (m[2]) n *= 1e3
        nums.push(Math.round(n))
    }
    if (nums.length !== 2 || !Number.isInteger(nums[0])) return null
    if (nums.some((n) => !Number.isFinite(n) || n < 0 || n > MAX_NUMBER)) return null
    return {orders: nums[0], dso_after: nums[1]}
}
