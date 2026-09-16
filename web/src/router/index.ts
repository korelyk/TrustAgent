import { ElMessage } from 'element-plus'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { setUnauthorizedHandler } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

// 让 `to.meta.permission` 有类型，避免到处写 as string
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 公开页面（不需要登录），目前只有登录页 */
    public?: boolean
    /** 访问该页面需要的权限点。权限码与后端 sys_permission.code 一一对应。 */
    permission?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: { name: 'apps' } },
      {
        path: 'apps',
        name: 'apps',
        component: () => import('@/views/AppsView.vue'),
        meta: { title: 'Agent 应用', permission: 'app:read' },
      },
      {
        path: 'apps/:id/policy',
        name: 'policy',
        component: () => import('@/views/PolicyView.vue'),
        meta: { title: '安全策略', permission: 'policy:read' },
      },
      {
        path: 'apps/:id/playground',
        name: 'playground',
        component: () => import('@/views/PlaygroundView.vue'),
        meta: { title: 'Agent Playground', permission: 'agent:chat' },
      },
      {
        path: 'eval',
        name: 'eval',
        component: () => import('@/views/EvalView.vue'),
        meta: { title: '评测集与任务', permission: 'eval:read' },
      },
      {
        path: 'eval/runs/:id',
        name: 'eval-run',
        component: () => import('@/views/EvalRunView.vue'),
        meta: { title: '评测报告', permission: 'eval:read' },
      },
      {
        path: 'traces/:traceId',
        name: 'trace',
        component: () => import('@/views/TraceView.vue'),
        meta: { title: 'Trace 详情', permission: 'trace:read' },
      },
      {
        path: 'security/events',
        name: 'security-events',
        component: () => import('@/views/SecurityEventsView.vue'),
        meta: { title: '安全事件', permission: 'security:read' },
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/AuditView.vue'),
        meta: { title: '审计链验证', permission: 'audit:read' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 401 的处理交给路由：api 层不能直接依赖 router（循环依赖）
setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  const auth = useAuthStore()
  const authenticated = await auth.restore()
  if (!authenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }

  // 页面级权限只在**前端**做"能不能进这个页面"的引导，
  // 真正的拦截在后端的 @PreAuthorize。这一层是为了避免用户点进去看到一个满是 403 的页面。
  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    ElMessage.error(`当前账号没有访问「${to.meta.title ?? '该页面'}」的权限`)
    return { name: 'apps' }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · TrustAgent` : 'TrustAgent'
})
