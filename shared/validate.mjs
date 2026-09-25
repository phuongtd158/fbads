// Validate nghiệp vụ dùng chung cho server (Node) và giao diện (Vue).
// Server là nơi kiểm tra cứng (không tin dữ liệu từ trình duyệt); giao diện dùng cùng luật để báo lỗi ngay khi nhập.
// Mỗi hàm trả về { ok, errors: { field: message }, warnings: [message], value, first }.

export const LIMITS = {
    nameMax: 80,
    budgetMax: 1e10,
    scheduleSetPctMax: 300, // lịch: tăng tối đa +300%
    rulePctIncreaseMax: 100, // rule: tăng tối đa +100% mỗi lần
    rulePctDecreaseMax: 90, // giảm tối đa -90% (không cho về 0)
    bigPctWarn: 30, // thay đổi lớn hơn mức này thì cảnh báo
    cooldownMax: 168,
    intervalMin: 5,
    intervalMax: 1440,
    capPctMin: 5, // giới hạn tổng thay đổi ngân sách mỗi ngày do rule (%)
    capPctMax: 100,
    scheduleTimesMax: 24, // một lịch chạy tối đa 24 lần mỗi ngày
    accountsMax: 20, // số tài khoản quảng cáo quản lý cùng lúc
}

// Các giờ chạy của một lịch. Lịch cũ chỉ có `time`, lịch mới có `times` (nhiều mốc trong ngày).
export const scheduleTimes = (s) => (Array.isArray(s && s.times) ? s.times.map(String) : s && s.time ? [String(s.time)] : [])
const METRICS = ['cpa', 'roas', 'spend', 'results', 'ctr', 'cpc', 'cpm']
export const MAX_CONDITIONS = 5 // một rule tối đa 5 điều kiện
export const TARGET_METRICS = ['cpa', 'roas'] // số liệu so được với "mục tiêu" đặt theo tài khoản (Cài đặt → Mục tiêu)
// Các điều kiện của rule. Rule cũ chỉ có metric/op/value ở ngoài cùng → coi như 1 điều kiện. Điều kiện đầu luôn được chép ra 3 trường cũ.
export const conditionsOf = (r) => (r && Array.isArray(r.conditions) && r.conditions.length ? r.conditions : r && r.metric ? [{metric: r.metric, op: r.op, value: r.value}] : [])
// Khoảng thời gian tính số liệu cho rule (khớp date_preset của Facebook Insights)
export const RANGES = ['today', 'yesterday', 'last_3d', 'last_7d']
export const RANGE_LABEL = {today: 'hôm nay', yesterday: 'hôm qua', last_3d: '3 ngày gần nhất', last_7d: '7 ngày gần nhất'}
const METRIC_LABEL = {cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Số kết quả', ctr: 'CTR', cpc: 'CPC', cpm: 'CPM'}
const isBlank = (v) => v === '' || v === null || v === undefined
const num = (v) => (isBlank(v) ? NaN : Number(v))
const uniq = (a) => [...new Set(a)]
const money = (n) => Math.round(n).toLocaleString('vi-VN')

export const isTime = (s) => typeof s === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(s)

export function isTimezone(tz) {
    if (typeof tz !== 'string' || !tz.trim()) return false
    try {
        new Intl.DateTimeFormat('en-US', {timeZone: tz});
        return true
    } catch {
        return false
    }
}

const done = (errors, warnings, value) => ({
    ok: Object.keys(errors).length === 0,
    errors,
    warnings,
    value,
    first: Object.values(errors)[0] || ''
})

function nameOf(objs, id) {
    const o = objs && objs.find((x) => x.id === id)
    return o ? o.name : id
}

const inter = (a, b) => a.filter((x) => b.includes(x))
const sameSet = (a, b) => a.length === b.length && a.every((x) => b.includes(x))

/* ------------------------------------------------------------------ Lịch */
// Chọn mục theo điều kiện (lịch "Theo điều kiện"): { level, op, x, y, name, onlyRunning } — cách lọc nằm ở shared/bulk.mjs
export const FILTER_OPS = ['any', 'lt', 'lte', 'gt', 'gte', 'between']
export function checkFilter(f = {}) {
    const e = {}
    const level = f.level === 'adset' ? 'adset' : 'campaign'
    const op = FILTER_OPS.includes(f.op) ? f.op : 'any'
    const x = num(f.x), y = num(f.y)
    if (op !== 'any' && !(x >= 0)) e.x = 'Nhập mức ngân sách để so sánh'
    if (op === 'between' && !(y >= 0)) e.y = 'Nhập mức thứ hai của khoảng'
    const name = String(f.name ?? '').trim()
    if (name.length > 100) e.name = 'Cụm tên tối đa 100 ký tự'
    const account = String(f.account ?? '').trim().slice(0, 40) // '' = mọi tài khoản quảng cáo
    return { errors: e, value: { level, op, ...(op !== 'any' ? { x } : {}), ...(op === 'between' ? { y } : {}), name, onlyRunning: !!f.onlyRunning, ...(account ? { account } : {}) } }
}

export function validateSchedule(input = {}, ctx = {}) {
    const {objs = null, schedules = []} = ctx
    const e = {}, w = []
    const name = String(input.name ?? '').trim()
    if (name.length > LIMITS.nameMax) e.name = `Tên tối đa ${LIMITS.nameMax} ký tự`

    const action = input.action
    if (!['on', 'off', 'budget'].includes(action)) e.action = 'Hành động không hợp lệ'

    const rawTimes = scheduleTimes(input)
    const times = uniq(rawTimes).sort()
    if (!rawTimes.length) e.time = 'Hãy thêm ít nhất 1 giờ chạy'
    else if (rawTimes.some((t) => !isTime(t))) e.time = 'Giờ chạy không hợp lệ (dạng HH:MM, ví dụ 06:00)'
    else if (times.length > LIMITS.scheduleTimesMax) e.time = `Tối đa ${LIMITS.scheduleTimesMax} giờ chạy mỗi ngày`

    const days = uniq((Array.isArray(input.days) ? input.days : []).map(Number)).filter((d) => Number.isInteger(d) && d >= 0 && d <= 6).sort()
    if (!days.length) e.days = 'Hãy chọn ít nhất 1 ngày trong tuần'

    // Áp dụng cho: danh sách cố định (list) hoặc theo điều kiện (filter) — lọc lại mỗi lần chạy nên camp mới cũng được áp dụng
    const targetMode = input.targetMode === 'filter' ? 'filter' : 'list'
    let targets = [], filter = null, exclude = []
    if (targetMode === 'filter') {
        const fr = checkFilter(input.filter)
        filter = fr.value
        if (Object.keys(fr.errors).length) e.filter = Object.values(fr.errors)[0]
        // bỏ tích trong danh sách khớp = loại trừ mục đó (mục mới khớp về sau vẫn được áp dụng)
        exclude = uniq((Array.isArray(input.exclude) ? input.exclude : []).map(String)).slice(0, 2000)
        if (!e.filter && filter.op === 'any' && !filter.name && !filter.onlyRunning) w.push(`Điều kiện đang khớp ${filter.level === 'adset' ? 'mọi nhóm QC' : 'mọi chiến dịch'} trên tài khoản.`)
    } else {
        targets = uniq((Array.isArray(input.targets) ? input.targets : []).map(String))
        if (!targets.length) e.targets = 'Hãy chọn ít nhất 1 chiến dịch'
        else if (objs) {
            const unknown = targets.filter((id) => !objs.some((o) => o.id === id))
            if (unknown.length) e.targets = `Có mục không còn tồn tại trên tài khoản: ${unknown.slice(0, 3).join(', ')}. Hãy bỏ chọn chúng.`
        }
    }

    let mode = ['set', 'add'].includes(input.mode) ? input.mode : 'percent'
    let value = num(input.value)
    if (action === 'budget') {
        if (!Number.isFinite(value)) e.value = 'Nhập giá trị đổi ngân sách'
        else if (mode === 'add') {
            if (value === 0) e.value = 'Số tiền cộng/trừ phải khác 0'
            else if (Math.abs(value) > LIMITS.budgetMax) e.value = 'Số tiền quá lớn, hãy kiểm tra lại số 0'
            else value = Math.round(value)
            if (!e.value && !e.time && times.length > 1) w.push(`Ngân sách sẽ ${value > 0 ? 'cộng' : 'trừ'} ${money(Math.abs(value))} ${times.length} lần mỗi ngày và cộng dồn.`)
        } else if (mode === 'percent') {
            if (value === 0) e.value = 'Phần trăm phải khác 0'
            else if (value <= -100) e.value = 'Không thể giảm từ 100% trở lên (ngân sách sẽ về 0). Tối đa -90%.'
            else if (value < -LIMITS.rulePctDecreaseMax) e.value = `Giảm tối đa ${LIMITS.rulePctDecreaseMax}% mỗi lần`
            else if (value > LIMITS.scheduleSetPctMax) e.value = `Tăng tối đa ${LIMITS.scheduleSetPctMax}% mỗi lần`
            else if (Math.abs(value) >= 50) w.push(`${value > 0 ? 'Tăng' : 'Giảm'} ${Math.abs(value)}% một lần là thay đổi lớn, Facebook có thể học lại từ đầu.`)
            if (!e.value && !e.time && times.length > 1) w.push(`Ngân sách sẽ ${value > 0 ? 'tăng' : 'giảm'} ${Math.abs(value)}% ${times.length} lần mỗi ngày và cộng dồn (lần sau tính trên ngân sách đã đổi). Nên đặt trần/sàn ngân sách hoặc dùng số tiền cố định.`)
        } else {
            if (value <= 0) e.value = 'Ngân sách phải lớn hơn 0'
            else if (value > LIMITS.budgetMax) e.value = 'Ngân sách quá lớn, hãy kiểm tra lại số 0'
            else value = Math.round(value)
        }
        if (targetMode === 'list' && !e.targets && objs) {
            const cbo = targets.filter((id) => {
                const o = objs.find((x) => x.id === id);
                return o && o.dailyBudget == null
            })
            if (cbo.length) e.targets = `Các mục sau không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO): ${cbo.slice(0, 3).map((id) => nameOf(objs, id)).join(', ')}${cbo.length > 3 ? '…' : ''}. Hãy bỏ chúng hoặc chọn mục có ngân sách.`
        }
    }

    // Xung đột với các lịch đang bật
    const enabled = input.enabled !== false
    // (lịch theo điều kiện không có danh sách cố định nên không kiểm tra trùng/ngược được trước)
    if (enabled && targetMode === 'list' && !e.time && !e.days && !e.targets && !e.action) {
        for (const o of schedules) {
            if (o.id && o.id === input.id) continue
            const oTimes = scheduleTimes(o), sharedTimes = inter(times, oTimes)
            if (o.enabled === false || !sharedTimes.length) continue
            const at = sharedTimes.join(', ')
            const sharedDays = inter(days, (o.days || []).map(Number)), sharedTargets = inter(targets, o.targets || [])
            if (!sharedDays.length || !sharedTargets.length) continue
            const names = sharedTargets.slice(0, 2).map((id) => nameOf(objs, id)).join(', ')
            const opposite = (action === 'on' && o.action === 'off') || (action === 'off' && o.action === 'on')
            const identical = action === o.action && (action !== 'budget' || (mode === (['set', 'add'].includes(o.mode) ? o.mode : 'percent') && Number(o.value) === value)) && sameSet(times, oTimes) && sameSet(days, (o.days || []).map(Number)) && sameSet(targets, o.targets || [])
            if (opposite) {
                e.conflict = `Xung đột với lịch “${o.name}”: cùng lúc ${at} nhưng làm điều ngược lại (${o.action === 'on' ? 'bật' : 'tắt'}) cho ${names}.`;
                break
            }
            if (identical) {
                e.conflict = `Đã có lịch giống hệt: “${o.name}”.`;
                break
            }
            if (action === 'budget' && o.action === 'budget') w.push(`Lịch “${o.name}” cũng đổi ngân sách của ${names} lúc ${at}, kết quả có thể khó đoán.`)
        }
    }

    return done(e, w, {
        ...(input.id ? {id: String(input.id)} : {}),
        name: name || 'Lịch mới',
        action,
        time: times[0], // giữ cho dữ liệu/giao diện cũ: giờ chạy sớm nhất
        times,
        days,
        targetMode,
        targets,
        ...(filter ? {filter} : {}),
        ...(exclude.length ? {exclude} : {}),
        mode,
        value: Number.isFinite(value) ? value : 0,
        enabled,
        ...(Number(input.max) > 0 ? {max: Number(input.max)} : {}), ...(Number(input.min) > 0 ? {min: Number(input.min)} : {}),
    })
}

/* ------------------------------------------------------------------ Rule */
function condRange(r) {
    return r.op === '>' ? [Number(r.value), Infinity] : [-Infinity, Number(r.value)]
}

function condOverlap(a, b) {
    if (a.op === b.op) return true
    const [lo, hi] = a.op === '>' ? [Number(a.value), Number(b.value)] : [Number(b.value), Number(a.value)]
    return lo < hi
}

export function validateRule(input = {}, ctx = {}) {
    const {objs = null, rules = [], accountTargets = null, accounts = null} = ctx
    const e = {}, w = []
    const name = String(input.name ?? '').trim()
    if (name.length > LIMITS.nameMax) e.name = `Tên tối đa ${LIMITS.nameMax} ký tự`

    // ----- Điều kiện (1..5), gộp bằng VÀ / HOẶC. Lỗi của điều kiện thứ i nằm ở khoá `c{i}.{trường}`; điều kiện đầu còn ghi ra khoá cũ (metric/op/value).
    const rawConds = Array.isArray(input.conditions) && input.conditions.length ? input.conditions : [{metric: input.metric, op: input.op, value: input.value}]
    if (rawConds.length > MAX_CONDITIONS) e.conditions = `Tối đa ${MAX_CONDITIONS} điều kiện cho mỗi rule`
    const put = (i, field, msg) => { e[`c${i}.${field}`] = msg; if (i === 0) e[field] = msg }
    const conds = rawConds.slice(0, MAX_CONDITIONS).map((c0, i) => {
        const c = c0 || {}
        const metric = c.metric
        if (!METRICS.includes(metric)) put(i, 'metric', 'Số liệu không hợp lệ')
        const op = c.op === '<' ? '<' : c.op === '>' ? '>' : null
        if (!op) put(i, 'op', 'Phép so sánh không hợp lệ')
        if (c.vs === 'target') { // so với mục tiêu của từng tài khoản: ngưỡng = mục tiêu × factor%
            if (!TARGET_METRICS.includes(metric)) put(i, 'metric', 'Chỉ CPA và ROAS so được với mục tiêu')
            const factor = isBlank(c.factor) ? 100 : num(c.factor)
            if (!Number.isFinite(factor) || factor <= 0 || factor > 1000) put(i, 'value', 'Phần trăm so với mục tiêu phải từ 1 đến 1000')
            return {metric, op, vs: 'target', factor: Number.isFinite(factor) ? factor : 100, value: 0}
        }
        const value = num(c.value)
        if (!Number.isFinite(value)) put(i, 'value', 'Nhập ngưỡng so sánh')
        else if (value < 0) put(i, 'value', 'Ngưỡng không được âm')
        else if (['cpa', 'spend', 'cpc', 'cpm'].includes(metric) && op === '>' && value <= 0) put(i, 'value', `Ngưỡng ${METRIC_LABEL[metric]} phải lớn hơn 0, nếu không rule sẽ khớp với mọi camp.`)
        else if (metric === 'roas' && value > 100) put(i, 'value', 'ROAS lớn hơn 100 là bất thường, hãy kiểm tra lại')
        else if (metric === 'ctr' && value > 100) put(i, 'value', 'CTR là phần trăm, tối đa 100')
        return {metric, op, value: Number.isFinite(value) ? value : 0}
    })
    const first = conds[0] || {}
    const metric = first.metric, op = first.op, value = first.value
    const match = input.match === 'any' ? 'any' : 'all'
    // Điều kiện tự mâu thuẫn (VÀ): cùng một số liệu vừa phải lớn hơn a vừa nhỏ hơn b mà a ≥ b thì không bao giờ khớp
    if (match === 'all' && !Object.keys(e).length) {
        for (const m of uniq(conds.filter((c) => c.vs !== 'target').map((c) => c.metric))) {
            const same = conds.filter((c) => c.metric === m && c.vs !== 'target')
            const lo = Math.max(...same.filter((c) => c.op === '>').map((c) => c.value), -Infinity)
            const hi = Math.min(...same.filter((c) => c.op === '<').map((c) => c.value), Infinity)
            if (lo >= hi) { w.push(`Các điều kiện về ${METRIC_LABEL[m]} mâu thuẫn nhau (vừa lớn hơn ${lo}, vừa nhỏ hơn ${hi}) nên rule sẽ không bao giờ khớp.`); break }
        }
    }

    const range = isBlank(input.range) ? 'today' : RANGES.includes(input.range) ? input.range : null
    if (!range) e.range = 'Khoảng thời gian không hợp lệ'

    const hasDataMetric = conds.some((c) => c.metric && c.metric !== 'spend')
    const minSpend = isBlank(input.minSpend) ? 0 : num(input.minSpend)
    if (!Number.isFinite(minSpend) || minSpend < 0) e.minSpend = 'Chi tiêu tối thiểu phải là số không âm'
    else if (hasDataMetric && minSpend <= 0) e.minSpend = 'Cần đặt chi tiêu tối thiểu lớn hơn 0 để không quyết định khi camp mới chạy, chưa đủ dữ liệu.'

    const action = input.action
    if (!['pause', 'increase', 'decrease', 'notify'].includes(action)) e.action = 'Hành động không hợp lệ'
    if (range === 'today' && hasDataMetric && (action === 'pause' || action === 'decrease')) w.push('Rule đang chỉ dựa trên số liệu hôm nay. Chuyển đổi thường về trễ nên dễ tắt/giảm oan; nên dùng “3 ngày gần nhất” hoặc dài hơn.')
    let pct = num(input.pct)
    if (action === 'increase' || action === 'decrease') {
        if (!Number.isFinite(pct) || pct <= 0) e.pct = 'Nhập % thay đổi lớn hơn 0'
        else if (action === 'decrease' && pct > LIMITS.rulePctDecreaseMax) e.pct = `Giảm tối đa ${LIMITS.rulePctDecreaseMax}% mỗi lần (giảm 100% là đưa ngân sách về 0)`
        else if (action === 'increase' && pct > LIMITS.rulePctIncreaseMax) e.pct = `Tăng tối đa ${LIMITS.rulePctIncreaseMax}% mỗi lần`
        else if (pct > LIMITS.bigPctWarn) w.push(`${action === 'increase' ? 'Tăng' : 'Giảm'} ${pct}% mỗi lần là khá lớn, Facebook có thể học lại từ đầu. Nên khoảng 20%.`)
    } else pct = 0

    const maxBudget = isBlank(input.maxBudget) ? 0 : num(input.maxBudget)
    const minBudget = isBlank(input.minBudget) ? 0 : num(input.minBudget)
    if (!Number.isFinite(maxBudget) || maxBudget < 0) e.maxBudget = 'Trần ngân sách phải là số không âm'
    if (!Number.isFinite(minBudget) || minBudget < 0) e.minBudget = 'Sàn ngân sách phải là số không âm'
    if (!e.maxBudget && !e.minBudget && maxBudget > 0 && minBudget > 0 && maxBudget < minBudget) e.maxBudget = 'Trần ngân sách phải lớn hơn hoặc bằng sàn'
    if (action === 'increase' && !e.maxBudget && maxBudget <= 0) w.push('Chưa đặt trần ngân sách: ngân sách có thể tăng mãi qua nhiều ngày. Nên đặt trần.')
    if (action === 'decrease' && !e.minBudget && minBudget <= 0) w.push('Chưa đặt sàn ngân sách: ngân sách có thể giảm rất thấp qua nhiều lần. Nên đặt sàn.')

    const cooldown = isBlank(input.cooldownHours) ? 0 : num(input.cooldownHours)
    if (!Number.isFinite(cooldown) || cooldown < 0 || cooldown > LIMITS.cooldownMax) e.cooldownHours = `Thời gian nghỉ từ 0 đến ${LIMITS.cooldownMax} giờ`
    else if ((action === 'increase' || action === 'decrease') && cooldown < 1) e.cooldownHours = 'Rule đổi ngân sách cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không ngân sách sẽ thay đổi liên tục mỗi lần kiểm tra.'
    else if (action === 'notify' && cooldown < 1) e.cooldownHours = 'Rule chỉ thông báo cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không bạn sẽ nhận thông báo lặp lại mỗi lần kiểm tra.'

    const from = input.from || '', to = input.to || ''
    if (from || to) {
        if (!from || !to) e.window = 'Hãy nhập cả giờ bắt đầu và giờ kết thúc (hoặc để trống cả hai)'
        else if (!isTime(from) || !isTime(to)) e.window = 'Khung giờ không hợp lệ'
        else if (from >= to) e.window = 'Giờ bắt đầu phải nhỏ hơn giờ kết thúc (chưa hỗ trợ khung giờ qua đêm)'
    }

    // Cấp áp dụng: chiến dịch (mặc định, rule cũ) hoặc nhóm QC
    const level = input.level === 'adset' ? 'adset' : 'campaign'
    const unit = level === 'adset' ? 'nhóm QC' : 'camp'
    const allActive = input.allActive !== false
    const targets = uniq((Array.isArray(input.targets) ? input.targets : []).map(String))
    if (!allActive) {
        if (!targets.length) e.targets = `Hãy chọn ít nhất 1 ${unit} áp dụng`
        else if (objs) {
            const unknown = targets.filter((id) => !objs.some((o) => o.id === id))
            const other = targets.filter((id) => objs.some((o) => o.id === id && o.level && o.level !== level))
            if (unknown.length) e.targets = `Có mục không còn tồn tại trên tài khoản: ${unknown.slice(0, 3).join(', ')}.`
            else if (other.length) e.targets = `Có ${other.length} mục không phải ${level === 'adset' ? 'nhóm QC' : 'chiến dịch'}, hãy chọn lại.`
        }
    }

    // Phạm vi tài khoản (chỉ khi áp dụng cho "tất cả camp đang chạy"): trống = mọi tài khoản đang quản lý
    const accountIds = allActive ? uniq((Array.isArray(input.accountIds) ? input.accountIds : []).map((x) => String(x).trim()).filter(Boolean)) : []
    if (accountIds.length > LIMITS.accountsMax) e.accountIds = `Tối đa ${LIMITS.accountsMax} tài khoản`
    if (accounts && accountIds.length) {
        const gone = accountIds.filter((id) => !accounts.some((a) => a.id === id))
        if (gone.length) w.push(`Rule đang giới hạn theo tài khoản ${gone.slice(0, 3).join(', ')} nhưng tài khoản này không còn được quản lý, nên không ${unit} nào được xét.`)
    }

    // Rule đổi ngân sách: ngân sách chỉ nằm ở 1 cấp (CBO: chiến dịch, ABO: nhóm QC) → mục không có ngân sách riêng bị bỏ qua
    if ((action === 'increase' || action === 'decrease') && objs && !e.targets) {
        const pool = allActive
            ? objs.filter((o) => o.level === level && o.effective === 'ACTIVE' && (!accountIds.length || accountIds.includes(o.accountId)))
            : objs.filter((o) => targets.includes(o.id))
        const none = pool.filter((o) => o.dailyBudget == null).length
        const why = level === 'adset' ? 'nằm trong chiến dịch CBO (ngân sách đặt ở chiến dịch)' : 'là chiến dịch ABO (ngân sách đặt ở từng nhóm QC)'
        if (pool.length && none === pool.length) w.push(`Không ${unit} nào ${allActive ? 'đang chạy ' : 'đã chọn '}có ngân sách riêng (đều ${why}), nên rule này sẽ không đổi được ngân sách. ${level === 'adset' ? 'Hãy dùng rule cấp chiến dịch.' : 'Hãy dùng rule cấp nhóm QC.'}`)
        else if (none) w.push(`${none} ${unit} không có ngân sách riêng (${why}) sẽ bị bỏ qua.`)
    }
    // Điều kiện so với mục tiêu: mỗi tài khoản trong phạm vi phải có mục tiêu tương ứng, không thì rule bỏ qua camp của tài khoản đó
    const tcs = conds.filter((c) => c.vs === 'target')
    if (tcs.length && accountTargets && accounts) {
        const inScope = accountIds.length ? accountIds
            : allActive ? accounts.map((a) => a.id)
                : uniq(targets.map((id) => ((objs || []).find((o) => o.id === id) || {}).accountId).filter(Boolean))
        for (const id of inScope) {
            const missing = uniq(tcs.map((c) => c.metric)).filter((m) => !(Number((accountTargets[id] || {})[m]) > 0))
            if (!missing.length) continue
            const nm = (accounts.find((a) => a.id === id) || {}).name || id
            w.push(`Tài khoản “${nm}” chưa đặt mục tiêu ${missing.map((m) => METRIC_LABEL[m]).join(', ')}: rule sẽ bỏ qua camp của tài khoản này (đặt ở Cài đặt → Mục tiêu).`)
        }
    }

    // Cảnh báo mâu thuẫn với rule khác đang bật (chỉ so được khi cả hai rule chỉ có 1 điều kiện số cụ thể)
    const enabled = input.enabled !== false
    const simple = (r) => conditionsOf(r).length === 1 && conditionsOf(r)[0].vs !== 'target'
    if (enabled && !e.metric && !e.op && !e.value && !e.action && conds.length === 1 && conds[0].vs !== 'target') {
        const kind = (a) => (a === 'increase' ? 'up' : 'down') // pause & decrease đều là "giảm chi"
        for (const o of rules) {
            if (o.id && o.id === input.id) continue
            if (o.enabled === false || !simple(o) || o.metric !== metric || !(o.op === '>' || o.op === '<')) continue
            if (o.action === 'notify' || action === 'notify') continue // rule chỉ thông báo không gây mâu thuẫn
            if ((o.level || 'campaign') !== level) continue // khác cấp: không tác động lên cùng một ngân sách
            const scopeOverlap = allActive || o.allActive !== false || inter(targets, o.targets || []).length > 0
            if (!scopeOverlap) continue
            if (kind(o.action) !== kind(action) && condOverlap({op, value}, o)) {
                w.push(`Rule “${o.name}” có thể mâu thuẫn: cùng xét ${METRIC_LABEL[metric]} nhưng ${o.action === 'increase' ? 'tăng' : o.action === 'pause' ? 'tắt' : 'giảm'} ngân sách trong vùng giá trị chồng lấn.`)
                break
            }
        }
    }

    return done(e, w, {
        ...(input.id ? {id: String(input.id)} : {}),
        name: name || 'Rule mới',
        // 3 trường cũ (metric/op/value) = điều kiện đầu tiên, để phần đọc kiểu cũ vẫn chạy
        metric,
        op,
        value: Number.isFinite(value) ? value : 0,
        conditions: conds,
        match,
        range: range || 'today',
        minSpend: Number.isFinite(minSpend) ? minSpend : 0,
        action,
        pct: Number.isFinite(pct) ? pct : 0,
        maxBudget: Number.isFinite(maxBudget) ? maxBudget : 0,
        minBudget: Number.isFinite(minBudget) ? minBudget : 0,
        cooldownHours: Number.isFinite(cooldown) ? cooldown : 0,
        from: from && to ? from : '',
        to: from && to ? to : '',
        allActive,
        level,
        accountIds,
        targets: allActive ? [] : targets,
        enabled,
    })
}

/* ------------------------------------------------------- Sửa ngân sách tay */

// `old` là ngân sách hiện tại (nếu biết). confirm = câu cảnh báo cần người dùng xác nhận.
export function validateBudget(next, old) {
    const v = num(next)
    if (!Number.isFinite(v)) return {ok: false, error: 'Nhập ngân sách hợp lệ (số)'}
    if (v <= 0) return {ok: false, error: 'Ngân sách phải lớn hơn 0'}
    if (v > LIMITS.budgetMax) return {ok: false, error: 'Ngân sách quá lớn, hãy kiểm tra lại số 0'}
    const value = Math.round(v)
    const o = Number(old)
    let confirm = ''
    if (o > 0) {
        const ratio = value / o
        if (ratio >= 2) confirm = `Ngân sách sẽ tăng từ ${money(o)} lên ${money(value)} (gấp ${ratio.toFixed(1).replace('.0', '')} lần).`
        else if (ratio <= 0.5) confirm = `Ngân sách sẽ giảm từ ${money(o)} xuống ${money(value)} (giảm ${Math.round((1 - ratio) * 100)}%).`
    }
    return {ok: true, value, confirm}
}

/* ------------------------------------------------------------- Cài đặt */
const TG_TOKEN = /^\d{6,}:[A-Za-z0-9_-]{30,}$/
const TG_CHAT = /^(-?\d{5,}|@[A-Za-z0-9_]{5,})$/

export function checkToken(t) {
    const s = String(t ?? '').trim()
    if (!s) return 'Hãy dán Access Token'
    if (/\s/.test(s)) return 'Token không được chứa khoảng trắng hay xuống dòng'
    if (s.length < 20) return 'Token quá ngắn, hãy sao chép đầy đủ (thường bắt đầu bằng EAA…)'
    return ''
}

export const cleanAccountId = (v) => String(v ?? '').trim().replace(/^act_/i, '')
// Các tài khoản quảng cáo đang quản lý (bản cũ chỉ có adAccountId). lib/fb.js có bản CommonJS tương tự.
export const accountIdsOf = (s = {}) => uniq((Array.isArray(s.adAccountIds) && s.adAccountIds.length ? s.adAccountIds : s.adAccountId ? [s.adAccountId] : []).map(cleanAccountId).filter(Boolean))
export const checkAccountId = (v) => (/^\d{5,}$/.test(cleanAccountId(v)) ? '' : 'ID tài khoản quảng cáo chỉ gồm chữ số và có ít nhất 5 số (ví dụ 1234567890)')
export const checkAppId = (v) => (/^\d{8,}$/.test(String(v ?? '').trim()) ? '' : 'App ID chỉ gồm chữ số (ít nhất 8 số)')
export const checkAppSecret = (v) => (/^[a-f0-9]{16,}$/i.test(String(v ?? '').trim()) ? '' : 'App Secret gồm chữ và số (thường 32 ký tự)')
export const checkConfigId = (v) => (!v || /^\d{5,}$/.test(String(v).trim()) ? '' : 'Configuration ID chỉ gồm chữ số')
export const checkTelegramToken = (v) => (!v || TG_TOKEN.test(String(v).trim()) ? '' : 'Bot Token không đúng dạng (ví dụ 123456789:AAxxxxxxxx…)')
export const checkTelegramChat = (v) => (!v || TG_CHAT.test(String(v).trim()) ? '' : 'Chat ID là một dãy số (có thể có dấu -) hoặc @tenkenh')

// Nhiều người nhận: Chat ID cách nhau bằng dấu phẩy (chấp nhận cả chấm phẩy, khoảng trắng, xuống dòng).
// Bỏ trùng (không phân biệt hoa thường, vì @tenkenh không phân biệt) và bỏ ô trống. lib/notify.js có bản tách y hệt (chatIdsOf).
export const MAX_TG_CHATS = 10
export function parseChatIds(v) {
    const seen = new Map()
    for (const raw of String(v ?? '').split(/[\s,;]+/)) {
        const id = raw.trim()
        if (id && !seen.has(id.toLowerCase())) seen.set(id.toLowerCase(), id)
    }
    return [...seen.values()]
}
export function checkTelegramChats(v) {
    const ids = parseChatIds(v)
    const bad = ids.find((id) => !TG_CHAT.test(id))
    if (bad) return `“${bad.length > 24 ? bad.slice(0, 24) + '…' : bad}” không phải Chat ID hợp lệ (dãy số, có thể có dấu -, hoặc @tenkenh)`
    if (ids.length > MAX_TG_CHATS) return `Tối đa ${MAX_TG_CHATS} Chat ID (đang nhập ${ids.length})`
    return ''
}

// patch: dữ liệu client gửi lên; current: cài đặt hiện tại (server: đầy đủ; giao diện: truyền has_accessToken → accessToken dạng cờ)
export function validateSettings(patch = {}, current = {}) {
    const e = {}, v = {}
    const has = (k) => Object.prototype.hasOwnProperty.call(patch, k)
    if (has('timezone')) {
        const t = String(patch.timezone ?? '').trim();
        if (!isTimezone(t)) e.timezone = 'Múi giờ không hợp lệ (ví dụ Asia/Ho_Chi_Minh)'; else v.timezone = t
    }
    if (has('ruleIntervalMin')) {
        const n = num(patch.ruleIntervalMin)
        if (!Number.isInteger(n) || n < LIMITS.intervalMin || n > LIMITS.intervalMax) e.ruleIntervalMin = `Chu kỳ kiểm tra rule từ ${LIMITS.intervalMin} đến ${LIMITS.intervalMax} phút`
        else v.ruleIntervalMin = n
    }
    if (has('reportTime')) {
        const t = String(patch.reportTime ?? '');
        if (t && !isTime(t)) e.reportTime = 'Giờ báo cáo không hợp lệ (HH:MM)'; else v.reportTime = t
    }
    if (has('telegramChatId')) {
        const c = String(patch.telegramChatId ?? '').trim();
        const m = checkTelegramChats(c);
        if (m) e.telegramChatId = m; else v.telegramChatId = parseChatIds(c).join(', ') // lưu dạng chuẩn: "id1, id2"
    }
    if (has('telegramToken')) {
        const t = String(patch.telegramToken ?? '').trim();
        const m = checkTelegramToken(t);
        if (m) e.telegramToken = m; else if (t) v.telegramToken = t
    }
    // Tài khoản quảng cáo: quản lý được nhiều tài khoản (adAccountIds). adAccountId = tài khoản đầu tiên, giữ cho phần cũ.
    if (has('adAccountIds') || has('adAccountId')) {
        const raw = has('adAccountIds') ? (Array.isArray(patch.adAccountIds) ? patch.adAccountIds : []) : [patch.adAccountId]
        const ids = uniq(raw.map(cleanAccountId).filter(Boolean))
        const bad = ids.find((a) => checkAccountId(a))
        if (bad) e.adAccountId = `${checkAccountId(bad)} — "${bad}"`
        else if (ids.length > LIMITS.accountsMax) e.adAccountId = `Tối đa ${LIMITS.accountsMax} tài khoản quảng cáo`
        else { v.adAccountIds = ids; v.adAccountId = ids[0] || '' }
    }
    if (has('accessToken')) {
        const t = String(patch.accessToken ?? '').trim();
        if (t) {
            const m = checkToken(t);
            if (m) e.accessToken = m; else v.accessToken = t
        }
    }
    if (has('resultAction')) {
        const r = String(patch.resultAction ?? '').trim();
        if (!/^[A-Za-z0-9_.]{2,100}$/.test(r)) e.resultAction = 'Loại kết quả không hợp lệ'; else v.resultAction = r
    }
    if (has('apiVersion')) {
        const r = String(patch.apiVersion ?? '').trim();
        if (!/^v\d+\.\d+$/.test(r)) e.apiVersion = 'Phiên bản API không hợp lệ (ví dụ v21.0)'; else v.apiVersion = r
    }
    if (has('skipLearning')) v.skipLearning = !!patch.skipLearning
    if (has('dailyChangeCapPct')) {
        const n = num(patch.dailyChangeCapPct)
        if (!Number.isInteger(n) || n < LIMITS.capPctMin || n > LIMITS.capPctMax) e.dailyChangeCapPct = `Giới hạn thay đổi ngân sách mỗi ngày từ ${LIMITS.capPctMin}% đến ${LIMITS.capPctMax}%`
        else v.dailyChangeCapPct = n
    }
    if (has('killSwitchEnabled')) v.killSwitchEnabled = !!patch.killSwitchEnabled
    if (has('dailySpendLimit')) {
        const n = isBlank(patch.dailySpendLimit) ? 0 : num(patch.dailySpendLimit)
        if (!Number.isFinite(n) || n < 0 || n > LIMITS.budgetMax) e.dailySpendLimit = 'Mức chi tiêu tối đa mỗi ngày phải là số không âm'
        else v.dailySpendLimit = Math.round(n)
    }
    // Dừng khẩn tính trên "tổng mọi tài khoản" (như trước) hay "từng tài khoản" (mỗi tài khoản một mức)
    if (has('killScope')) {
        if (patch.killScope === 'account' || patch.killScope === 'total') v.killScope = patch.killScope
        else e.killScope = 'Phạm vi dừng khẩn không hợp lệ'
    }
    // Mục tiêu theo từng tài khoản: { [mã tài khoản]: { cpa: CPA tối đa, roas: ROAS tối thiểu, dailySpendLimit: mức dừng khẩn riêng } }. 0/trống = chưa đặt.
    if (has('accountTargets')) {
        const raw = patch.accountTargets
        const out = {}
        if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) e.accountTargets = 'Mục tiêu theo tài khoản không hợp lệ'
        else {
            const cap = {cpa: LIMITS.budgetMax, roas: 100, dailySpendLimit: LIMITS.budgetMax}
            const label = {cpa: 'CPA mục tiêu', roas: 'ROAS mục tiêu', dailySpendLimit: 'Mức dừng khẩn'}
            outer: for (const [id, t] of Object.entries(raw)) {
                if (!/^[A-Za-z0-9_]{1,40}$/.test(id)) { e.accountTargets = `Mã tài khoản không hợp lệ: ${id.slice(0, 20)}`; break }
                const o = {}
                for (const k of Object.keys(cap)) {
                    const n = isBlank(t && t[k]) ? 0 : num(t[k])
                    if (!Number.isFinite(n) || n < 0 || n > cap[k]) { e.accountTargets = `${label[k]} của tài khoản ${id.slice(0, 20)} phải là số không âm${k === 'roas' ? ' (tối đa 100)' : ''}`; break outer }
                    if (n > 0) o[k] = k === 'roas' ? Math.round(n * 100) / 100 : Math.round(n)
                }
                if (Object.keys(o).length) out[id] = o
                if (Object.keys(out).length > LIMITS.accountsMax) { e.accountTargets = `Tối đa ${LIMITS.accountsMax} tài khoản`; break }
            }
        }
        if (!e.accountTargets) v.accountTargets = out
    }
    if (has('mock')) v.mock = !!patch.mock
    if (has('dryRun')) v.dryRun = !!patch.dryRun

    const eff = {...current, ...v}
    // Từng tài khoản: chỉ cần có mức chung HOẶC ít nhất một tài khoản có mức riêng
    const ownLimit = eff.killScope === 'account' && Object.values(eff.accountTargets || {}).some((t) => t && Number(t.dailySpendLimit) > 0)
    if ((has('killSwitchEnabled') || has('dailySpendLimit') || has('killScope') || has('accountTargets')) && !e.dailySpendLimit && eff.killSwitchEnabled && !(Number(eff.dailySpendLimit) > 0) && !ownLimit) e.dailySpendLimit = eff.killScope === 'account' ? 'Hãy nhập mức chung hoặc đặt mức riêng cho ít nhất một tài khoản (Cài đặt → Mục tiêu) để bật dừng khẩn' : 'Hãy nhập mức chi tiêu tối đa mỗi ngày (lớn hơn 0) để bật dừng khẩn'
    if (has('mock') || has('dryRun') || has('adAccountId') || has('adAccountIds') || has('accessToken')) {
        if (eff.mock === false && (!eff.accessToken || !accountIdsOf(eff).length)) e.mock = 'Cần kết nối Facebook (token và tài khoản quảng cáo) trước khi dùng dữ liệu thật.'
    }
    return done(e, [], v)
}

