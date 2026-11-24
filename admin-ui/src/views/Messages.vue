<template>
  <div class="app-page">
    <FcSectionHeader :title="t('msg.title')" />

    <FcSection>
      <FcTable :data="chats" :loading="loadingChats" stripe @row-click="(r: ChatRow) => openChat(r)">
        <el-table-column prop="username" :label="t('msg.chatId')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="display_name" :label="t('msg.displayName')" min-width="160" />
        <el-table-column :label="t('msg.isGroup')" width="100">
          <template #default="{ row }">
            <FcTag :color="row.is_group ? 'warning' : 'gray'" size="sm">{{ row.is_group ? t('common.yes') : t('common.no') }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column prop="message_count" :label="t('msg.messageCount')" width="120" />
      </FcTable>
      <FcPagination v-model:current-page="chatPage" v-model:page-size="chatPageSize" :total="chatTotal"
                    style="margin-top: 12px; justify-content: flex-end" @change="loadChats" />
    </FcSection>

    <!-- 消息浏览 -->
    <FcDrawer v-model:open="msgOpen" :title="t('msg.ofChat', { chat: activeChat })" width="720px" append-to-body>
      <FcTable :data="msgs" :loading="loadingMsgs" stripe>
        <el-table-column :label="t('msg.time')" width="170">
          <template #default="{ row }">{{ fmt(row.create_time) }}</template>
        </el-table-column>
        <el-table-column prop="sender_username" :label="t('msg.sender')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="type_name" :label="t('msg.type')" width="90" />
        <el-table-column :label="t('msg.body')" min-width="280">
          <template #default="{ row }">
            <span class="body-cell">{{ row.body || '—' }}</span>
          </template>
        </el-table-column>
      </FcTable>
      <div class="load-more">
        <FcButton variant="secondary" size="sm" :loading="loadingMsgs"
                  :disabled="!msgHasMore" @click="loadMsgs">
          {{ msgHasMore ? t('msg.loadMore') : t('msg.noMore') }}
        </FcButton>
      </div>
    </FcDrawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionHeader, FcSection, FcTable, FcTag, FcPagination, FcDrawer } from '@/components/sdk'
import { get } from '@/api/client'
import type { PageVO } from '@/api/types'

interface ChatRow {
  username: string; display_name?: string; is_group?: boolean
  message_count?: number; last_message_time?: string
}
interface MsgRow {
  id: string; sender_username?: string; type_name?: string
  body?: string; create_time?: string; is_revoked?: boolean
}

const { t } = useI18n()
const chats = ref<ChatRow[]>([])
const loadingChats = ref(false)
const chatPage = ref(1)
const chatPageSize = ref(20)
const chatTotal = ref(0)

const msgOpen = ref(false)
const loadingMsgs = ref(false)
const activeChat = ref('')
const msgs = ref<MsgRow[]>([])
const msgCursor = ref('')
const msgHasMore = ref(false)

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '—' }

async function loadChats() {
  loadingChats.value = true
  try {
    const data = await get<PageVO<ChatRow>>(`/v1/chats?page=${chatPage.value}&pageSize=${chatPageSize.value}`)
    chats.value = data.list
    chatTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingChats.value = false
  }
}

async function openChat(row: ChatRow) {
  activeChat.value = row.username
  msgOpen.value = true
  await loadMsgs(false)
}

// 游标分页（keyset 倒序：先到的是最新消息），加载更多向尾部追加
async function loadMsgs(append = false) {
  loadingMsgs.value = true
  try {
    const q = new URLSearchParams({ chatUsername: activeChat.value, limit: '50' })
    if (append && msgCursor.value) q.set('cursor', msgCursor.value)
    const data = await get<{ list: MsgRow[]; next_cursor?: string; has_more?: boolean }>(`/v1/messages?${q}`)
    msgs.value = append ? [...msgs.value, ...(data.list || [])] : (data.list || [])
    msgCursor.value = data.next_cursor || ''
    msgHasMore.value = Boolean(data.has_more)
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingMsgs.value = false
  }
}

onMounted(loadChats)
</script>

<style scoped lang="scss">
.body-cell { white-space: pre-wrap; word-break: break-all; font-size: 12px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
