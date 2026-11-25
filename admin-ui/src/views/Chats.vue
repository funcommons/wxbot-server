<template>
  <div class="app-page chats-page">
    <div class="wx-shell">
      <!-- 左：会话列表（仿微信聊天列表） -->
      <aside class="wx-list">
        <div class="wx-list-tools">
          <FcInput v-model="search" size="small" :placeholder="t('chatv.searchPh')" :prefix-icon="Search"
                   clearable />
          <FcSegmented v-model="chatKind" size="sm" block :options="kindOptions" @change="reload" />
          <div class="wx-list-meta">
            <FcSelect v-model="account" size="small" :options="accountOptions" style="width: 100%"
                      @change="reload" />
            <span class="meta">{{ t('chatv.loaded', { n: rows.length }) }}</span>
          </div>
        </div>
        <div class="sess-scroll" @scroll="onSessScroll">
          <div v-for="r in viewRows" :key="r.username" class="sess-item" :data-user="r.username"
               :class="{ active: r.username === activeChat }" @click="selectChat(r)">
            <div class="sess-avwrap">
              <FcAvatar :src="avatarOf(r.username)" :name="dispName(r.username)" size="medium" />
            </div>
            <div class="sess-main">
              <div class="sess-top">
                <span class="sess-name">{{ dispName(r.username) }}</span>
                <span class="sess-time">{{ fmtSessTime(r.last_message_at) }}</span>
              </div>
              <div class="sess-bottom">
                <span class="sess-summary">{{ sessSummary(r) }}</span>
              </div>
            </div>
          </div>
          <div v-if="listLoading" class="sess-foot">{{ t('chatv.loadingMore') }}</div>
          <div v-else-if="!hasMoreChats && rows.length" class="sess-foot">{{ t('chatv.noMore') }}</div>
          <FcEmpty v-if="!listLoading && !viewRows.length" type="empty" :title="t('chatv.noChat')"
                   style="padding: 32px 0" />
        </div>
      </aside>

      <!-- 右：聊天气泡区 -->
      <section class="wx-chat">
        <div class="wx-chat-head">
          <div class="wx-chat-headmain">
            <span class="wx-chat-title" @click="openDetail">{{ headTitle }}</span>
            <span class="wx-chat-sub">{{ headSub }}</span>
          </div>
          <span class="spacer" />
          <FcButton size="sm" :loading="msgLoading" :disabled="!activeChat" @click="reloadMsgs">
            {{ t('chatv.refresh') }}
          </FcButton>
        </div>

        <div v-if="!activeChat" class="wx-chat-empty">{{ t('chatv.emptyPick') }}</div>

        <template v-else>
          <div ref="chatEl" class="chat-wrap" @scroll="onChatScroll">
            <div v-if="olderBusy" class="chat-hint">{{ t('chatv.loadingEarlier') }}</div>
            <div v-else-if="!hasMoreMsgs && msgs.length" class="chat-hint">{{ t('chatv.noEarlier') }}</div>
            <FcButton v-else-if="hasMoreMsgs" variant="secondary" size="sm" style="align-self: center; margin: 8px 0"
                      @click="loadOlder">{{ t('chatv.loadEarlier') }}</FcButton>

            <template v-for="(m, i) in chatRows" :key="m.id">
              <div v-if="showTimeAt(i)" class="chat-time">{{ fmtChatTime(timeOf(m)) }}</div>
              <div v-if="isSysMsg(m)" class="chat-sys">{{ oneLine(bubbleBody(m)) }}</div>
              <div v-else class="chat-row" :class="{ self: isSelf(m) }">
                <FcAvatar :src="avatarOf(m.sender_username || activeChat)"
                          :name="dispName(m.sender_username || activeChat)" size="medium"
                          @click="openContact(m.sender_username || activeChat)" />
                <div class="chat-body">
                  <div v-if="showNick(m)" class="chat-nick">{{ dispName(m.sender_username) }}</div>
                  <div class="chat-bubble" :class="{ bare: bubbleBare(m), revoked: m.is_revoked }"
                       @click="openMsgDetail(m)">
                    <template v-if="m.is_revoked">{{ t('chatv.revoked') }}</template>
                    <template v-else-if="bubbleKind(m) === 'text'">{{ bubbleBody(m) }}</template>
                    <template v-else-if="bubbleKind(m) === 'emoji'">
                      <img v-if="emojiUrl(m)" class="emoji-img" :src="emojiUrl(m)" alt="">
                      <span v-else class="chat-ph">{{ t('ph.emoji') }}</span>
                    </template>
                    <template v-else-if="bubbleKind(m) === 'img'">
                      <div class="img-ph"><span>[{{ m.type_name || 'IMG' }}]</span></div>
                    </template>
                    <span v-else-if="bubbleKind(m) === 'voice'" class="voice-strip">{{ voiceLabel(m) }}</span>
                    <template v-else-if="bubbleKind(m) === 'quote'">
                      <div class="quote-title">{{ appTitle(m) }}</div>
                      <div v-if="appReferText(m)" class="quote-ref">{{ appReferText(m) }}</div>
                    </template>
                    <template v-else-if="bubbleKind(m) === 'file'">
                      <div class="file-card">
                        <span class="file-ico">{{ appFileExt(m) }}</span>
                        <span class="file-info">
                          <span class="file-name">{{ appTitle(m) }}</span>
                          <span v-if="appFileSize(m)" class="file-size">{{ appFileSize(m) }}</span>
                        </span>
                      </div>
                    </template>
                    <template v-else-if="bubbleKind(m) === 'link'">
                      <div class="link-title">{{ appTitle(m) }}</div>
                      <div v-if="appDes(m)" class="link-des">{{ appDes(m) }}</div>
                    </template>
                    <span v-else class="chat-ph">{{ bubbleBody(m) }}</span>
                  </div>
                </div>
              </div>
            </template>

            <!-- 本地回显：任务已受理，等 wxbot-helper OCR 执行回执 -->
            <template v-for="e in echoesOfChat" :key="'echo-' + e.id">
              <div class="chat-row self">
                <FcAvatar :name="myWxid || 'me'" size="medium" />
                <div class="chat-body">
                  <div class="chat-bubble self-send">{{ e.text }}</div>
                </div>
              </div>
              <div class="chat-sys" :class="{ 'send-fail': !e.ok }">{{ e.note }}</div>
            </template>
          </div>

          <!-- 发送输入区：走 SEND_MESSAGE 任务，由 wxbot-helper OCR 执行 -->
          <div class="chat-composer">
            <FcInput v-model="draft" type="textarea" :rows="3" :placeholder="t('chatv.composerPh')"
                     class="composer-input" @keydown.enter="onDraftEnter"
                     @compositionstart="cmpOn = true" @compositionend="onCmpEnd" />
            <div class="chat-composer-foot">
              <span class="chat-composer-hint">{{ sendHint }}</span>
              <FcButton variant="primary" size="sm" :loading="sendBusy" :disabled="!draft.trim() || !activeChat"
                        @click="sendDraft">{{ sendBusy ? t('chatv.sending') : t('chatv.send') }}</FcButton>
            </div>
          </div>

          <button v-if="showBottomBtn" class="chat-gobottom" :title="t('chatv.toBottom')"
                  @click="scrollBottom">↓</button>
        </template>
      </section>
    </div>

    <!-- 会话详情：群聊 = 成员宫格 + 字段；单聊 = 字段行 -->
    <FcDrawer v-model:open="detailOpen" :title="t('chatv.detailTitle')" size="420px">
      <template v-if="detailOpen">
        <div v-if="detailLoading"><FcSkeleton variant="text" :rows="4" /></div>
        <template v-else>
          <div v-if="members.length" class="c-members">
            <div v-for="mb in members.slice(0, 10)" :key="mb.member_username" class="c-member"
                 @click="openContact(mb.member_username)">
              <FcAvatar :src="avatarOf(mb.member_username)" :name="dispName(mb.member_username)" size="small" />
              <span class="c-mname">{{ dispName(mb.member_username) }}</span>
            </div>
          </div>
          <div v-if="membersFailed" class="c-none">{{ t('chatv.noMembers') }}</div>
          <div class="dlg-fields">
            <div class="cfield"><span class="k">{{ t('msg.chatId') }}</span><span class="v">{{ activeChat }}</span></div>
            <div class="cfield"><span class="k">{{ t('col.status') }}</span>
              <span class="v">{{ activeChatIsGroup ? t('chatv.kindGroups') : t('chatv.kindDirect') }}</span></div>
            <div class="cfield"><span class="k">{{ t('msg.messageCount') }}</span>
              <span class="v">{{ activeChatCount }}</span></div>
            <div v-if="myAccount" class="cfield"><span class="k">Account</span><span class="v">{{ myAccount }}</span></div>
          </div>
        </template>
      </template>
    </FcDrawer>

    <!-- 消息详情 -->
    <FcDialog v-model:open="msgDlgOpen" :title="t('chatv.msgTitle')" width="560px">
      <template v-if="msgDlg">
        <div class="dlg-fields">
          <div class="cfield"><span class="k">ID</span><span class="v">{{ msgDlg.id }}</span></div>
          <div v-if="msgDlg.server_id" class="cfield"><span class="k">Server ID</span>
            <span class="v">{{ msgDlg.server_id }}</span></div>
          <div class="cfield"><span class="k">{{ t('msg.sender') }}</span>
            <span class="v">{{ msgDlg.sender_username || '—' }}</span></div>
          <div class="cfield"><span class="k">{{ t('msg.type') }}</span>
            <span class="v">{{ msgDlg.type_name || '—' }}（{{ msgDlg.type_code || '—' }}）</span></div>
          <div class="cfield"><span class="k">{{ t('msg.time') }}</span>
            <span class="v">{{ msgDlg.create_time ? msgDlg.create_time.replace('T', ' ').slice(0, 19) : '—' }}</span></div>
          <div v-for="(s, si) in msgDlg.sources || []" :key="si" class="cfield">
            <span class="k">Source {{ si + 1 }}</span>
            <span class="v mono">{{ s.account }} / {{ s.shard }} / {{ s.local_id }}</span></div>
        </div>
        <pre class="msg-pre">{{ msgDlg.body || '—' }}</pre>
      </template>
      <template #footer>
        <FcButton size="sm" @click="copyBody">{{ t('common.copy') }}</FcButton>
        <FcButton variant="primary" size="sm" @click="msgDlgOpen = false">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 联系人名片 -->
    <FcDialog v-model:open="contactOpen" :title="t('chatv.contactTitle')" width="420px">
      <template v-if="contactInfo">
        <div class="c-head">
          <FcAvatar :src="contactInfo.big_head_url || ''" :name="contactInfo.username" size="large" />
          <span class="c-name">{{ contactInfo.remark || contactInfo.nick_name || contactInfo.username }}</span>
        </div>
        <div class="dlg-fields">
          <div class="cfield"><span class="k">{{ t('msg.chatId') }}</span><span class="v">{{ contactInfo.username }}</span></div>
          <div v-if="contactInfo.alias" class="cfield"><span class="k">{{ t('contact.alias') }}</span>
            <span class="v">{{ contactInfo.alias }}</span></div>
          <div v-if="contactInfo.nick_name" class="cfield"><span class="k">{{ t('contact.nickname') }}</span>
            <span class="v">{{ contactInfo.nick_name }}</span></div>
          <div v-if="contactInfo.remark" class="cfield"><span class="k">{{ t('contact.remark2') }}</span>
            <span class="v">{{ contactInfo.remark }}</span></div>
          <div v-if="contactInfo.source" class="cfield"><span class="k">{{ t('contact.source') }}</span>
            <span class="v">{{ contactInfo.source }}</span></div>
        </div>
      </template>
      <div v-else class="c-none">{{ t('chatv.stranger') }}</div>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  FcAvatar, FcButton, FcDialog, FcDrawer, FcEmpty, FcInput, FcSegmented, FcSelect,
  FcSkeleton, type SelectOption,
} from '@/components/sdk'
import { get, post, ApiError } from '@/api/client'
import type { AccountVO, ChatRowVO, ContactRowVO, CursorPageVO, MessageRowVO, PageVO } from '@/api/types'

