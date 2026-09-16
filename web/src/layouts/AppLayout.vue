<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

interface MenuItem {
  name: string
  label: string
  permission: string
  /** 需要 appId 的页面不出现在侧边栏（从应用列表进），置 false */
  inMenu: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const allItems: MenuItem[] = [
  { name: 'apps', label: 'Agent 应用', permission: 'app:read', inMenu: true },
  { name: 'eval', label: '评测集与任务', permission: 'eval:read', inMenu: true },
  { name: 'security-events', label: '安全事件', permission: 'security:read', inMenu: true },
  { name: 'audit', label: '审计链验证', permission: 'audit:read', inMenu: true },
  // 策略编辑页与 Playground 依赖 appId，从应用列表点进去，不放在侧边栏
  { name: 'policy', label: '安全策略', permission: 'policy:read', inMenu: false },
  { name: 'playground', label: 'Agent Playground', permission: 'agent:chat', inMenu: false },
]

// 菜单按权限过滤。注意：这只是"不显示"，不是"不能访问"——后端才是边界。
const menuItems = computed(() => allItems.filter((item) => item.inMenu && auth.hasPermission(item.permission)))

const activeMenu = computed(() => {
  const name = route.name
  if (name === 'eval-run') return 'eval'
  if (name === 'trace') return 'security-events'
  return typeof name === 'string' ? name : ''
})

const currentTitle = computed(() => route.meta.title ?? '')
const roleLabel = computed(() => (auth.user?.roles ?? []).join(' / ') || '—')

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="brand-name">TrustAgent</div>
        <div class="brand-sub">AI Agent 安全网关</div>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="item in menuItems" :key="item.name" :index="item.name" :route="{ name: item.name }">
          {{ item.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="title">{{ currentTitle }}</div>
        <div class="user">
          <el-tag size="small" type="info">{{ roleLabel }}</el-tag>
          <span class="username">{{ auth.user?.displayName || auth.user?.username }}</span>
          <el-button link type="primary" @click="handleLogout">退出</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #1f2d3d;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.brand {
  padding: 18px 16px 12px;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.brand-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #8fa4bb;
}

.menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #c8d6e5;
  --el-menu-hover-bg-color: #2c3e50;
  --el-menu-active-color: #ffffff;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
}

.username {
  font-size: 13px;
  color: #4b5563;
}

.main {
  background: #f5f7fa;
}
</style>
