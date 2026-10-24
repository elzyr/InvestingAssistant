import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    proxy: {
      '/api/prices': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/currencies': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/market': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
