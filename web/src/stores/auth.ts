import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchMe, login as loginApi } from '@/api/endpoints'
import { clearToken, readToken, writeToken } from '@/api/token'
import type { UserInfo } from '@/types/api'

/**
 * 登录态与权限。
 *
 * **重要：这里的权限判断只用于"按钮显不显示"，不是安全边界。**
 * 真正的拦截在后端（`@PreAuthorize` + 路径规则）。任何"前端隐藏了按钮就等于不能做"的
 * 假设都是错的 —— 攻击者可以直接调接口。把这句话写在代码里，是为了防止后来的人
 * 在这里加"安全逻辑"。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const loading = ref(false)
  /** 是否已经尝试过"用本地 Token 换取用户信息"，避免路由守卫重复请求。 */
  const initialized = ref(false)

  const isAuthenticated = computed(() => user.value !== null)
  const permissions = computed(() => new Set(user.value?.permissions ?? []))
  const roles = computed(() => user.value?.roles ?? [])

  function hasPermission(permission: string): boolean {
    return permissions.value.has(permission)
  }

  function hasAnyPermission(...candidates: string[]): boolean {
    return candidates.some((item) => permissions.value.has(item))
  }

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      const result = await loginApi(username, password)
      writeToken(result.token)
      user.value = result.user
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  /**
   * 用本地 Token 恢复登录态。
   *
   * 后端每次请求都会回库查权限，因此这里重新拉一次 `/auth/me` 而不是把用户信息也缓存到
   * localStorage —— 否则管理员改了权限，前端会一直显示旧权限直到用户手动重新登录。
   */
  async function restore(): Promise<boolean> {
    if (initialized.value) {
      return user.value !== null
    }
    if (!readToken()) {
      initialized.value = true
      return false
    }
    try {
      user.value = await fetchMe()
    } catch {
      // Token 过期或无效：清掉，当作未登录
      clearToken()
      user.value = null
    } finally {
      initialized.value = true
    }
    return user.value !== null
  }

  function logout(): void {
    clearToken()
    user.value = null
    initialized.value = true
  }

  return {
    user,
    loading,
    initialized,
    isAuthenticated,
    permissions,
    roles,
    hasPermission,
    hasAnyPermission,
    login,
    restore,
    logout,
  }
})