/* ================= 左栏：会话列表 ================= */

const { t } = useI18n()
const route = useRoute()

const rows = ref<ChatRowVO[]>([])
const listLoading = ref(false)
const hasMoreChats = ref(true)
const page = ref(1)
const PAGE_SIZE = 50

const search = ref('')
const chatKind = ref('')
const kindOptions = [
  { label: t('chatv.kindAll'), value: '' },
  { label: t('chatv.kindGroups'), value: 'group' },
  { label: t('chatv.kindDirect'), value: 'direct' },
]

const accounts = ref<AccountVO[]>([])
const account = ref('')
const accountOptions = computed<SelectOption[]>(() => [
  { label: t('chatv.allAccounts'), value: '' },
  ...accounts.value.map((a) => ({ label: a.wxid || a.account, value: a.account })),
])
const myAccount = computed(() => account.value || accounts.value[0]?.account || '')
const myWxid = computed(() => {
  const hit = accounts.value.find((a) => a.account === myAccount.value)
  return hit?.wxid || ''
})

const viewRows = computed(() => {
  const kw = search.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) => {
    const hay = [r.username, r.display_name, r.last_body, nameMap.value[r.username]]
    return hay.some((x) => (x || '').toLowerCase().includes(kw))
  })
})

const activeChat = ref('')
const activeChatIsGroup = computed(() => activeChat.value.endsWith('@chatroom'))
const activeChatCount = computed(() =>
  Number(rows.value.find((r) => r.username === activeChat.value)?.message_count ?? 0))

