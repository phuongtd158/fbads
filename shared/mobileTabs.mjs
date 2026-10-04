// Thanh menu dưới trên mobile: người dùng tự chọn trang nào hiện và theo thứ tự nào (Cài đặt → Giao diện).
// Dùng chung cho giao diện (web/src/lib/nav.js) và test.
export const PAGE_IDS = ['overview', 'schedules', 'rules', 'logs', 'company', 'settings', 'help']
export const DEFAULT_TABS = ['overview', 'schedules', 'rules', 'logs', 'settings']
export const MAX_TABS = 5
export const MIN_TABS = 2
export const LOCKED_TAB = 'settings' // luôn có, để không lỡ tay ẩn mất đường quay lại chỗ chỉnh

// Làm sạch lựa chọn đã lưu: bỏ mã lạ / trùng, tối đa 5 mục, luôn có Cài đặt, ít nhất 2 mục → danh sách mã trang
export function cleanTabs(list) {
    if (!Array.isArray(list)) return [...DEFAULT_TABS]
    const out = []
    for (const id of list) if (PAGE_IDS.includes(id) && !out.includes(id)) out.push(id)
    if (!out.includes(LOCKED_TAB)) {
        if (out.length >= MAX_TABS) out.length = MAX_TABS - 1
        out.push(LOCKED_TAB)
    }
    if (out.length > MAX_TABS) {
        // giữ Cài đặt, bỏ bớt các mục cuối
        const others = out.filter((id) => id !== LOCKED_TAB).slice(0, MAX_TABS - 1)
        const at = Math.min(out.indexOf(LOCKED_TAB), others.length)
        others.splice(at, 0, LOCKED_TAB)
        return others
    }
    if (out.length < MIN_TABS) return [...DEFAULT_TABS]
    return out
}

// Bật/tắt một trang. Không tắt được Cài đặt; đã đủ 5 mục thì không bật thêm. → danh sách mới (hoặc chính danh sách cũ nếu không đổi)
export function toggleTab(list, id) {
    const cur = cleanTabs(list)
    if (!PAGE_IDS.includes(id) || id === LOCKED_TAB) return cur
    if (cur.includes(id)) return cur.length > MIN_TABS ? cur.filter((x) => x !== id) : cur
    return cur.length < MAX_TABS ? [...cur, id] : cur
}

// Đổi chỗ một mục lên (-1) hoặc xuống (+1)
export function moveTab(list, id, dir) {
    const cur = cleanTabs(list)
    const i = cur.indexOf(id), j = i + dir
    if (i < 0 || j < 0 || j >= cur.length) return cur
    const out = [...cur];
    [out[i], out[j]] = [out[j], out[i]]
    return out
}
