<template>
  <div class="app-page">
    <FcSectionHeader :title="t('sub.title')">
      <template #actions>
        <FcButton variant="primary" :icon="Plus" @click="openCreate">{{ t('sub.create') }}</FcButton>
      </template>
    </FcSectionHeader>

    <FcFilterBar>
      <FcSelect v-model="consumerFilter" :options="consumerOptions" clearable
                :placeholder="t('sub.consumerId')" style="width: 220px" @change="load" />
    </FcFilterBar>

    <FcSection>
      <FcTable :data="rows" :loading="loading" stripe>
        <el-table-column label="ID" width="190">
          <template #default="{ row }"><span class="mono">{{ short(row.id) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('sub.consumerId')" width="190">
          <template #default="{ row }"><span class="mono">{{ short(row.consumer_id) }}</span></template>
        </el-table-column>
        <el-table-column prop="name" :label="t('col.name')" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('sub.deliveryMode')" width="120">
          <template #default="{ row }">
            <FcTag :color="row.delivery_mode === 'WEBHOOK' ? 'warning' : 'gray'" size="sm">
              {{ row.delivery_mode === 'WEBHOOK' ? t('sub.webhook') : t('sub.poll') }}
            </FcTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('sub.filter')" min-width="180">
          <template #default="{ row }">
            <span class="mono filter-cell">{{ filterText(row.filter) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('sub.enabled')" width="90">
          <template #default="{ row }">
            <FcSwitch :model-value="isEnabled(row)" @update:model-value="((v: string | number | boolean) => toggle(row, Boolean(v)))" />
          </template>
        </el-table-column>
        <el-table-column :label="t('col.actions')" fixed="right" width="140">
          <template #default="{ row }">
            <FcButton variant="text" size="sm" @click="openDeliveries(row)">{{ t('dlv.title') }}</FcButton>
            <FcButton variant="text" size="sm" color="danger" @click="onDelete(row)">{{ t('common.delete') }}</FcButton>
          </template>
        </el-table-column>
      </FcTable>
      <FcPagination v-model:current-page="page" v-model:page-size="pageSize" :total="total"
                    style="margin-top: 12px; justify-content: flex-end" @change="load" />
    </FcSection>

    <!-- 投递明细 -->
    <FcDrawer v-model:open="dlvOpen" :title="t('dlv.titleOf', { name: dlvSubName })" size="820px" append-to-body>
      <template v-if="dlvOpen">
      <FcFilterBar>
        <FcSegmented v-model="dlvStatus" :options="dlvStatusOptions" @change="dlvPage = 1; loadDeliveries()" />
        <FcButton variant="secondary" size="sm" @click="onRequeueDead">{{ t('dlv.requeueDead') }}</FcButton>
      </FcFilterBar>
      <FcTable :data="deliveries" :loading="dlvLoading" stripe>
        <el-table-column label="ID" width="110">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column :label="t('col.status')" width="120">
          <template #default="{ row }">
            <FcTag :color="dlvColor(row.status)" size="sm">{{ row.status }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('task.attempts')" width="90">
          <template #default="{ row }">{{ row.attempts }}/{{ row.max_attempts }}</template>
        </el-table-column>
        <el-table-column :label="t('dlv.responseCode')" width="110">
          <template #default="{ row }">{{ row.response_code ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('task.error')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.error || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('tok.expiresAt')" width="160">
          <template #default="{ row }">{{ fmtTime(row.delivered_at || row.next_attempt_at || row.created_at) }}</template>
        </el-table-column>
        <el-table-column :label="t('col.actions')" fixed="right" width="90">
          <template #default="{ row }">
            <FcButton v-if="row.status === 'DELIVERED'" variant="text" size="sm"
                      @click="onRedeliver(row)">{{ t('dlv.redeliver') }}</FcButton>
          </template>
        </el-table-column>
      </FcTable>
      <FcPagination v-model:current-page="dlvPage" v-model:page-size="dlvPageSize" :total="dlvTotal"
                    style="margin-top: 12px; justify-content: flex-end" @change="loadDeliveries" />
      </template>
    </FcDrawer>

    <!-- 新建订阅 -->
    <FcDialog v-model:open="createOpen" :title="t('sub.create')" width="560px" append-to-body>
      <el-form :model="form" label-width="130px">
        <FcFormItem :label="t('sub.consumerId')" required>
          <FcSelect v-model="form.consumerId" :options="consumerOptions" style="width: 100%" />
        </FcFormItem>
        <FcFormItem :label="t('sub.name')" required>
          <FcInput v-model="form.name" maxlength="64" />
        </FcFormItem>
        <FcFormItem :label="t('sub.deliveryMode')" required>
          <FcSegmented v-model="form.deliveryMode" :options="modeOptions" />
        </FcFormItem>
        <FcFormItem v-if="form.deliveryMode === 'WEBHOOK'" :label="t('sub.webhookUrl')">
          <FcInput v-model="form.webhookUrl" placeholder="https://..." />
        </FcFormItem>
        <FcFormItem :label="t('sub.filterJson')" required>
          <FcInput v-model="form.filterJson" type="textarea" :rows="4" placeholder='{"keyword":"1"}' />
        </FcFormItem>
        <FcFormItem :label="t('sub.visibleSeconds')">
          <FcInput v-model.number="form.visibleSeconds" type="number" />
        </FcFormItem>
        <FcFormItem :label="t('sub.maxAttempts')">
          <FcInput v-model.number="form.maxAttempts" type="number" />
        </FcFormItem>
        <FcFormItem :label="t('sub.backoffBase')">
          <FcInput v-model.number="form.backoffBase" type="number" />
        </FcFormItem>
      </el-form>
      <template #footer>
        <FcButton @click="createOpen = false">{{ t('common.cancel') }}</FcButton>
        <FcButton variant="primary" :loading="saving" @click="onCreate">{{ t('common.save') }}</FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  FcSectionHeader, FcSection, FcFilterBar, FcTable, FcTag, FcSwitch, FcButton, FcSelect,
  FcPagination, FcDialog, FcFormItem, FcInput, FcSegmented,
} from '@/components/sdk'
import { get, post, put, del } from '@/api/client'

interface DeliveryRow {
  id: string; status: string; attempts?: number; max_attempts?: number
  response_code?: number; error?: string; delivered_at?: string
  next_attempt_at?: string; created_at?: string
}
import type { PageVO, SubscriptionVO, ConsumerVO } from '@/api/types'

const { t } = useI18n()
const rows = ref<SubscriptionVO[]>([])
const consumers = ref<ConsumerVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const consumerFilter = ref('')

const createOpen = ref(false)
const saving = ref(false)
const form = ref({
  consumerId: '', name: '', deliveryMode: 'POLL', webhookUrl: '',
  filterJson: '{\n  "keyword": "1"\n}', visibleSeconds: 60, maxAttempts: 3, backoffBase: 5,
})

const modeOptions = [
  { label: t('sub.poll'), value: 'POLL' },
  { label: t('sub.webhook'), value: 'WEBHOOK' },
]
const consumerOptions = ref<{ label: string; value: string }[]>([])

function short(id: string) { return (id || '').slice(0, 13) + (id && id.length > 13 ? '…' : '') }
function isEnabled(row: SubscriptionVO) { return row.enabled === 1 || row.enabled === true }
function filterText(f?: Record<string, unknown> | string) {
  if (!f) return '{}'
  if (typeof f === 'string') {
    try { return JSON.stringify(JSON.parse(f)) } catch { return f }
  }
  return JSON.stringify(f)
}

function syncConsumerOptions() {
  consumerOptions.value = [
    { label: t('common.all'), value: '' },
    ...consumers.value.map((c) => ({ label: `${c.name} (${c.id.slice(0, 8)}…)`, value: c.id })),
  ]
}

async function load() {
  loading.value = true
  try {
    const q = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize.value) })
    if (consumerFilter.value) q.set('consumerId', consumerFilter.value)
    const data = await get<PageVO<SubscriptionVO>>(`/v1/subscriptions?${q}`)
    rows.value = data.list
    total.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loading.value = false
  }
}

