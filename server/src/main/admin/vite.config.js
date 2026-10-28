import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig({
  base: '/admin/',
  // Maven 构建会执行 npm ci，开发服务的缓存不能放在 node_modules 中。
  cacheDir: '.vite-cache',
  optimizeDeps: {
    include: ['echarts', 'element-ui/lib/timeline', 'element-ui/lib/timeline-item']
  },
  plugins: [vue()],
  build: {
    outDir: '../resources/static/admin',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return
          }
          if (id.includes('element-ui')) {
            return 'element-ui'
          }
        }
      }
    }
  },
  server: {
    port: 5174
  }
})
