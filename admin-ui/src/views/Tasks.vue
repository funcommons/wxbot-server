<template>
  <div class="app-page">
    <FcSectionHeader :title="t('task.title')" />

    <FcFilterBar>
      <FcSegmented v-model="status" :options="statusOptions" @change="page = 1; load()" />
      <FcInput v-model="kw" :placeholder="t('common.search')" clearable style="width: 200px" />
    </FcFilterBar>

    <FcSection>
      <FcTable :data="filtered" :loading="loading" stripe>
        <el-table-column label="ID" width="90">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column prop="task_type" :label="t('task.type')" width="150" />
        <el-table-column :label="t('col.status')" width="120">
          <template #default="{ row }">
            <FcTag :color="statusColor(row.status)" size="sm">{{ row.status }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column prop="target" :label="t('task.target')" width="120" />
        <el-table-column :label="t('task.attempts')" width="90">
          <template #default="{ row }">{{ row.attempts }}</template>
        </el-table-column>
        <el-table-column :label="t('task.payload')" min-width="220">
          <template #default="{ row }">
            <span class="mono payload-cell">{{ row.payload }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('tok.createdAt')" width="170">
          <template #default="{ row }">{{ fmt(row.created_at) }}</template>
        </el-table-column>
        <el-table-column :label="t('col.actions')" fixed="right" width="130">
          <template #default="{ row }">
            <FcButton variant="text" size="sm" @click="openRuns(row)">{{ t('task.runs') }}</FcButton>
            <FcButton v-if="row.status === 'DEAD'" variant="text" size="sm"
                      @click="onReset(row)">{{ t('task.reset') }}</FcButton>
            <FcButton v-if="row.status === 'PENDING' || row.status === 'DISPATCHED'" variant="text"
                      size="sm" color="danger" @click="onCancel(row)">{{ t('common.delete') }}</FcButton>
          </template>
        </el-table-column>
      </FcTable>
      <FcPagination v-model:current-page="page" v-model:page-size="pageSize" :total="total"
                    style="margin-top: 12px; justify-content: flex-end" @change="load" />
    </FcSection>

    <!-- 执行历史 -->
    <FcDrawer v-model:open="runsOpen" :title="t('task.runsTitle', { id: runsTaskId })" size="640px" append-to-body>
      <FcTable :data="runs" :loading="runsLoading" stripe>
        <el-table-column prop="attempt_no" :label="t('task.attempts')" width="80" />
        <el-table-column :label="t('col.status')" width="110">
          <template #default="{ row }">
            <FcTag :color="runColor(row.outcome)" size="sm">{{ row.outcome }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('task.error')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.error || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('task.result')" min-width="220">
          <template #default="{ row }">
            <span class="mono payload-cell">{{ row.result || '—' }}</span>
          </template>
        </el-table-column>
      </FcTable>
    </FcDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionHeader, FcSection, FcFilterBar, FcTable, FcTag, FcButton,
         FcPagination, FcDrawer, FcInput, FcSegmented } from '@/components/sdk'
import { get, post } from '@/api/client'
import type { PageVO } from '@/api/types'

interface TaskVO {
  id: string; task_type: string; status: string; target?: string
  attempts?: number; payload?: string; created_at?: string; account?: string
}
interface TaskRunVO {
  attempt_no?: number; outcome: string; error?: string; result?: string
}

const { t } = useI18n()
const rows = ref<TaskVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const status = ref('')
const kw = ref('')

const statusOptions = [
  { label: t('common.all'), value: '' },
  { label: 'PENDING', value: 'PENDING' },
  { label: 'DISPATCHED', value: 'DISPATCHED' },
  { label: 'SUCCEEDED', value: 'SUCCEEDED' },
  { label: 'FAILED', value: 'FAILED' },
  { label: 'DEAD', value: 'DEAD' },
  { label: 'CANCELLED', value: 'CANCELLED' },
]

const runsOpen = ref(false)
const runsLoading = ref(false)
const runsTaskId = ref('')
const runs = ref<TaskRunVO[]>([])

function statusColor(s: string) {
  return s === 'SUCCEEDED' ? 'success' : s === 'DEAD' || s === 'FAILED' ? 'danger'
       : s === 'DISPATCHED' ? 'warning' : 'gray'
}
function runColor(o: string) { return o === 'SUCCEEDED' ? 'success' : o === 'LOST' ? 'warning' : 'danger' }
function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '—' }

const filtered = computed(() =>
  kw.value ? rows.value.filter((r) => JSON.stringify(r).toLowerCase().includes(kw.value.toLowerCase())) : rows.value)

async function load() {
  loading.value = true
  try {
    const q = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize.value) })
    if (status.value) q.set('status', status.value)
    const data = await get<PageVO<TaskVO>>(`/v1/tasks?${q}`)
    rows.value = data.list
    total.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loading.value = false
  }
}

async function openRuns(row: TaskVO) {
  runsTaskId.value = row.id
  runsOpen.value = true
  runsLoading.value = true
  try {
    const data = await get<PageVO<TaskRunVO>>(`/v1/task-runs?taskId=${row.id}&page=1&pageSize=50`)
    runs.value = data.list
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    runsLoading.value = false
  }
}

async function onReset(row: TaskVO) {
  try {
    await post(`/v1/tasks/${row.id}/reset`)
    ElMessage.success(t('task.resetOk'))
    await load()
  } catch (e) { ElMessage.error((e as Error).message || t('err.reqFail')) }
}

async function onCancel(row: TaskVO) {
  try {
    await post(`/v1/tasks/${row.id}/cancel`)
    ElMessage.success(t('task.cancelOk'))
    await load()
  } catch (e) { ElMessage.error((e as Error).message || t('err.reqFail')) }
}

onMounted(load)
</script>

<style scoped lang="scss">
.mono { font-family: monospace; font-size: 12px; }
.payload-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-block; }
</style>
