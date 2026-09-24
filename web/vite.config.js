import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Build ra ../public để server Node phục vụ trực tiếp (không cần chạy Vite khi dùng thật).
export default defineConfig({
  plugins: [vue()],
  build: { outDir: '../public', emptyOutDir: true, chunkSizeWarningLimit: 900 },
  server: { port: 5173, proxy: { '/api': process.env.API_TARGET || 'http://127.0.0.1:3000' } },
})