async function loadConsumers() {
  try {
    const data = await get<PageVO<ConsumerVO>>('/v1/consumers?page=1&pageSize=100')
    consumers.value = data.list
    syncConsumerOptions()
  } catch { /* 列表页可独立于消费者页使用 */ }
}

function openCreate() {
  form.value.consumerId = consumers.value[0]?.id || ''
  createOpen.value = true
}

async function onCreate() {
  let filter: Record<string, unknown>
  try { filter = JSON.parse(form.value.filterJson || '{}') } catch {
    ElMessage.error(t('sub.filterJson')); return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      consumer_id: form.value.consumerId,
      name: form.value.name,
      filter,
      delivery_mode: form.value.deliveryMode,
      visible_seconds: form.value.visibleSeconds,
      max_attempts: form.value.maxAttempts,
      backoff_base_seconds: form.value.backoffBase,
    }
    if (form.value.deliveryMode === 'WEBHOOK' && form.value.webhookUrl) body.webhook_url = form.value.webhookUrl
    const res = await post<{ subscription: SubscriptionVO; already_exists: boolean }>('/v1/subscriptions', body)
    ElMessage.success(res.already_exists ? t('sub.alreadyExists') : t('sub.createdOk'))
    createOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    saving.value = false
  }
}

async function toggle(row: SubscriptionVO, v: boolean) {
  try {
    await put(`/v1/subscriptions/${row.id}`, { enabled: v ? 1 : 0 })
    row.enabled = v ? 1 : 0
    ElMessage.success(t('sub.updatedOk'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

async function onDelete(row: SubscriptionVO) {
  try {
    await del(`/v1/subscriptions/${row.id}`)
    ElMessage.success(t('sub.deletedOk'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

// ---- 投递明细 ----
const dlvOpen = ref(false)
const dlvLoading = ref(false)
const dlvSubId = ref('')
const dlvSubName = ref('')
const dlvStatus = ref('')
const dlvPage = ref(1)
const dlvPageSize = ref(20)
const dlvTotal = ref(0)
const deliveries = ref<DeliveryRow[]>([])

const dlvStatusOptions = [
  { label: t('common.all'), value: '' },
  { label: 'PENDING', value: 'PENDING' },
  { label: 'DELIVERED', value: 'DELIVERED' },
  { label: 'DEAD', value: 'DEAD' },
]

function dlvColor(s: string) {
  return s === 'DELIVERED' ? 'success' : s === 'DEAD' ? 'danger' : s === 'PENDING' ? 'gray' : 'warning'
}
function fmtTime(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '—' }

function openDeliveries(row: SubscriptionVO) {
  dlvSubId.value = row.id
  dlvSubName.value = row.name || row.id
  dlvStatus.value = ''
  dlvPage.value = 1
  dlvOpen.value = true
  loadDeliveries()
}

async function loadDeliveries() {
  dlvLoading.value = true
  try {
    const q = new URLSearchParams({ page: String(dlvPage.value), page_size: String(dlvPageSize.value) })
    if (dlvStatus.value) q.set('status', dlvStatus.value)
    const data = await get<PageVO<DeliveryRow>>(`/v1/subscriptions/${dlvSubId.value}/deliveries?${q}`)
    deliveries.value = data.list
    dlvTotal.value = data.total
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    dlvLoading.value = false
  }
}

async function onRedeliver(row: DeliveryRow) {
  try {
    await post(`/v1/subscriptions/deliveries/${row.id}/redeliver`)
    ElMessage.success(t('dlv.redeliverOk'))
    await loadDeliveries()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

async function onRequeueDead() {
  try {
    const n = await post<number>(`/v1/subscriptions/${dlvSubId.value}/requeue-dead`)
    ElMessage.success(t('dlv.requeueOk', { n: n ?? 0 }))
    await loadDeliveries()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

onMounted(async () => { await loadConsumers(); await load() })
</script>

<style scoped lang="scss">
.mono { font-family: monospace; font-size: 12px; }
.filter-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-block; }
</style>
