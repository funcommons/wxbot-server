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
        { path: 'subscriptions', name: 'Subscriptions', component: () => import('@/views/Subscriptions.vue') },
        { path: 'consumers', name: 'Consumers', component: () => import('@/views/Consumers.vue') },
        { path: 'tokens', name: 'Tokens', component: () => import('@/views/Tokens.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.loggedIn) return { name: 'Login', query: { back: to.fullPath } }
  if (to.name === 'Login' && auth.loggedIn) return { path: '/dashboard' }
  return true
})

export default router
