<template>
  <FcThemeProvider :initial-brand="qBrand" :initial-theme="qTheme">
    <router-view />
  </FcThemeProvider>
</template>

<style scoped lang="scss">
/* 全局页面内边距（模板 .app-page 契约） */
:deep(.app-page) { padding: 16px 16px 24px; }
</style>

<script setup lang="ts">
import { FcThemeProvider } from '@/components/sdk'

// ?brand= / ?theme= 查询参数可覆盖默认（验收截图/演示便利），用户切换仍持久化 localStorage
const q = new URLSearchParams(window.location.search)
const qBrand = (q.get('brand') || 'mchuan') as never
const qTheme = (q.get('theme') || 'light') as never
</script>