async function loadChats() {
  listLoading.value = true
  try {
    const q = new URLSearchParams({ page: String(page.value), pageSize: String(PAGE_SIZE) })
    if (account.value) q.set('account', account.value)
    if (chatKind.value === 'group') q.set('isGroup', 'true')
    if (chatKind.value === 'direct') q.set('isGroup', 'false')
    const data = await get<PageVO<ChatRowVO>>(`/v1/chats?${q}`)
    rows.value = page.value === 1 ? data.list : [...rows.value, ...data.list]
    hasMoreChats.value = page.value * PAGE_SIZE < Number(data.total)
    // 顺带预取显示名（会话行 + 摘要发送者），nameMap 命中即免逐个查
    const users = new Set<string>()
    data.list.forEach((r) => {
      users.add(r.username)
      if (r.last_sender_username) users.add(r.last_sender_username)
    })
    prefetchNames([...users])
    // 切筛选/账号后，当前会话若不在新列表里：回落到首个会话，避免右侧停留在一个
    // 列表中已不可见（也就无法取消选中）的会话上
    if (!rows.value.some((r) => r.username === activeChat.value)) {
      if (rows.value.length) selectChat(rows.value[0])
      else {
        // echoes/drafts 按 chat 键隔离，保留（切回来仍能看到待回执的发送回显）
        activeChat.value = ''
        msgs.value = []
      }
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : t('err.reqFail'))
  } finally {
    listLoading.value = false
  }
}

function reload() {
  page.value = 1
  hasMoreChats.value = true
  loadChats()
}

function onSessScroll(e: Event) {
  const el = e.target as HTMLElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 60 && hasMoreChats.value && !listLoading.value) {
    page.value += 1
    loadChats()
  }
}

/* ================= 显示名 / 头像（联系人缓存） ================= */

const nameMap = ref<Record<string, string>>({})
const avatarMap = ref<Record<string, string>>({})
const fetchedUsers = new Set<string>()

function dispName(u?: string | null): string {
  if (!u) return ''
  return nameMap.value[u] || u
}
function avatarOf(u?: string | null): string {
  return (u && avatarMap.value[u]) || ''
}

