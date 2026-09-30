// Middleware dùng chung cho /api (gắn bằng app.use('/api', …) nên req.path tính từ sau /api): header, chặn CSRF, đọc JSON, bắt đăng nhập, 404 và xử lý lỗi.
const express = require('express');
const auth = require('./auth');
const fb = require('./fb');
const ctx = require('./ctx');

// Các API không cần đăng nhập (đường dẫn tính từ /api). Callback Facebook không mang cookie được (xem lib/routes/facebook.js).
const PUBLIC_API = new Set(['/auth', '/health', '/login', '/logout', '/register', '/fb/callback']);

// Dữ liệu API không bao giờ được cache
function apiHeaders(req, res, next) {
  res.set({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  next();
}

// Chặn gọi chéo từ trang web khác (CSRF): request ghi phải cùng origin và là JSON.
// HEAD chỉ được phép trên /api/auth và /api/health: các dịch vụ theo dõi (UptimeRobot…) hay dùng HEAD để kiểm tra tool còn sống
function sameOriginJson(req, res, next) {
  if (req.method === 'GET' || (req.method === 'HEAD' && (req.path === '/auth' || req.path === '/health'))) return next();
  const o = req.headers.origin;
  if (o && new URL(o).host !== req.headers.host) return res.status(403).json({ error: 'Origin không hợp lệ' });
  if (!String(req.headers['content-type'] || '').includes('application/json')) return res.status(415).json({ error: 'Cần Content-Type: application/json' });
  next();
}

// Đọc body JSON (tối đa 1MB). Body hỏng hoặc trống coi như {} để route luôn có req.body là object.
const jsonBody = [
  express.json({ limit: '1mb', type: () => true }),
  (err, req, res, next) => {
    if (err.type !== 'entity.parse.failed') return next(err);
    req.body = {};
    next();
  },
  (req, res, next) => { if (!req.body || typeof req.body !== 'object') req.body = {}; next(); },
];

// Đăng nhập rồi thì mọi thứ phía sau (route, cache Facebook, nhật ký…) chạy trong tài khoản đó (lib/ctx.js).
// API công khai chạy ngoài tài khoản nào; route nào cần dữ liệu thì tự chọn (vd callback Facebook).
function requireAuth(req, res, next) {
  const uid = auth.userIdOf(req);
  if (uid != null) { req.userId = uid; return ctx.run(uid, next); }
  if (PUBLIC_API.has(req.path)) return next();
  res.status(401).json({ error: 'Cần đăng nhập' });
}

// Chỉ tài khoản admin: quản lý tài khoản khác
function requireAdmin(req, res, next) {
  if (auth.isAdmin(req.userId)) return next();
  res.status(403).json({ error: 'Chỉ tài khoản admin làm được việc này' });
}

const notFound = (req, res) => res.status(404).json({ error: 'Not found' });

// Lỗi ném ra từ route (kể cả hàm async): trả JSON, giữ cờ drift/rateLimited cho giao diện
// eslint-disable-next-line no-unused-vars
function errorHandler(e, req, res, next) {
  res.status(e.status || 500).json({ error: e.message, ...(e.errors ? { errors: e.errors } : {}), ...(e.drift ? { drift: true } : {}), ...(fb.isRateLimited(e) ? { rateLimited: true } : {}) });
}

module.exports = { apiHeaders, sameOriginJson, jsonBody, requireAuth, requireAdmin, notFound, errorHandler };
