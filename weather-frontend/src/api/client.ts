import axios from 'axios'

// Shared Axios instance for all backend calls. baseURL defaults to '' so
// requests hit the same origin -- in dev, Vite's proxy (see vite.config.ts)
// forwards '/api' to the backend, sidestepping CORS. Set VITE_API_BASE_URL to
// target a different host, e.g. once the apps move to Kubernetes.
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
})

export default apiClient
