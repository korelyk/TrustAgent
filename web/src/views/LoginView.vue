<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : null
    await router.replace(redirect ?? { name: 'apps' })
  } catch (error) {
    if (error instanceof ApiError) {
      // 控制面对"密码错误"和"用户不存在"返回同一个错误码，这里也照原样提示，
      // 不做任何区分——否则前端会成为一个用户名枚举的旁路。
      ElMessage.error(error.message || '登录失败')
    } else {
      ElMessage.error('登录失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <el-card class="card" shadow="always">
      <div class="brand">
        <div class="brand-name">TrustAgent</div>
        <div class="brand-sub">AI Agent 安全网关 · 自动化评测与审计平台</div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="submitting" @click="handleSubmit">登录</el-button>
      </el-form>

      <el-alert
        class="hint"
        type="info"
        :closable="false"
        show-icon
        title="演示账号由 scripts/init-dev-env.ps1 生成，口令在生成时打印一次，不会写入版本库。"
      />
    </el-card>
  </div>
</template>

<style scoped>
.page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #1f2d3d 0%, #2c3e50 60%, #34495e 100%);
}

.card {
  width: 400px;
  padding: 8px 12px;
}

.brand {
  text-align: center;
  margin-bottom: 22px;
}

.brand-name {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.brand-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #6b7280;
}

.submit {
  width: 100%;
}

.hint {
  margin-top: 16px;
}
</style>
