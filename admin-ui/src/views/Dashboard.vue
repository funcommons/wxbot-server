<template>
  <div class="app-page">
    <FcSectionHeader :title="t('dash.title')">
      <template #actions>
        <FcButton variant="secondary" size="sm" :icon="Refresh" @click="loadAll">{{ t('dash.refreshNow') }}</FcButton>
      </template>
    </FcSectionHeader>

    <FcSection>
      <FcSkeleton v-if="loading" variant="card" :rows="4" />
      <template v-else>
        <div class="stat-grid">
          <FcSectionCard v-for="card in cards" :key="card.label">
            <div class="stat-label">{{ card.label }}</div>
            <div class="stat-value">{{ card.value }}</div>
          </FcSectionCard>
        </div>
        <div class="token-row">
          <FcStatusBadge tone="success" :label="t('dash.tokenState') + ': ' + t('dash.tokenOk')" />
        </div>
      </template>
    </FcSection>

    <!-- 最近摄入批次 -->
    <FcSection>
      <div class="sec-title">{{ t('dash.batches') }}</div>
      <FcTable :data="batches" :loading="loadingBatches" stripe size="small">
        <el-table-column :label="t('dash.startedAt')" width="170">
          <template #default="{ row }">{{ fmt(row.started_at) }}</template>
        </el-table-column>
        <el-table-column prop="account" label="Account" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('dash.batch')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ short(row.batch_ref) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('dash.msgs')" width="90">
          <template #default="{ row }">{{ row.n_messages ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('dash.inserted')" width="90">
          <template #default="{ row }">{{ row.n_inserted ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('dash.merged')" width="90">
          <template #default="{ row }">{{ row.n_merged ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('dash.duration')" width="100">
          <template #default="{ row }">{{ row.duration_ms ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('col.status')" width="110">
          <template #default="{ row }">
            <FcTag :color="statusColor(row.status)" size="sm">{{ row.status || '—' }}</FcTag>
          </template>
        </el-table-column>
      </FcTable>
    </FcSection>

    <!-- 消息去重 -->
    <FcSection>
      <div class="sec-title">{{ t('dash.dedupTitle') }}</div>
      <FcTable :data="dedupRows" :loading="loadingDedup" stripe size="small">
        <el-table-column :label="t('msg.time')" width="170">
          <template #default="{ row }">{{ fmt(row.create_time) }}</template>
        </el-table-column>
        <el-table-column prop="chat_username" :label="t('msg.chatId')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="type_name" :label="t('msg.type')" width="100" />
        <el-table-column prop="sender_username" :label="t('msg.sender')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('dash.sources')" width="90">
          <template #default="{ row }">{{ row.source_accounts ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('dash.dedupKey')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.dedup_key }}</span></template>
        </el-table-column>
      </FcTable>
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionHeader, FcSection, FcSectionCard, FcSkeleton, FcButton, FcStatusBadge, FcTable, FcTag } from '@/components/sdk'
import { get } from '@/api/client'
import type { PageVO } from '@/api/types'

interface BatchRow {
  started_at?: string; account?: string; batch_ref?: string
  n_messages?: number; n_inserted?: number; n_merged?: number; duration_ms?: number; status?: string
}
interface DedupRow {
  message_id?: string; chat_username?: string; dedup_key?: string; sender_username?: string
  create_time?: string; type_name?: string; source_accounts?: number
}

const { t } = useI18n()
const loading = ref(true)
const counts = ref({ accounts: 0, consumers: 0, subscriptions: 0, chats: 0, tasks: 0, contacts: 0 })

const batches = ref<BatchRow[]>([])
const loadingBatches = ref(false)
const dedupRows = ref<DedupRow[]>([])
const loadingDedup = ref(false)

const cards = computed(() => [
  { label: t('dash.accounts'), value: counts.value.accounts },
  { label: t('dash.consumers'), value: counts.value.consumers },
  { label: t('dash.subscriptions'), value: counts.value.subscriptions },
  { label: t('dash.sessions'), value: counts.value.chats },
  { label: t('dash.tasks'), value: counts.value.tasks },
  { label: t('dash.contacts'), value: counts.value.contacts },
])

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '—' }
function short(v?: string) { return v ? (v.length > 18 ? v.slice(0, 15) + '…' : v) : '—' }
function statusColor(s?: string) {
  const v = (s || '').toUpperCase()
  return v.includes('FAIL') || v.includes('ERROR') ? 'danger'
       : v.includes('OK') || v.includes('DONE') || v.includes('COMPLETE') || v.includes('SUCCESS') ? 'success'
       : v ? 'warning' : 'gray'
}

async function loadAll() {
  loading.value = true
  try {
    const [a, c, s, ch, tk, ct] = await Promise.all([
      get<PageVO<unknown>>('/v1/accounts?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/consumers?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/subscriptions?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/chats?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/tasks?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/contacts?page=1&pageSize=1'),
    ])
    counts.value = {
      accounts: a.total, consumers: c.total, subscriptions: s.total,
      chats: ch.total, tasks: tk.total, contacts: ct.total,
    }
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loading.value = false
  }
  loadBatches()
  loadDedup()
}

async function loadBatches() {
  loadingBatches.value = true
  try {
    const data = await get<PageVO<BatchRow>>('/v1/ingest-batches?page=1&pageSize=8')
    batches.value = data.list
  } catch { /* 概览区块失败不打断 */ } finally { loadingBatches.value = false }
}

async function loadDedup() {
  loadingDedup.value = true
  try {
    const data = await get<PageVO<DedupRow>>('/v1/statistics/dedup?page=1&pageSize=8')
    dedupRows.value = data.list
  } catch { /* 概览区块失败不打断 */ } finally { loadingDedup.value = false }
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.stat-label { font-size: 12px; color: var(--app-text-secondary); }
.stat-value { font-size: 28px; font-weight: 700; color: var(--app-text); margin-top: 4px; }
.token-row { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.sec-title { font-size: 14px; font-weight: 600; color: var(--app-text); margin-bottom: 12px; }
.mono { font-family: monospace; font-size: 12px; }
</style>
