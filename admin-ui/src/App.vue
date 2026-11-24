<template>
  <FcThemeProvider initial-brand="mchuan" initial-theme="light">
    <router-view />
  </FcThemeProvider>
</template>

<style scoped lang="scss">
/* 全局页面内边距（模板 .app-page 契约） */
:deep(.app-page) { padding: 16px 16px 24px; }
</style>

<script setup lang="ts">
import { FcThemeProvider } from '@/components/sdk'
</script>
