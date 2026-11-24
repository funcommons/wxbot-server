import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import i18n from '@/i18n'
import App from '@/App.vue'
import router from '@/router'

// Fc SDK 全局样式：token 体系 + EP 品牌化覆写 + 8 brand + 主题（必须最先引入）
import '@/components/sdk/theme/theme.scss'

const app = createApp(App)
app.use(i18n)
app.use(router)
app.use(ElementPlus)
app.mount('#app')