async function prefetchNames(users: string[]) {
  const todo = users.filter((u) => u && !fetchedUsers.has(u))
  if (!todo.length) return
  todo.forEach((u) => fetchedUsers.add(u))
  try {
    // /v1/contacts 只支持 username 精确匹配：逐个查但并发收敛（一次 8 个）
    for (let i = 0; i < todo.length; i += 8) {
      await Promise.all(todo.slice(i, i + 8).map(async (u) => {
        const data = await get<PageVO<ContactRowVO>>(`/v1/contacts?username=${encodeURIComponent(u)}&pageSize=1`)
        const c = data.list.find((x) => x.username === u)
        if (c) {
          nameMap.value[u] = c.remark || c.nick_name || u
          if (c.big_head_url) avatarMap.value[u] = c.big_head_url
        }
      }))
    }
  } catch { /* 名字解析失败静默回退 wxid */ }
}

/* ================= 右栏：消息气泡流 ================= */

const msgs = ref<MessageRowVO[]>([])
const msgCursor = ref('')
const hasMoreMsgs = ref(true)
const msgLoading = ref(false)
const olderBusy = ref(false)
const showBottomBtn = ref(false)
const chatEl = ref<HTMLElement | null>(null)

// 接口按时间倒序返回；聊天序 = 反转（旧上新下）
const chatRows = computed(() => [...msgs.value].reverse())

function timeOf(m: MessageRowVO): number {
  if (m.create_time_raw) return Number(m.create_time_raw)
  if (m.create_time) return Math.floor(new Date(m.create_time).getTime() / 1000)
  return 0
}

async function loadMsgs(reset: boolean) {
  if (!activeChat.value) return
  msgLoading.value = true
  try {
    const q = new URLSearchParams({ chatUsername: activeChat.value, limit: '50' })
    if (!reset && msgCursor.value) q.set('cursor', msgCursor.value)
    const data = await get<CursorPageVO<MessageRowVO>>(`/v1/messages?${q}`)
    msgs.value = reset ? data.list : [...data.list, ...msgs.value]
    msgCursor.value = data.next_cursor || ''
    hasMoreMsgs.value = Boolean(data.has_more)
    if (reset) {
      const users = new Set<string>()
      data.list.forEach((m) => m.sender_username && users.add(m.sender_username))
      prefetchNames([...users])
      await nextTick()
      const el = chatEl.value
      if (el) el.scrollTop = el.scrollHeight
    }
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : t('err.reqFail'))
  } finally {
    msgLoading.value = false
  }
}

function reloadMsgs() {
  msgCursor.value = ''
  hasMoreMsgs.value = true
  loadMsgs(true)
}

async function loadOlder() {
  if (!hasMoreMsgs.value || olderBusy.value || msgLoading.value) return
  const el = chatEl.value
  const oldH = el ? el.scrollHeight : 0
  const oldTop = el ? el.scrollTop : 0
  olderBusy.value = true
  try {
    const q = new URLSearchParams({ chatUsername: activeChat.value, limit: '50', cursor: msgCursor.value })
    const data = await get<CursorPageVO<MessageRowVO>>(`/v1/messages?${q}`)
    msgs.value = [...data.list, ...msgs.value]
    msgCursor.value = data.next_cursor || ''
    hasMoreMsgs.value = Boolean(data.has_more)
    await nextTick()
    const e2 = chatEl.value
    if (e2) e2.scrollTop = e2.scrollHeight - oldH + oldTop   // 锚点行视觉不动
  } catch {
    hasMoreMsgs.value = false   // 失败锁定，避免滚动风暴反复重试
  } finally {
    olderBusy.value = false
  }
}

function onChatScroll(e: Event) {
  const el = e.target as HTMLElement
  showBottomBtn.value = el.scrollTop + el.clientHeight < el.scrollHeight - 400
  if (el.scrollTop < 60 && hasMoreMsgs.value && !olderBusy.value && !msgLoading.value) loadOlder()
}

function scrollBottom() {
  const el = chatEl.value
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
}

function selectChat(r: ChatRowVO) {
  if (r.username === activeChat.value && msgs.value.length) return
  activeChat.value = r.username
  msgs.value = []
  msgCursor.value = ''
  hasMoreMsgs.value = true
  loadMsgs(true)
}

const headTitle = computed(() => dispName(activeChat.value))
const headSub = computed(() => {
  const n = activeChatCount.value
  if (activeChatIsGroup.value) return n ? t('chatv.history', { n }) : ''
  return n ? t('chatv.history', { n }) : ''
})

/* ================= 时间 / 摘要 ================= */

const WEEK_CN = ['日', '一', '二', '三', '四', '五', '六']
const dayStart = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()

function fmtSessTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const days = Math.round((dayStart(now) - dayStart(d)) / 86400000)
  if (days === 0) return p(d.getHours()) + ':' + p(d.getMinutes())
  if (days === 1) return t('chatv.yesterday')
  if (days > 1 && days < 7) return t('chatv.weekday', { d: WEEK_CN[d.getDay()] })
  if (d.getFullYear() === now.getFullYear())
    return t('chatv.monthDay', { m: d.getMonth() + 1, d: d.getDate() })
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