const WEAK = ['12345678', '123456789', '1234567890', 'password', 'matkhau123', 'qwertyui', '11111111', '00000000', 'abcd1234']

export function validatePassword(next, current = '') {
    const e = {}
    const n = String(next ?? '')
    if (n.length < 8) e.newPassword = 'Mật khẩu mới cần ít nhất 8 ký tự'
    else if (n.length > 128) e.newPassword = 'Mật khẩu tối đa 128 ký tự'
    else if (/^\d+$/.test(n)) e.newPassword = 'Không nên chỉ gồm chữ số, hãy thêm chữ cái hoặc ký tự khác'
    else if (WEAK.includes(n.toLowerCase())) e.newPassword = 'Mật khẩu này quá phổ biến, hãy chọn mật khẩu khác'
    else if (current && n === current) e.newPassword = 'Mật khẩu mới phải khác mật khẩu hiện tại'
    return done(e, [], n)
}

/* ------------------------------------------------------------- Hoàn tác */
// Trả về lý do KHÔNG hoàn tác được một dòng nhật ký (chuỗi rỗng = hoàn tác được). Dùng chung cho server và giao diện.
export function undoBlocker(l, nowMs = Date.now(), maxDays = 3) {
    if (!l) return 'Không tìm thấy dòng nhật ký.'
    if (l.undone) return 'Dòng này đã được hoàn tác.'
    if (l.kind === 'undo') return 'Không thể hoàn tác một lần hoàn tác.'
    if (l.ok === false) return 'Thao tác này đã thất bại nên không có gì để hoàn tác.'
    if (l.skipped) return 'Dòng này chỉ ghi nhận việc bỏ qua, không thay đổi gì.'
    if (l.dry) return 'Đây là bản chạy thử (chưa thay đổi thật) nên không cần hoàn tác.'
    if (!l.action || !['on', 'off', 'budget'].includes(l.action.type)) return 'Loại thao tác này không hoàn tác được.'
    if (!l.before || !l.after || !l.target || !l.target.id) return 'Dòng nhật ký cũ không lưu đủ dữ liệu để hoàn tác.'
    if (l.after.status === undefined && l.after.dailyBudget === undefined) return 'Dòng nhật ký không ghi lại thay đổi nào để hoàn tác.'
    if (l.ts && nowMs - new Date(l.ts).getTime() > maxDays * 864e5) return `Đã quá ${maxDays} ngày, dữ liệu lúc đó có thể không còn phù hợp.`
    return ''
}
