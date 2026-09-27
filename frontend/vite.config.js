import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// npm run dev: Vite chạy ở cổng 5173, chuyển /api sang backend Spring Boot (cổng 3000).
// npm run build: ra frontend/dist, backend đọc thư mục này (PUBLIC_DIR) khi chạy thật.
export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 900 },
  server: { port: 5173, proxy: { '/api': process.env.API_TARGET || 'http://127.0.0.1:3000' } },
})
