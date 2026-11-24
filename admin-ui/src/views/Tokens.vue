<template>
  <div class="app-page">
    <FcSectionHeader :title="t('tok.title')" />

    <FcTabsPanel v-model="tab" :tabs="tabs">
      <template #tab-accounts>
        <FcFilterBar>
          <FcInput v-model="kw" :placeholder="t('common.search')" clearable @enter="accPage = 1; loadAccounts()" />
        </FcFilterBar>
        <FcSection>
          <FcTable :data="accountRows" :loading="loadingAccounts" stripe>
            <el-table-column prop="account" :label="t('tok.account')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="wxid" :label="t('tok.wxid')" min-width="160" show-overflow-tooltip />
            <el-table-column prop="hostname" :label="t('tok.hostname')" width="140" />
            <el-table-column :label="t('tok.lastSeen')" width="170">
              <template #default="{ row }">{{ fmt(row.last_seen_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('tok.issuedAt')" width="170">
              <template #default="{ row }">{{ fmt(row.created_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('col.actions')" fixed="right" width="170">
              <template #default="{ row }">
                <FcButton variant="text" size="sm" @click="openIssue(row.account)">{{ t('tok.issueToken') }}</FcButton>
                <FcButton variant="text" size="sm" color="danger" @click="onDeleteAccount(row)">{{ t('common.delete') }}</FcButton>
              </template>
            </el-table-column>
          </FcTable>
          <FcPagination v-model:current-page="accPage" v-model:page-size="accPageSize" :total="accTotal"
                        style="margin-top: 12px; justify-content: flex-end" @change="loadAccounts" />
        </FcSection>
      </template>

      <template #tab-regcodes>
        <FcSection>
          <FcFilterBar>
            <FcButton variant="primary" :icon="Plus" @click="codeOpen = true">{{ t('common.create') }}</FcButton>
          </FcFilterBar>
          <FcTable :data="codeRows" :loading="loadingCodes" stripe>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="label" :label="t('tok.label')" min-width="120" show-overflow-tooltip />
            <el-table-column prop="account" :label="t('tok.preBind')" min-width="180" show-overflow-tooltip />
            <el-table-column :label="t('tok.expiresAt')" width="170">
              <template #default="{ row }">{{ fmt(row.expires_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('tok.usedBy')" min-width="180">
              <template #default="{ row }">{{ row.used_by || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('tok.usedAt')" width="170">
              <template #default="{ row }">{{ fmt(row.used_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('col.actions')" fixed="right" width="90">
              <template #default="{ row }">
                <FcButton variant="text" size="sm" color="danger" @click="onRevoke(row)">{{ t('common.revoke') }}</FcButton>
              </template>
            </el-table-column>
          </FcTable>
          <FcPagination v-model:current-page="codePage" v-model:page-size="codePageSize" :total="codeTotal"
                        style="margin-top: 12px; justify-content: flex-end" @change="loadCodes" />
        </FcSection>
      </template>
    </FcTabsPanel>

    <!-- 签发账号令牌（仅此一次） -->
    <FcDialog v-model:open="issueOpen" :title="t('tok.tokenIssued')" width="680px" append-to-body>
      <template v-if="issued">
        <div class="cred-row">
          <div class="cred-label">{{ t('tok.accessToken') }}</div>
          <div class="cred-value mono">{{ issued.access_token }}</div>
          <FcButton variant="text" size="sm" @click="copy(issued.access_token)">{{ t('common.copy') }}</FcButton>
        </div>
        <div class="cred-row">
          <div class="cred-label">{{ t('tok.refreshToken') }}</div>
          <div class="cred-value mono">{{ issued.refresh_token }}</div>
          <FcButton variant="text" size="sm" @click="copy(issued.refresh_token)">{{ t('common.copy') }}</FcButton>
        </div>
        <div class="cred-label">{{ t('tok.expiresAt') }}: {{ issued.expires_at || '—' }}</div>
      </template>
      <template #footer>
        <FcButton variant="primary" @click="issueOpen = false">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 签发参数 -->
    <FcDialog v-model:open="issueFormOpen" :title="t('tok.issueToken')" width="460px" append-to-body>
      <el-form :model="issueForm" label-width="120px">
        <FcFormItem :label="t('tok.ttl')">
          <FcSelect v-model="issueForm.ttl" :options="ttlOptions" style="width: 100%" />
        </FcFormItem>
      </el-form>
      <template #footer>
        <FcButton @click="issueFormOpen = false">{{ t('common.cancel') }}</FcButton>
        <FcButton variant="primary" :loading="saving" @click="onIssue">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 注册账号 -->
    <FcDialog v-model:open="registerOpen" :title="t('tok.register')" width="460px" append-to-body>
      <el-form :model="registerForm" label-width="140px">
        <FcFormItem :label="t('tok.account')" required>
          <FcInput v-model="registerForm.account" placeholder="wxid_xxx_8316" />
        </FcFormItem>
        <FcFormItem :label="t('tok.hostnameField')">
          <FcInput v-model="registerForm.hostname" />
        </FcFormItem>
      </el-form>
      <template #footer>
        <FcButton @click="registerOpen = false">{{ t('common.cancel') }}</FcButton>
        <FcButton variant="primary" :loading="saving" @click="onRegister">{{ t('common.save') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 签发注册码 -->
    <FcDialog v-model:open="codeOpen" :title="t('common.create')" width="460px" append-to-body>
      <el-form :model="codeForm" label-width="170px">
        <FcFormItem :label="t('tok.label')">
          <FcInput v-model="codeForm.label" maxlength="64" />
        </FcFormItem>
        <FcFormItem :label="t('tok.preBind')">
          <FcInput v-model="codeForm.account" placeholder="wxid_xxx_8316" />
        </FcFormItem>
        <FcFormItem :label="t('tok.expiresHours')">
          <FcInput v-model.number="codeForm.expiresHours" type="number" placeholder="24" />
        </FcFormItem>
      </el-form>
      <template #footer>
        <FcButton @click="codeOpen = false">{{ t('common.cancel') }}</FcButton>
        <FcButton variant="primary" :loading="saving" @click="onIssueCode">{{ t('common.save') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 注册码明文仅此一次 -->
    <FcDialog v-model:open="codeResultOpen" :title="t('tok.codeIssued')" width="640px" append-to-body>
      <template v-if="issuedCode">
        <div class="cred-row">
          <div class="cred-label">{{ t('tok.regCode') }}</div>
          <div class="cred-value mono">{{ issuedCode.code }}</div>
          <FcButton variant="text" size="sm" @click="copy(issuedCode.code)">{{ t('common.copy') }}</FcButton>
        </div>
        <div class="cred-label">{{ t('tok.expiresAt') }}: {{ issuedCode.expires_at || '—' }}</div>
      </template>
      <template #footer>
        <FcButton variant="primary" @click="codeResultOpen = false">{{ t('common.confirm') }}</FcButton>
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
  FcSectionHeader, FcSection, FcFilterBar, FcTable, FcButton, FcPagination,
  FcTabsPanel, FcDialog, FcFormItem, FcInput, FcSelect,
} from '@/components/sdk'
import { get, post, del } from '@/api/client'
import type { PageVO, AccountVO, AccountTokenVO, RegCodeVO, RegCodeIssueVO } from '@/api/types'

const { t } = useI18n()
const tab = ref('accounts')
const tabs = [
  { value: 'accounts', label: t('tok.accounts') },
  { value: 'regcodes', label: t('tok.regCodes') },
]

// ---- 账号令牌 ----
const accountRows = ref<AccountVO[]>([])
const loadingAccounts = ref(false)
const accPage = ref(1)
const accPageSize = ref(20)
const accTotal = ref(0)
const kw = ref('')

const issueOpen = ref(false)
const issueFormOpen = ref(false)
const saving = ref(false)
const issueTarget = ref('')
const issued = ref<AccountTokenVO | null>(null)
const issueForm = ref({ ttl: 7776000 })
const ttlOptions = [
  { label: t('tok.ttl7d'), value: 604800 },
  { label: t('tok.ttl30d'), value: 2592000 },
  { label: t('tok.ttl90d'), value: 7776000 },
]
const registerOpen = ref(false)
const registerForm = ref({ account: '', hostname: '' })

// ---- 注册码 ----
const codeRows = ref<RegCodeVO[]>([])
const loadingCodes = ref(false)
const codePage = ref(1)
const codePageSize = ref(20)
const codeTotal = ref(0)
const codeOpen = ref(false)
const codeForm = ref({ label: '', account: '', expiresHours: undefined as number | undefined })
const codeResultOpen = ref(false)
const issuedCode = ref<RegCodeIssueVO | null>(null)

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '—' }
async function copy(text: string) {
  try { await navigator.clipboard.writeText(text); ElMessage.success(t('common.copied')) } catch { /* ignore */ }
}

async function loadAccounts() {
  loadingAccounts.value = true
  try {
    const q = new URLSearchParams({ page: String(accPage.value), pageSize: String(accPageSize.value) })
    const data = await get<PageVO<AccountVO>>(`/v1/accounts?${q}`)
    accountRows.value = kw.value
      ? data.list.filter((r) => (r.account || '').includes(kw.value))
      : data.list
    accTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingAccounts.value = false
  }
}

async function loadCodes() {
  loadingCodes.value = true
  try {
    const data = await get<PageVO<RegCodeVO>>(`/v1/reg-codes?page=${codePage.value}&pageSize=${codePageSize.value}`)
    codeRows.value = data.list
    codeTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingCodes.value = false
  }
}

function openIssue(account: string) {
  issueTarget.value = account
  issueForm.value.ttl = 7776000
  issueFormOpen.value = true
}

async function onIssue() {
  saving.value = true
  try {
    const res = await post<AccountTokenVO>(`/v1/accounts/${issueTarget.value}/tokens`, { ttl_seconds: issueForm.value.ttl })
    issued.value = res
    issueFormOpen.value = false
    issueOpen.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    saving.value = false
  }
}

async function onRegister() {
  if (!registerForm.value.account.trim()) return
  saving.value = true
  try {
    await post('/v1/accounts', {
      account: registerForm.value.account.trim(),
      hostname: registerForm.value.hostname || undefined,
    })
    registerOpen.value = false
    ElMessage.success(t('tok.register'))
    await loadAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    saving.value = false
  }
}

async function onDeleteAccount(row: AccountVO) {
  try {
    await del(`/v1/accounts/${row.account}`)
    ElMessage.success(t('tok.accountDeleted'))
    await loadAccounts()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

async function onIssueCode() {
  saving.value = true
  try {
    const res = await post<RegCodeIssueVO>('/v1/reg-codes', {
      label: codeForm.value.label || undefined,
      account: codeForm.value.account || undefined,
      expires_hours: codeForm.value.expiresHours || undefined,
    })
    codeOpen.value = false
    issuedCode.value = res
    codeResultOpen.value = true
    codeForm.value = { label: '', account: '', expiresHours: undefined }
    await loadCodes()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  } finally {
    saving.value = false
  }
}

async function onRevoke(row: RegCodeVO) {
  try {
    await del(`/v1/reg-codes/${row.id}`)
    ElMessage.success(t('tok.revokedOk'))
    await loadCodes()
  } catch (e) {
    ElMessage.error((e as Error).message || t('err.reqFail'))
  }
}

onMounted(() => { loadAccounts(); loadCodes() })
</script>

<style scoped lang="scss">
.mono { font-family: monospace; font-size: 12px; word-break: break-all; }
.cred-row { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 10px; }
.cred-label { min-width: 130px; font-size: 13px; color: var(--app-text-secondary); padding-top: 2px; }
.cred-value { flex: 1; color: var(--app-text); }
</style>
