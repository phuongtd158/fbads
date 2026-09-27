import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// npm run dev: Vite chạy ở cổng 5173, chuyển /api và WebSocket /ws sang backend Spring Boot (cổng 3000).
// npm run build: ra frontend/dist, backend đọc thư mục này (PUBLIC_DIR) khi chạy thật.
const API = process.env.API_TARGET || 'http://127.0.0.1:3000'

export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 900 },
  server: {
    port: 5173,
    // changeOrigin: false = giữ nguyên Host của trình duyệt (localhost:5173). Vite 8 mặc định đổi Host thành
    // 127.0.0.1:3000, khi đó Host khác Origin và backend chặn vì tưởng là gọi chéo ("Origin không hợp lệ").
    proxy: {
      '/api': { target: API, changeOrigin: false },
      '/ws': { target: API, ws: true, changeOrigin: false },
    },
  },
})