function fmtChatTime(sec: number): string {
  if (!sec || sec <= 0) return '—'
  const d = new Date(sec * 1000)
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const hm = p(d.getHours()) + ':' + p(d.getMinutes())
  const days = Math.round((dayStart(now) - dayStart(d)) / 86400000)
  if (days === 0) return hm
  if (days === 1) return `${t('chatv.yesterday')} ${hm}`
  if (days > 1 && days < 7) return `${t('chatv.weekday', { d: WEEK_CN[d.getDay()] })} ${hm}`
  if (d.getFullYear() === now.getFullYear())
    return t('chatv.monthDayTime', { m: d.getMonth() + 1, d: d.getDate(), hm })
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}`
}

function showTimeAt(i: number): boolean {
  if (i === 0) return true
  return timeOf(chatRows.value[i]) - timeOf(chatRows.value[i - 1]) > 300
}

function oneLine(s?: string | null): string {
  return (s || '').replace(/\r|\n/g, ' ')
}

function sessSummary(r: ChatRowVO): string {
  const text = bubblePreview(r.last_type_code, r.last_body)
  if (r.is_group && r.last_sender_username) {
    return dispName(r.last_sender_username) + ': ' + text
  }
  return text
}

/* ================= 气泡内容分型（掩码 + XML 解析，口径同 wxbot-helper） ================= */

function tcOf(m: { type_code?: string | null }): number {
  const n = Number(m.type_code)
  return Number.isFinite(n) ? (n & 0xFFFFFFFF) : 0
}

function plainBody(m: MessageRowVO): string {
  // 群聊历史消息 content 可能带「sender: 」前缀——冒号+空白后紧跟 < 才剥（避免误伤正文冒号）
  return (m.body || '').replace(/^[A-Za-z0-9_@+.-]{1,64}:\s*(?=<)/, '').trim()
}

function isSysMsg(m: MessageRowVO): boolean {
  const tc = tcOf(m)
  return tc === 10000 || tc === 10002
}
function isSelf(m: MessageRowVO): boolean {
  return Boolean(m.sender_username && myWxid.value && m.sender_username === myWxid.value)
}
function showNick(m: MessageRowVO): boolean {
  return activeChatIsGroup.value && !isSelf(m)
}

interface ParsedApp { kind: 'quote' | 'file' | 'link'; title: string; des: string; url: string; fileExt: string; fileLen: number; refer: { name: string; text: string } | null }
const appCache = new WeakMap<MessageRowVO, ParsedApp | null>()

function parseAppmsg(m: MessageRowVO): ParsedApp | null {
  if (appCache.has(m)) return appCache.get(m) ?? null
  let out: ParsedApp | null = null
  try {
    const doc = new DOMParser().parseFromString(plainBody(m), 'text/xml')
    if (doc.getElementsByTagName('parsererror').length) throw new Error('bad xml')
    const app = doc.getElementsByTagName('appmsg')[0]
    if (!app) throw new Error('no appmsg')
    const txt = (tag: string) => {
      const el = app.getElementsByTagName(tag)[0]
      return el ? (el.textContent || '').trim() : ''
    }
    let fileExt = ''
    let fileLen = 0
    const att = app.getElementsByTagName('appattach')[0]
    if (att) {
      const fe = att.getElementsByTagName('fileext')[0]
      if (fe) fileExt = (fe.textContent || '').trim()
      const tl = parseInt(att.getElementsByTagName('totallen')[0]?.textContent || '', 10)
      if (tl > 0) fileLen = tl
    }
    let refer: { name: string; text: string } | null = null
    const rf = app.getElementsByTagName('refermsg')[0]
    if (rf) {
      const rn = rf.getElementsByTagName('displayname')[0]
      const rc = rf.getElementsByTagName('content')[0]
      refer = { name: rn ? (rn.textContent || '').trim() : '', text: (rc ? rc.textContent || '' : '').trim().slice(0, 60) }
    }
    const inner = parseInt(txt('type'), 10) || 0
    const kind = inner === 57 && refer ? 'quote' : inner === 6 ? 'file' : 'link'
    out = { kind, title: txt('title'), des: txt('des'), url: txt('url'), fileExt, fileLen, refer }
  } catch {
    out = null
  }
  appCache.set(m, out)
  return out
}

const emojiCache = new WeakMap<MessageRowVO, string>()
function emojiUrl(m: MessageRowVO): string {
  if (emojiCache.has(m)) return emojiCache.get(m) || ''
  let u = ''
  try {
    const doc = new DOMParser().parseFromString(plainBody(m), 'text/xml')
    const el = doc.getElementsByTagName('emoji')[0]
    if (el) u = el.getAttribute('cdnurl') || el.getAttribute('url') || ''
  } catch { u = '' }
  emojiCache.set(m, u)
  return u
}

const voiceCache = new WeakMap<MessageRowVO, number>()
function voiceSecs(m: MessageRowVO): number {
  if (voiceCache.has(m)) return voiceCache.get(m) || 0
  let s = 0
  try {
    const doc = new DOMParser().parseFromString(plainBody(m), 'text/xml')
    const el = doc.getElementsByTagName('voicemsg')[0]
    const ms = el ? parseInt(el.getAttribute('voicelength') || '0', 10) : 0
    if (ms > 0) s = Math.round(ms / 1000)
  } catch { s = 0 }
  voiceCache.set(m, s)
  return s
}
function voiceLabel(m: MessageRowVO): string {
  const s = voiceSecs(m)
  return s > 0 ? `▶ ${s}″` : `[${m.type_name || '语音'}]`
}

function bubbleKind(m: MessageRowVO): string {
  const tc = tcOf(m)
  if (tc === 1) return (m.body || '').trim() ? 'text' : 'ph'
  if (tc === 3) return 'img'
  if (tc === 34) return 'voice'
  if (tc === 47) return 'emoji'
  if (tc === 49) {
    const a = parseAppmsg(m)
    return a ? a.kind : 'ph'
  }
  return 'ph'
}
function bubbleBare(m: MessageRowVO): boolean {
  return bubbleKind(m) === 'emoji'
}

/** 摘要/占位正文：文本原文；非文本按类型给占位文案。 */
function bubblePreview(tc: string | null | undefined, body: string | null | undefined): string {
  const n = Number(tc)
  const mask = Number.isFinite(n) ? (n & 0xFFFFFFFF) : 0
  if (mask === 1) return oneLine(body)
  if (mask === 3) return `[${t('ph.image')}]`
  if (mask === 34) return `[${t('ph.voice')}]`
  if (mask === 43) return `[${t('ph.video')}]`
  if (mask === 47) return `[${t('ph.emoji')}]`
  if (mask === 48) return `[${t('ph.location')}]`
  if (mask === 49) return `[${t('ph.card')}]`
  // 未知类型：有正文给正文；会话摘要场景两者皆空时给空串（不显示 [?] 噪声）
  return oneLine(body)
}
function bubbleBody(m: MessageRowVO): string {
  // 气泡正文兜底：空内容时仅气泡内退回类型标注，摘要侧保持干净
  return bubblePreview(m.type_code, m.body) || `[${m.type_name || m.type_code || '?'}]`
}

function appTitle(m: MessageRowVO): string {
  return parseAppmsg(m)?.title || `[${t('ph.card')}]`
}
function appDes(m: MessageRowVO): string {
  return parseAppmsg(m)?.des || ''
}
function appReferText(m: MessageRowVO): string {
  const a = parseAppmsg(m)
  return a?.refer ? `${a.refer.name}：${a.refer.text}` : ''
}
function appFileExt(m: MessageRowVO): string {
  const e = (parseAppmsg(m)?.fileExt || 'file').toLowerCase()
  return e.length > 5 ? e.slice(0, 5) : e
}
function appFileSize(m: MessageRowVO): string {
  const n = parseAppmsg(m)?.fileLen || 0
  if (!n) return ''
  if (n < 1024) return `${n} B`
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1048576).toFixed(1)} MB`
}

