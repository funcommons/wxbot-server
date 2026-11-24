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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionHeader, FcSection, FcSectionCard, FcSkeleton, FcButton, FcStatusBadge } from '@/components/sdk'
import { get } from '@/api/client'
import type { PageVO } from '@/api/types'
import { useAuthStore } from '@/store/auth'

const { t } = useI18n()
const auth = useAuthStore()
const loading = ref(true)
const counts = ref({ accounts: 0, consumers: 0, subscriptions: 0, chats: 0 })

const cards = computed(() => [
  { label: t('dash.accounts'), value: counts.value.accounts },
  { label: t('dash.consumers'), value: counts.value.consumers },
  { label: t('dash.subscriptions'), value: counts.value.subscriptions },
  { label: t('dash.sessions'), value: counts.value.chats },
])

async function loadAll() {
  loading.value = true
  try {
    const [a, c, s, ch] = await Promise.all([
      get<PageVO<unknown>>('/v1/accounts?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/consumers?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/subscriptions?page=1&pageSize=1'),
      get<PageVO<unknown>>('/v1/chats?page=1&pageSize=1'),
    ])
    counts.value = {
      accounts: a.total, consumers: c.total,
      subscriptions: s.total, chats: ch.total,
    }
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.stat-label { font-size: 12px; color: var(--app-text-secondary); }
.stat-value { font-size: 28px; font-weight: 700; color: var(--app-text); margin-top: 4px; }
.token-row { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
</style>
