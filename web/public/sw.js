// Service worker tối thiểu để cài tool như app trên điện thoại.
// Chỉ lo một việc: mất mạng khi mở trang thì hiện offline.html thay vì trang lỗi của trình duyệt.
// Không lưu giao diện hay số liệu: mọi yêu cầu khác (kể cả /api) đi thẳng lên server như khi chưa có service worker,
// nên mỗi lần mở luôn là số liệu mới và bản giao diện mới nhất sau khi deploy.
const CACHE = 'fbads-offline-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.add(OFFLINE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  if (e.request.mode !== 'navigate') return; // không đụng tới API, JS, CSS, ảnh
  e.respondWith(fetch(e.request).catch(() => caches.match(OFFLINE)));
});