/* ================= 弹层 ================= */

const msgDlgOpen = ref(false)
const msgDlg = ref<MessageRowVO | null>(null)
function openMsgDetail(m: MessageRowVO) {
  msgDlg.value = m
  msgDlgOpen.value = true
}
function copyBody() {
  if (msgDlg.value?.body) {
    navigator.clipboard?.writeText(msgDlg.value.body)
    ElMessage.success(t('common.copied'))
  }
}

const contactOpen = ref(false)
const contactInfo = ref<ContactRowVO | null>(null)
function openContact(u?: string | null) {
  if (!u) return
  const data = { username: u, nick_name: nameMap.value[u] !== u ? nameMap.value[u] : undefined,
                 big_head_url: avatarMap.value[u] } as ContactRowVO
  contactInfo.value = data
  contactOpen.value = true
  // 异步补全（命中缓存时只是确认）
  get<PageVO<ContactRowVO>>(`/v1/contacts?username=${encodeURIComponent(u)}&pageSize=1`)
    .then((p) => {
      const c = p.list.find((x) => x.username === u)
      if (c && contactOpen.value) contactInfo.value = c
      if (c) {
        nameMap.value[u] = c.remark || c.nick_name || u
        if (c.big_head_url) avatarMap.value[u] = c.big_head_url
      }
    })
    .catch(() => { contactInfo.value = { username: u } })
}

const detailOpen = ref(false)
const detailLoading = ref(false)
const members = ref<{ member_username: string }[]>([])
const membersFailed = ref(false)
function openDetail() {
  if (!activeChat.value) return
  detailOpen.value = true
  members.value = []
  membersFailed.value = false
  if (!activeChatIsGroup.value) return
  detailLoading.value = true
  get<PageVO<{ member_username: string }>>(
    `/v1/groups/${encodeURIComponent(activeChat.value)}/members?union=true&pageSize=10`)
    .then((p) => { members.value = p.list })
    .catch(() => { membersFailed.value = true })
    .finally(() => { detailLoading.value = false })
}

/* ================= 发送（SEND_MESSAGE 任务，OCR 由 wxbot-helper 执行） ================= */

interface EchoRow { id: number; chat: string; text: string; ok: boolean; note: string }
const echoes = ref<EchoRow[]>([])
const echoesOfChat = computed(() => echoes.value.filter((e) => e.chat === activeChat.value))

const drafts = ref<Record<string, string>>({})
const draft = computed({
  get: () => drafts.value[activeChat.value] || '',
  set: (v: string) => { drafts.value[activeChat.value] = v },
})
const sendBusy = ref(false)
const cmpOn = ref(false)
let cmpEndAt = 0

const sendHint = computed(() => {
  const to = dispName(activeChat.value) || activeChat.value || '-'
  return sendBusy.value ? t('chatv.sendingTo', { to }) : t('chatv.sendTo', { to })
})

function onCmpEnd() {
  cmpOn.value = false
  cmpEndAt = Date.now()
}
function onDraftEnter(e: KeyboardEvent) {
  // 组词中的 Enter = 候选选词；刚结束组词 150ms 内的 Enter 同样不发送（IME 提交用）
  if ((e as KeyboardEvent & { isComposing?: boolean }).isComposing || cmpOn.value
      || Date.now() - cmpEndAt < 150) return
  if (!e.shiftKey) {
    e.preventDefault()
    sendDraft()
  }
}

