import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue') },
        { path: 'chats', name: 'Chats', component: () => import('@/views/Chats.vue') },
        { path: 'subscriptions', name: 'Subscriptions', component: () => import('@/views/Subscriptions.vue') },
        { path: 'consumers', name: 'Consumers', component: () => import('@/views/Consumers.vue') },
        { path: 'tokens', name: 'Tokens', component: () => import('@/views/Tokens.vue') },
        { path: 'tasks', name: 'Tasks', component: () => import('@/views/Tasks.vue') },
        { path: 'messages', name: 'Messages', component: () => import('@/views/Messages.vue') },
        { path: 'contacts', name: 'Contacts', component: () => import('@/views/Contacts.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  // ?token= 自动登录（深链/自动化截图便利；命中即剥离 query 防泄漏到地址栏）
  const qtok = typeof to.query.token === 'string' ? to.query.token : ''
  if (qtok) {
    auth.login(qtok)
    const rest = { ...to.query }
    delete rest.token
    return { path: to.path, query: rest, replace: true }
  }
  if (!to.meta.public && !auth.loggedIn) return { name: 'Login', query: { back: to.fullPath } }
  if (to.name === 'Login' && auth.loggedIn) return { path: '/dashboard' }
  return true
})

export default router
