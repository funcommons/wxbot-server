// 服务端 VO 类型（wire 为 snake_case，与 Java 全局命名策略一致）
export interface PageVO<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
  hasMore: boolean
}

export interface AccountVO {
  id: string
  account: string
  wxid?: string
  hostname?: string
  client_version?: string
  last_sync_at?: string
  last_seen_at?: string
  created_at?: string
  updated_at?: string
}

export interface AccountTokenVO {
  access_token: string
  refresh_token: string
  expires_at?: string
}

export interface RegCodeVO {
  id: number
  label?: string
  account?: string
  code_hash?: string
  expires_at?: string
  used_by?: string
  used_at?: string
  created_at?: string
}

export interface RegCodeIssueVO {
  id: number
  code: string
  account?: string
  expires_at?: string
}

export interface ConsumerVO {
  id: string
  name: string
  enable_poll?: boolean
  enable_webhook?: boolean
  webhook_url?: string
  remark?: string
  created_at?: string
  updated_at?: string
}

export interface ConsumerCreatedVO {
  consumer: ConsumerVO
  poll_token?: string
  webhook_secret?: string
}

export interface SubscriptionVO {
  id: string
  consumer_id: string
  name: string
  filter?: Record<string, unknown>
  delivery_mode?: string
  enabled?: number | boolean
  webhook_url?: string
  order_mode?: string
  visible_seconds?: number
  max_attempts?: number
  backoff_base_seconds?: number
  created_at?: string
  updated_at?: string
}

export interface SubscriptionCreatedVO {
  subscription: SubscriptionVO
  already_exists: boolean
}
