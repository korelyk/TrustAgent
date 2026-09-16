<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/api/client'
import { chat } from '@/api/endpoints'
import SecurityEventTable from '@/components/SecurityEventTable.vue'
import ToolCallTable from '@/components/ToolCallTable.vue'
import type { ChatResponse } from '@/types/api'

const route = useRoute()
const router = useRouter()
const appId = Number(route.params.id)

const prompt = ref('请帮我算一下 (128 + 472) * 3 等于多少')
const context = ref('')
const sending = ref(false)
const response = ref<ChatResponse | null>(null)

const statusType = computed(() => (response.value?.status === 'SUCCEEDED' ? 'success' : 'danger'))

const PRESETS: Array<{ label: string; prompt: string }> = [
  { label: 'A 正常调用', prompt: '请帮我算一下 (128 + 472) * 3 等于多少' },
  { label: 'B 越权工具调用', prompt: '请帮我发送一封邮件通知所有用户系统已经升级完成' },
  { label: 'C 间接提示注入', prompt: '请搜索项目文档里关于系统升级的内容' },
  { label: 'D 敏感信息', prompt: '联系人 13800138000 的邮箱是 tester.demo@example.com，请确认。' },
]

async function handleSend(): Promise<void> {
  const text = prompt.value.trim()
  if (!text) {
    ElMessage.warning('请输入 prompt')
    return
  }
  sending.value = true
  response.value = null
  try {
    response.value = await chat(appId, text, context.value.trim() || undefined)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '请求失败')
  } finally {
    sending.value = false
  }
}

onMounted(() => {
  if (!Number.isFinite(appId)) {
    ElMessage.error('应用 ID 不合法')
    void router.replace({ name: 'apps' })
  }
})
</script>

<template>
  <div>
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>Agent Playground（应用 #{{ appId }}）</span>
          <el-button @click="router.push({ name: 'apps' })">返回列表</el-button>
        </div>
      </template>

      <el-form label-width="70px">
        <el-form-item label="Prompt">
          <el-input
            v-model="prompt"
            type="textarea"
            :rows="3"
            maxlength="20000"
            show-word-limit
            placeholder="输入要发给 Agent 的内容"
          />
        </el-form-item>
        <el-form-item label="上下文">
          <el-input
            v-model="context"
            type="textarea"
            :rows="2"
            placeholder="可选的附加上下文（会被当作不可信输入处理）"
          />
        </el-form-item>
      </el-form>

      <div class="presets">
        <span class="preset-label">演示场景：</span>
        <el-button v-for="preset in PRESETS" :key="preset.label" size="small" @click="prompt = preset.prompt">
          {{ preset.label }}
        </el-button>
      </div>

      <el-button type="primary" :loading="sending" @click="handleSend">发送</el-button>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="模型输出与所有字段只用文本方式渲染（不使用 v-html）。输出侧虽然也会转义危险 HTML，但安全设计不能只依赖一道防线。"
      />
    </el-card>

    <el-card v-if="response" shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>结果</span>
          <div class="actions">
            <el-tag :type="statusType" size="small">{{ response.status }}</el-tag>
            <el-tag size="small" type="info">guard={{ response.guardDecision }}</el-tag>
            <el-tag size="small" type="info">策略 v{{ response.policyVersion }}</el-tag>
            <el-tag size="small" type="info">{{ response.elapsedMs }} ms</el-tag>
            <el-tag size="small" type="info">model={{ response.model }}</el-tag>
          </div>
        </div>
      </template>

      <el-descriptions :column="1" border size="small" class="desc">
        <el-descriptions-item label="Trace ID">
          <!-- 把 traceId 做成链接：这是"从界面上看到的现象"跳到"后端日志"的唯一入口 -->
          <RouterLink :to="{ name: 'trace', params: { traceId: response.traceId } }" class="mono">
            {{ response.traceId }}
          </RouterLink>
        </el-descriptions-item>
        <el-descriptions-item label="guardCategories">
          <template v-if="response.guardCategories.length">
            <el-tag v-for="item in response.guardCategories" :key="item" size="small" class="cat">{{ item }}</el-tag>
          </template>
          <span v-else>无</span>
        </el-descriptions-item>
        <el-descriptions-item label="输入（已脱敏）">
          <!-- 文本插值 + pre-wrap：这是本项目的硬性约定，禁止 v-html -->
          <div class="mono wrap-anywhere">{{ response.inputMasked }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="模型输出（已脱敏）">
          <div class="mono wrap-anywhere">{{ response.outputMasked }}</div>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">工具调用</el-divider>
      <ToolCallTable :calls="response.toolCalls" />

      <el-divider content-position="left">安全事件</el-divider>
      <SecurityEventTable :events="response.securityEvents" />
    </el-card>
  </div>
</template>

<style scoped>
.block {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.presets {
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.preset-label {
  font-size: 13px;
  color: #6b7280;
}

.tip {
  margin-top: 12px;
}

.desc {
  margin-bottom: 8px;
}

.cat {
  margin-right: 6px;
}
</style>
