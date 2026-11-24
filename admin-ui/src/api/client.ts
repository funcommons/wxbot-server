// 服务端 HTTP 客户端：ApiResponse 信封 + 固定 admin token（服务端过滤器透明换 JWT）
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

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const auth = useAuthStore()
  let resp: Response
  try {
    resp = await fetch(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(auth.token ? { Authorization: 'Bearer ' + auth.token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(-2, 'connFail')
  }
  const env = (await resp.json()) as Envelope<T>
  if (env.code === 10200 || env.code === 10201 || env.code === 10202) {
    // 固定 token 模式下意味着服务端配置已更换——踢回登录
    auth.logout()
    throw new ApiError(env.code, 'kicked')
  }
  if (env.code !== 0 && env.code !== 10700) throw new ApiError(env.code, env.message)
  return env.data
}

export async function get<T>(path: string): Promise<T> { return api<T>('GET', path) }
export async function post<T>(path: string, body?: unknown): Promise<T> { return api<T>('POST', path, body) }
export async function put<T>(path: string, body?: unknown): Promise<T> { return api<T>('PUT', path, body) }
export async function del<T>(path: string): Promise<T> { return api<T>('DELETE', path) }
