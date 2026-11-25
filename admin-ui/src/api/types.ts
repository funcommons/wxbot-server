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

// ---- 聊天视图（/v1/chats + /v1/messages + /v1/contacts）----
// 注意：framework4j 线上把所有 Long 序列化成 String（JS 精度安全），
// message_count / create_time_raw / local_id 等计数与时间戳按 string 处理。

export interface ChatRowVO {
  id?: string | null
  account?: string | null
  username: string
  is_group?: boolean | null
  display_name?: string | null
  last_message_at?: string | null
  message_count?: string | number | null
  unread?: number | null
  updated_at?: string | null
  last_sender_username?: string | null
  last_type_code?: string | null
  last_body?: string | null
}

export interface MessageRowVO {
  id: string
  chat_username?: string | null
  is_group?: boolean | null
  server_id?: string | null
  sender_username?: string | null
  type_code?: string | null
  type_name?: string | null
  create_time?: string | null
  create_time_raw?: string | number | null
  body?: string | null
  is_revoked?: boolean
  revoked_at?: string | null
  sources?: { account: string; shard: string; local_id: string }[] | null
}

export interface ContactRowVO {
  id?: string | null
  account?: string | null
  username: string
  source?: string | null
  nick_name?: string | null
  remark?: string | null
  alias?: string | null
  big_head_url?: string | null
}

export interface CursorPageVO<T> {
  list: T[]
  next_cursor?: string | null
  has_more?: boolean
}
