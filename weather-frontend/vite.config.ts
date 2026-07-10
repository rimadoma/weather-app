import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // Dev-only: proxy '/api' to the Spring Boot backend so the browser only
    // ever talks to the Vite origin -- no CORS setup needed. In a deployed
    // environment the app is served behind the same origin (or
    // VITE_API_BASE_URL points elsewhere, see src/api/client.ts).
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
