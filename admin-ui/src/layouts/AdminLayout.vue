<template>
  <div class="admin-shell">
    <FcSidebar v-model:collapsed="collapsed" :width="216" :collapsed-width="64">
      <div class="brand-row">
        <span class="brand-name">wxbot-admin</span>
      </div>
      <FcSidebarNav :items="navItems" :active-path="route.path" :collapse="collapsed" @select="onNav" />
    </FcSidebar>

    <div class="admin-body">
      <FcHeader>
        <template #brand>
          <FcSidebarToggle v-model:collapsed="collapsed" />
          <span class="page-title">{{ activeTitle }}</span>
        </template>
        <template #actions>
          <FcTag v-if="auth.accessTtl > 0" color="success" size="sm">
            {{ t('dash.tokenOk') }}
          </FcTag>
          <FcTag v-else color="danger" size="sm">{{ t('err.kicked') }}</FcTag>
        </template>
        <template #user>
          <FcThemeSwitcher variant="popover" />
          <FcButton variant="text" size="sm" :icon="SwitchButton" @click="onLogout">{{ t('nav.logout') }}</FcButton>
        </template>
      </FcHeader>
      <FcMain>
        <router-view />
      </FcMain>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { SwitchButton, DataLine, Connection, Share, Key, List, ChatDotRound, Message, User } from '@element-plus/icons-vue'
import {
  FcSidebar, FcSidebarNav, FcSidebarToggle, FcHeader, FcMain,
  FcTag, FcButton, FcThemeSwitcher, type NavItem,
} from '@/components/sdk'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()
const collapsed = ref(false)

const navItems: NavItem[] = [
  { index: '/dashboard', label: t('nav.dashboard'), icon: DataLine },
  { index: '/chats', label: t('nav.chats'), icon: ChatDotRound },
  { index: '/subscriptions', label: t('nav.subscriptions'), icon: Share },
  { index: '/consumers', label: t('nav.consumers'), icon: Connection },
  { index: '/tokens', label: t('nav.tokens'), icon: Key },
  { index: '/tasks', label: t('nav.tasks'), icon: List },
  { index: '/messages', label: t('nav.messages'), icon: Message },
  { index: '/contacts', label: t('nav.contacts'), icon: User },
]

const activeTitle = computed(() => {
  const hit = navItems.find((n) => n.index === route.path)
  return hit ? hit.label : 'wxbot-admin'
})

function onNav(path: string) { router.push(path) }

function onLogout() {
  auth.logout()
  router.push({ name: 'Login' })
}
</script>

<style scoped lang="scss">
/* 仅布局：颜色/圆角/阴影全部走 SDK token */
.admin-shell { display: flex; height: 100vh; align-items: stretch; }
.admin-shell :deep(.fc-sidebar) { height: 100%; display: flex; flex-direction: column; }
.admin-body { display: flex; flex: 1; flex-direction: column; min-width: 0; }
/* 列方向 flex 项 min-height:auto = 内容下限，会把 fc-main 撑破 100vh 壳；
   置 0 后普通页行为不变（内容照旧溢出由文档滚动），定高页（chats）才能约束内部滚动 */
.admin-body :deep(.fc-main) { min-height: 0; }
.brand-row { display: flex; align-items: center; justify-content: center; padding: 16px 8px 8px; }
.brand-name { font-size: 15px; font-weight: 700; color: var(--app-text); white-space: nowrap; }
.page-title { font-size: 15px; font-weight: 600; color: var(--app-text); }
</style>
