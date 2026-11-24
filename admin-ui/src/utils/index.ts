// 宿主 utils：SDK（FcImagePicker）依赖的 logger；另附 ElementPlus 图标按名解析
import * as Icons from '@element-plus/icons-vue'
import type { Component } from 'vue'

const TAG = '[wxbot-admin]'

export const logger = {
  debug: (...args: unknown[]) => console.debug(TAG, ...args),
  info: (...args: unknown[]) => console.info(TAG, ...args),
  warn: (...args: unknown[]) => console.warn(TAG, ...args),
  error: (...args: unknown[]) => console.error(TAG, ...args),
}

/** 图标名（e.g. 'Setting'）→ ElementPlus 图标组件；未命中回退 Connection */
export function resolveIcon(name: string): Component {
  const set = Icons as unknown as Record<string, Component>
  return set[name] ?? Icons.Connection
}
