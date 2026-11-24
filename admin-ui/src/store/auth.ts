// 登录态：模块级 reactive 单例（免 pinia 依赖）；JWT exp 本地解码供页头展示
import { reactive } from 'vue'
import { post } from '@/api/client'

const KEY = 'wxbot-admin-auth'

interface Persisted { access: string; refresh: string }

function jwtExp(jwt: string): number {
  try {
    const p = jwt.split('.')[1]
    const d = JSON.parse(atob(p.replace(/-/g, '+').replace(/_/g, '=')))
    return Math.floor(Number(d.exp) / 1000) // 服务端 exp 是毫秒时间戳
  } catch {
    return 0
  }
}

function load(): Persisted {
  try { return JSON.parse(localStorage.getItem(KEY) || 'null') || { access: '', refresh: '' } }
  catch { return { access: '', refresh: '' } }
}

const saved = load()

const state = reactive({
  access: saved.access,
  refresh: saved.refresh,
})

function persist() {
  localStorage.setItem(KEY, JSON.stringify({ access: state.access, refresh: state.refresh }))
}

export const useAuthStore = () => ({
  get access() { return state.access },
  get refresh() { return state.refresh },
  get loggedIn() { return Boolean(state.access || state.refresh) },
  /** access 剩余秒数（exp 缺失视为 0） */
  get accessTtl() { return state.access ? Math.max(0, jwtExp(state.access) - Math.floor(Date.now() / 1000)) : 0 },
  /** 登录：refresh 必填（一次性轮换取新对）；access 可选（直接采用） */
  async login(refreshIn: string, accessIn = '') {
    const env = await post<{ access_token: string; refresh_token: string }>(
      '/v1/auth/refresh', { refresh_token: refreshIn.trim() })
    this.setTokens(accessIn.trim() || env.access_token, env.refresh_token)
  },
  setTokens(access: string, refresh: string) {
    state.access = access
    state.refresh = refresh
    persist()
  },
  logout() {
    state.access = ''
    state.refresh = ''
    localStorage.removeItem(KEY)
  },
})
