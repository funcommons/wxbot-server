// 服务端 HTTP 客户端：ApiResponse 信封 + Bearer + 过期单飞轮换（/v1/auth/refresh 双放置）
import { useAuthStore } from '@/store/auth'

export interface Envelope<T> {
  code: number
  message: string
  data: T
  success?: boolean
  fail?: boolean
}

export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

let refreshing: Promise<void> | null = null

/** 过期轮换单飞：全应用只允许一个在途 refresh，完成后唤醒所有等待者 */
function rotate(): Promise<void> {
  if (!refreshing) {
    refreshing = (async () => {
      const auth = useAuthStore()
      const r = await fetch('/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.refresh },
        body: JSON.stringify({ refresh_token: auth.refresh }),
      })
      const env = (await r.json()) as Envelope<{ access_token: string; refresh_token: string }>
      if (env.code !== 0 || !env.data) {
        auth.logout()
        throw new ApiError(env.code ?? -1, env.message || 'refresh failed')
      }
      auth.setTokens(env.data.access_token, env.data.refresh_token)
    })()
    refreshing = refreshing.finally(() => { refreshing = null })
  }
  return refreshing
}

async function raw<T>(method: string, path: string, body?: unknown, retried = false): Promise<Envelope<T>> {
  const auth = useAuthStore()
  let resp: Response
  try {
    resp = await fetch(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(auth.access ? { Authorization: 'Bearer ' + auth.access } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(-2, 'connFail')
  }
  const env = (await resp.json()) as Envelope<T>
  const authed = !path.startsWith('/v1/auth/')
  if (authed && (env.code === 10200 || env.code === 10201 || env.code === 10202) && !retried) {
    await rotate()
    return raw<T>(method, path, body, true)
  }
  return env
}

/** 业务请求：code=0 → data；10700 部分成功 → data（调用方自行看 error[]）；其余抛 ApiError */
export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const env = await raw<T>(method, path, body)
  if (env.code !== 0 && env.code !== 10700) throw new ApiError(env.code, env.message)
  return env.data
}

export async function get<T>(path: string): Promise<T> { return api<T>('GET', path) }
export async function post<T>(path: string, body?: unknown): Promise<T> { return api<T>('POST', path, body) }
export async function put<T>(path: string, body?: unknown): Promise<T> { return api<T>('PUT', path, body) }
export async function del<T>(path: string): Promise<T> { return api<T>('DELETE', path) }