async function sendDraft() {
  const chat = activeChat.value
  const text = (drafts.value[chat] || '').trim()
  if (!chat || !text || sendBusy.value) return
  sendBusy.value = true
  try {
    const taskId = await post<number>('/v1/tasks', {
      task_type: 'SEND_MESSAGE',
      account: myAccount.value,
      target: chat,
      payload: JSON.stringify({ chat_username: chat, text }),
    })
    echoes.value.push({
      id: taskId, chat, text, ok: true,
      note: t('chatv.sentAsEcho', { id: taskId }),
    })
    drafts.value[chat] = ''
    await nextTick()
    scrollBottom()
  } catch (e) {
    echoes.value.push({
      id: Date.now(), chat, text, ok: false,
      note: t('chatv.sendFail', { msg: e instanceof ApiError ? e.message : t('err.reqFail') }),
    })
  } finally {
    sendBusy.value = false
  }
}

/* ================= 装载 ================= */

onMounted(async () => {
  try {
    const acc = await get<PageVO<AccountVO>>('/v1/accounts?page=1&pageSize=100')
    accounts.value = [...acc.list].sort((a, b) =>
      (b.last_seen_at || '').localeCompare(a.last_seen_at || ''))
  } catch { /* 账号列表失败不阻塞会话页（发送时 account 为空会被服务端拒绝） */ }
  await loadChats()
  // 深链 /chats?chat=<username>：自动选中。目标不在首页列表里（很久没动静的会话）时
  // 合成一行置顶，保证深链不会静默失效；无深链时 loadChats 已回落到首个会话
  const q = (route.query.chat as string) || ''
  if (q) {
    const hit = rows.value.find((r) => r.username === q)
    if (hit) selectChat(hit)
    else {
      rows.value = [{ username: q, is_group: q.endsWith('@chatroom') }, ...rows.value]
      prefetchNames([q])
      selectChat(rows.value[0])
    }
  }
})

// keep-alive 下切回本页时按需刷新
watch(() => route.fullPath, () => {
  if (route.path.endsWith('/chats') && !rows.value.length) reload()
})
</script>

<style scoped lang="scss">
/* 布局类样式：颜色/圆角/阴影一律走 SDK token */
/* fc-main 是 flex column；本页要 app 式固定布局：flex:1 + min-height:0 才能让
   chat-wrap 成为内部滚动容器，composer 始终钉在视口底部（否则高度随消息流增长）。 */
.chats-page { padding: 0 !important; flex: 1; min-height: 0; display: flex; flex-direction: column; }
.wx-shell { flex: 1; min-height: 0; display: flex; }

/* ---- 左栏：会话列表 ---- */
.wx-list { width: 300px; flex: none; min-height: 0; display: flex; flex-direction: column;
  border-right: 1px solid var(--app-border-light); background: var(--app-bg-card); }
.wx-list-tools { flex: none; display: flex; flex-direction: column; gap: 8px; padding: 10px;
  border-bottom: 1px solid var(--app-border-light); }
