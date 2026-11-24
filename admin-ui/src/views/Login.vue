<template>
  <div class="login-wrap">
    <FcSectionCard class="login-card">
      <div class="login-title">{{ t('login.title') }}</div>
      <div class="login-hint">{{ t('login.hint') }}</div>
      <el-form label-position="top" @submit.prevent>
        <FcFormItem :label="t('login.refresh')">
          <FcInput v-model="refresh" type="password" show-password clearable />
        </FcFormItem>
        <FcFormItem :label="t('login.access')">
          <FcInput v-model="access" type="password" show-password clearable />
        </FcFormItem>
        <FcButton variant="primary" class="login-btn" :loading="busy" @click="onConnect">
          {{ t('login.connect') }}
        </FcButton>
      </el-form>
    </FcSectionCard>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionCard, FcFormItem, FcInput, FcButton } from '@/components/sdk'
import { useAuthStore } from '@/store/auth'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const refresh = ref('')
const access = ref('')
const busy = ref(false)

async function onConnect() {
  if (!refresh.value.trim()) return
  busy.value = true
  try {
    await auth.login(refresh.value, access.value)
    router.push(String(route.query.back || '/dashboard'))
  } catch {
    ElMessage.error(t('login.invalid'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped lang="scss">
.login-wrap { display: flex; align-items: center; justify-content: center; height: 100vh; }
.login-card { width: 420px; padding: 8px; }
.login-title { font-size: 18px; font-weight: 700; color: var(--app-text); margin-bottom: 8px; }
.login-hint { font-size: 12px; color: var(--app-text-secondary); margin-bottom: 16px; }
.login-btn { width: 100%; margin-top: 8px; }
</style>
