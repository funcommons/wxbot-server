// 登录态：固定 admin token（写在服务端 application.yml 的 wx-sync.admin-ui-token）。
// 服务端过滤器将该串透明换取真实 admin JWT 并自动续签——SPA 无任何刷新动作。
import { reactive } from 'vue'

const KEY = 'wxbot-admin-static-token'

const state = reactive({ token: localStorage.getItem(KEY) || '' })

export const useAuthStore = () => ({
  get token() { return state.token },
  get access() { return state.token },          // 兼容 api client 的取值口径
  get refresh() { return '' },                  // 固定 token 模式无 refresh
  get loggedIn() { return Boolean(state.token) },
  /** 固定 token 无 TTL 概念，恒返回 1（页头徽章据此显示有效） */
  get accessTtl() { return state.token ? 1 : 0 },
  login(token: string) {
    state.token = token.trim()
    localStorage.setItem(KEY, state.token)
  },
  logout() {
    state.token = ''
    localStorage.removeItem(KEY)
  },
})