.wx-list-meta { display: flex; align-items: center; gap: 8px; }
.wx-list-meta .meta { flex: none; font-size: 12px; color: var(--app-text-tertiary); white-space: nowrap; }
.sess-scroll { flex: 1; min-height: 0; overflow-y: auto; }
.sess-item { display: flex; gap: 10px; padding: 10px 12px; cursor: pointer; align-items: center; }
.sess-item:hover { background: var(--app-bg-muted); }
.sess-item.active { background: var(--app-primary); }
.sess-item.active .sess-name { color: var(--app-on-primary); }
.sess-item.active .sess-summary { color: color-mix(in srgb, var(--app-on-primary) 85%, transparent); }
.sess-item.active .sess-time { color: color-mix(in srgb, var(--app-on-primary) 80%, transparent); }
.sess-main { flex: 1; min-width: 0; }
.sess-top { display: flex; align-items: baseline; gap: 8px; }
.sess-name { flex: 1; min-width: 0; font-size: 14px; font-weight: 500; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sess-time { flex: none; font-size: 12px; color: var(--app-text-quaternary); }
.sess-bottom { display: flex; margin-top: 3px; }
.sess-summary { flex: 1; min-width: 0; font-size: 13px; color: var(--app-text-tertiary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sess-foot { text-align: center; font-size: 12px; color: var(--app-text-quaternary); padding: 8px 0 10px; }

/* ---- 右栏：聊天区 ---- */
.wx-chat { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column;
  position: relative; background: var(--app-bg-page); }
.wx-chat-head { flex: none; min-height: 56px; display: flex; align-items: center; gap: 10px;
  padding: 6px 14px; background: var(--app-bg-card); border-bottom: 1px solid var(--app-border-light); }
.wx-chat-headmain { min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 1px; }
.wx-chat-title { font-size: 15px; font-weight: 500; color: var(--app-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.wx-chat-title:hover { text-decoration: underline; }
.wx-chat-sub { max-width: 100%; font-size: 11px; color: var(--app-text-tertiary); line-height: 1.2;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wx-chat-empty { flex: 1; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: var(--app-text-quaternary); }
.spacer { flex: 1; }

.chat-wrap { flex: 1; min-height: 0; overflow-y: auto; background: var(--app-bg-card);
  padding: 16px 24px; font-size: 14px; display: flex; flex-direction: column; }
.chat-time { text-align: center; font-size: 12px; color: var(--app-text-quaternary); margin: 12px 0; }
.chat-sys { text-align: center; font-size: 12px; color: var(--app-text-quaternary); margin: 6px 0; }
.chat-sys.send-fail { color: var(--app-color-danger); }
.chat-row { display: flex; align-items: flex-start; gap: 10px; margin: 6px 0; }
.chat-row.self { flex-direction: row-reverse; }
.chat-body { max-width: 62%; min-width: 0; }
.chat-row.self .chat-body { display: flex; flex-direction: column; align-items: flex-end; }
.chat-nick { font-size: 12px; color: var(--app-text-tertiary); margin: 0 0 2px 2px; }
.chat-bubble { display: inline-block; max-width: 100%; box-sizing: border-box;
  background: var(--app-bg-muted); border-radius: var(--app-radius-sm); padding: 9px 12px;
  line-height: 1.55; word-break: break-all; cursor: pointer; color: var(--app-text); }
.chat-row.self .chat-bubble { background: var(--app-primary); color: var(--app-on-primary); }
.chat-bubble.self-send { white-space: pre-wrap; word-break: break-word; }
.chat-bubble.revoked { font-style: italic; color: var(--app-text-tertiary); cursor: default; }
.chat-bubble.bare { background: transparent; padding: 0; }
.chat-ph { color: var(--app-text-secondary); }
.emoji-img { display: block; max-width: 120px; max-height: 120px; border-radius: var(--app-radius-sm); }
.img-ph { width: 116px; height: 87px; display: flex; align-items: center; justify-content: center;
  background: var(--app-bg-muted); border: 1px dashed var(--app-border-light);
  border-radius: var(--app-radius-sm); color: var(--app-text-tertiary); font-size: 13px; }
.voice-strip { color: var(--app-text-secondary); font-size: 14px; user-select: none; }
.quote-title { font-size: 13px; font-weight: 600; }
.quote-ref { margin-top: 6px; max-width: 100%; box-sizing: border-box; padding: 5px 8px;
  background: color-mix(in srgb, var(--app-text-quaternary) 18%, transparent);
  border-left: 3px solid var(--app-text-quaternary); border-radius: 2px;
  font-size: 11px; color: var(--app-text-secondary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.link-title { font-size: 13px; font-weight: 600; }
.link-des { margin-top: 4px; font-size: 12px; color: var(--app-text-secondary); line-height: 1.45;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.file-card { display: flex; align-items: center; gap: 10px; min-width: 190px; }
.file-ico { flex: none; width: 36px; height: 36px; box-sizing: border-box;
  border-radius: var(--app-radius-sm); background: var(--app-color-info); color: #fff;
  font-size: 10px; display: flex; align-items: center; justify-content: center;
  word-break: break-all; padding: 3px; overflow: hidden; }
.file-info { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.file-name { font-size: 13px; word-break: break-all; }
.file-size { font-size: 11px; color: var(--app-text-tertiary); }
.chat-hint { text-align: center; font-size: 12px; color: var(--app-text-quaternary); margin: 8px 0; }

/* ---- 输入区 ---- */
.chat-composer { flex: none; background: var(--app-bg-card);
  border-top: 1px solid var(--app-border-light); padding: 6px 12px 8px; }
.composer-input :deep(.el-textarea__inner) { resize: none; }
.chat-composer-foot { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.chat-composer-hint { flex: 1; min-width: 0; font-size: 11px; color: var(--app-text-quaternary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-gobottom { position: absolute; right: 20px; bottom: 140px; z-index: 5;
  width: 34px; height: 34px; box-sizing: border-box; border-radius: var(--app-radius-full);
  border: 1px solid var(--app-border-light); background: var(--app-bg-card);
  color: var(--app-text-secondary); font-size: 15px; cursor: pointer; padding: 0;
  display: flex; align-items: center; justify-content: center;
  box-shadow: var(--app-shadow-md); }
.chat-gobottom:hover { background: var(--app-bg-muted); color: var(--app-text); }

/* ---- 详情字段（弹层共用） ---- */
.cfield { display: flex; line-height: 1.9; font-size: 13px; }
.cfield .k { flex: none; width: 80px; color: var(--app-text-tertiary); font-size: 12px;
  text-align: right; margin-right: 12px; }
.cfield .v { flex: 1; min-width: 0; word-break: break-all; color: var(--app-text); }
.cfield .v.mono { font-family: monospace; font-size: 12px; }
.c-head { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 4px 0 12px; }
.c-head .c-name { font-size: 16px; font-weight: 500; color: var(--app-text); }
.c-none { color: var(--app-text-tertiary); font-size: 12px; text-align: center; margin-top: 8px; }
.c-members { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px 4px; padding: 4px 0 12px; }
.c-member { min-width: 0; display: flex; flex-direction: column; align-items: center;
  gap: 3px; cursor: pointer; }
.c-mname { max-width: 100%; font-size: 11px; color: var(--app-text-secondary); text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.msg-pre { background: var(--app-bg-muted); border-radius: var(--app-radius-sm); padding: 8px;
  white-space: pre-wrap; word-break: break-all; margin: 8px 0 0;
  font-family: Consolas, 'Courier New', monospace; font-size: 12px; color: var(--app-text); }
</style>
