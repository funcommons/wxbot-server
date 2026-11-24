<template>
  <div class="app-page">
    <FcSectionHeader :title="t('cons.title')">
      <template #actions>
        <FcButton variant="primary" :icon="Plus" @click="createOpen = true">{{ t('cons.create') }}</FcButton>
      </template>
    </FcSectionHeader>

    <FcSection>
      <FcTable :data="rows" :loading="loading" stripe>
        <el-table-column label="ID" width="190">
          <template #default="{ row }"><span class="mono">{{ short(row.id) }}</span></template>
        </el-table-column>
        <el-table-column prop="name" :label="t('col.name')" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('cons.enablePoll')" width="110">
          <template #default="{ row }">
            <FcTag :color="row.enable_poll ? 'success' : 'gray'" size="sm">{{ boolText(row.enable_poll) }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('cons.enableWebhook')" width="140">
          <template #default="{ row }">
            <FcTag :color="row.enable_webhook ? 'success' : 'gray'" size="sm">{{ boolText(row.enable_webhook) }}</FcTag>
          </template>
        </el-table-column>
        <el-table-column prop="webhook_url" :label="t('cons.webhookUrl')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="remark" :label="t('col.remark')" min-width="120" show-overflow-tooltip />
        <el-table-column :label="t('col.actions')" fixed="right" width="90">
          <template #default="{ row }">
            <FcButton variant="text" size="sm" color="danger" @click="onDelete(row)">{{ t('common.delete') }}</FcButton>
          </template>
        </el-table-column>
      </FcTable>
      <FcPagination v-model:current-page="page" v-model:page-size="pageSize" :total="total"
                    style="margin-top: 12px; justify-content: flex-end" @change="load" />
    </FcSection>

    <!-- 新建消费者 -->
    <FcDialog v-model:open="createOpen" :title="t('cons.create')" width="520px" append-to-body>
      <el-form :model="form" label-width="140px">
        <FcFormItem :label="t('cons.name')" required>
          <FcInput v-model="form.name" maxlength="64" />
        </FcFormItem>
        <FcFormItem :label="t('cons.enablePoll')">
          <FcSwitch v-model="form.enablePoll" />
        </FcFormItem>
        <FcFormItem :label="t('cons.enableWebhook')">
          <FcSwitch v-model="form.enableWebhook" />
        </FcFormItem>
        <FcFormItem v-if="form.enableWebhook" :label="t('cons.webhookUrl')">
          <FcInput v-model="form.webhookUrl" placeholder="https://..." />
        </FcFormItem>
        <FcFormItem :label="t('col.remark')">
          <FcInput v-model="form.remark" maxlength="128" />
        </FcFormItem>
      </el-form>
      <template #footer>
        <FcButton @click="createOpen = false">{{ t('common.cancel') }}</FcButton>
        <FcButton variant="primary" :loading="saving" @click="onCreate">{{ t('common.save') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 明文凭据仅此一次 -->
    <FcDialog v-model:open="credOpen" :title="t('common.showOnce')" width="640px" append-to-body>
      <template v-if="cred">
        <div v-if="cred.poll_token" class="cred-row">
          <div class="cred-label">{{ t('cons.pollToken') }}</div>
          <div class="cred-value mono">{{ cred.poll_token }}</div>
          <FcButton variant="text" size="sm" @click="copy(cred.poll_token)">{{ t('common.copy') }}</FcButton>
        </div>
        <div v-if="cred.webhook_secret" class="cred-row">
          <div class="cred-label">{{ t('cons.webhookSecret') }}</div>
          <div class="cred-value mono">{{ cred.webhook_secret }}</div>
          <FcButton variant="text" size="sm" @click="copy(cred.webhook_secret)">{{ t('common.copy') }}</FcButton>
        </div>
      </template>
      <template #footer>
        <FcButton variant="primary" @click="credOpen = false">{{ t('common.confirm') }}</FcButton>
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
  FcSectionHeader, FcSection, FcTable, FcTag, FcButton, FcPagination,
  FcDialog, FcFormItem, FcInput, FcSwitch,
} from '@/components/sdk'
import { get, post, del } from '@/api/client'
import type { PageVO, ConsumerVO, ConsumerCreatedVO } from '@/api/types'

const { t } = useI18n()
const rows = ref<ConsumerVO[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const createOpen = ref(false)
const saving = ref(false)
const form = ref({ name: '', enablePoll: true, enableWebhook: false, webhookUrl: '', remark: '' })

const credOpen = ref(false)
const cred = ref<{ poll_token?: string; webhook_secret?: string } | null>(null)

function short(id: string) { return (id || '').slice(0, 13) + (id && id.length > 13 ? '…' : '') }
function boolText(v?: boolean) { return v ? t('common.yes') : t('common.no') }

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('common.copied'))
  } catch { /* 剪贴板权限拒绝时静默 */ }
}

async function load() {
  loading.value = true
  try {
    const data = await get<PageVO<ConsumerVO>>(`/v1/consumers?page=${page.value}&pageSize=${pageSize.value}`)
    rows.value = data.list
    total.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.value.name.trim()) return
  saving.value = true
  try {
    const res = await post<ConsumerCreatedVO>('/v1/consumers', {
      name: form.value.name,
      enable_poll: form.value.enablePoll,
      enable_webhook: form.value.enableWebhook,
      webhook_url: form.value.webhookUrl || undefined,
      remark: form.value.remark || undefined,
    })
    createOpen.value = false
    ElMessage.success(t('cons.createdOk'))
    cred.value = { poll_token: res.poll_token, webhook_secret: res.webhook_secret }
    credOpen.value = true
    form.value = { name: '', enablePoll: true, enableWebhook: false, webhookUrl: '', remark: '' }
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    saving.value = false
  }
}

async function onDelete(row: ConsumerVO) {
  try {
    await del(`/v1/consumers/${row.id}`)
    ElMessage.success(t('cons.deletedOk'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.mono { font-family: monospace; font-size: 12px; }
.cred-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.cred-label { min-width: 130px; font-size: 13px; color: var(--app-text-secondary); }
.cred-value { flex: 1; word-break: break-all; color: var(--app-text); }
</style>
