<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/api/client'
import { getApp, getPolicy, listTools, savePolicy } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import type { AgentAppView, ToolView } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const appId = Number(route.params.id)
const loading = ref(false)
const saving = ref(false)
const app = ref<AgentAppView | null>(null)
const tools = ref<ToolView[]>([])
const version = ref<number | null>(null)
const updatedAt = ref<string | null>(null)

const form = reactive({
  guardEnabled: true,
  maxToolCalls: 3,
  maxInputLength: 4000,
  taskTimeoutMs: 15000,
  domainWhitelist: [] as string[],
  deniedParamRules: [] as string[],
  confirmSideEffects: true,
  injectionDetectionEnabled: true,
  piiMaskingEnabled: true,
  outputGuardEnabled: true,
})

const canEdit = computed(() => auth.hasPermission('policy:update'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [appDetail, policy, toolList] = await Promise.all([
      getApp(appId),
      getPolicy(appId),
      listTools(appId).catch(() => [] as ToolView[]),
    ])
    app.value = appDetail
    tools.value = toolList

    form.guardEnabled = policy.guardEnabled
    form.maxToolCalls = policy.maxToolCalls
    form.maxInputLength = policy.maxInputLength
    form.taskTimeoutMs = policy.taskTimeoutMs
    form.domainWhitelist = [...policy.domainWhitelist]
    form.deniedParamRules = [...policy.deniedParamRules]
    form.confirmSideEffects = policy.confirmSideEffects
    form.injectionDetectionEnabled = policy.injectionDetectionEnabled
    form.piiMaskingEnabled = policy.piiMaskingEnabled
    form.outputGuardEnabled = policy.outputGuardEnabled
    version.value = policy.version
    updatedAt.value = policy.updatedAt
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载策略失败')
  } finally {
    loading.value = false
  }
}

async function handleSave(): Promise<void> {
  saving.value = true
  try {
    const saved = await savePolicy(appId, {
      guardEnabled: form.guardEnabled,
      maxToolCalls: form.maxToolCalls,
      maxInputLength: form.maxInputLength,
      taskTimeoutMs: form.taskTimeoutMs,
      domainWhitelist: form.domainWhitelist,
      deniedParamRules: form.deniedParamRules,
      confirmSideEffects: form.confirmSideEffects,
      injectionDetectionEnabled: form.injectionDetectionEnabled,
      piiMaskingEnabled: form.piiMaskingEnabled,
      outputGuardEnabled: form.outputGuardEnabled,
      // 带上读到的版本，服务端用它做乐观锁校验
      version: version.value,
    })
    version.value = saved.version
    updatedAt.value = saved.updatedAt
    ElMessage.success(`策略已保存，版本更新为 ${saved.version}`)
  } catch (error) {
    if (error instanceof ApiError && error.code === 40901) {
      // 乐观锁冲突要给专门的操作指引：直接重试会再次失败
      ElMessage.warning('策略已被其他人修改，请刷新后重新编辑')
      return
    }
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <div>
            <span>{{ app?.name ?? '加载中…' }}</span>
            <span v-if="app" class="code mono">{{ app.code }}</span>
          </div>
          <div class="actions">
            <el-tag size="small" type="info">版本 v{{ version ?? '—' }}</el-tag>
            <el-button @click="router.push({ name: 'apps' })">返回列表</el-button>
            <el-button
              v-if="auth.hasPermission('agent:chat')"
              @click="router.push({ name: 'playground', params: { id: appId } })"
            >
              Playground
            </el-button>
            <el-button v-if="canEdit" type="primary" :loading="saving" @click="handleSave">保存</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="!canEdit"
        type="info"
        :closable="false"
        show-icon
        title="当前账号只有查看权限（policy:read），保存按钮不可用。真正的拦截在后端。"
      />
      <el-alert
        v-if="!form.guardEnabled"
        type="warning"
        :closable="false"
        show-icon
        title="总开关已关闭：所有内容检测都会被跳过。这是评测对照实验专用的后门，正常运行不应关闭。"
      />

      <el-form class="form" label-width="180px" :disabled="!canEdit">
        <el-divider content-position="left">内容检测</el-divider>
        <el-form-item label="启用防护总开关">
          <el-switch v-model="form.guardEnabled" />
          <span class="hint">关闭后注入检测、脱敏、输出安检全部跳过</span>
        </el-form-item>
        <el-form-item label="提示注入检测">
          <el-switch v-model="form.injectionDetectionEnabled" />
        </el-form-item>
        <el-form-item label="敏感信息脱敏">
          <el-switch v-model="form.piiMaskingEnabled" />
        </el-form-item>
        <el-form-item label="输出安全检测">
          <el-switch v-model="form.outputGuardEnabled" />
          <span class="hint">系统提示词泄露、危险 HTML、未验证外链</span>
        </el-form-item>

        <el-divider content-position="left">工具调用约束</el-divider>
        <el-form-item label="单次任务最多调用工具次数">
          <el-input-number v-model="form.maxToolCalls" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="副作用工具需确认">
          <el-switch v-model="form.confirmSideEffects" />
          <span class="hint">开启后，有副作用的工具（如发送通知）必须显式确认才执行</span>
        </el-form-item>
        <el-form-item label="敏感参数禁止规则">
          <el-select
            v-model="form.deniedParamRules"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="参数名命中即拒绝，例如 password / token"
            class="full"
          />
        </el-form-item>
        <el-form-item label="URL / 域名白名单">
          <el-select
            v-model="form.domainWhitelist"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="例如 docs.trustagent.local 或 *.example.com"
            class="full"
          />
        </el-form-item>

        <el-divider content-position="left">资源限制</el-divider>
        <el-form-item label="最大输入长度">
          <el-input-number v-model="form.maxInputLength" :min="64" :max="20000" :step="100" />
        </el-form-item>
        <el-form-item label="单次任务超时（毫秒）">
          <el-input-number v-model="form.taskTimeoutMs" :min="1000" :max="120000" :step="1000" />
        </el-form-item>
      </el-form>

      <div class="meta">最后更新：{{ updatedAt ?? '—' }}</div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span>该应用的工具</span>
      </template>
      <el-table :data="tools" border>
        <template #empty>
          <el-empty description="没有查到工具配置" />
        </template>
        <el-table-column prop="toolName" label="工具" width="200" />
        <el-table-column label="是否启用" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '已启用' : '未启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有副作用" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.sideEffect" type="warning" size="small">是</el-tag>
            <span v-else>否</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="260" />
      </el-table>
      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="有副作用的工具默认不启用；即使启用，也会受“副作用工具需确认”约束。内置工具都不触碰真实资源。"
      />
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

.code {
  margin-left: 10px;
  color: #6b7280;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.form {
  margin-top: 12px;
  max-width: 760px;
}

.full {
  width: 100%;
}

.hint {
  margin-left: 10px;
  color: #6b7280;
  font-size: 12px;
}

.meta {
  margin-top: 8px;
  color: #9ca3af;
  font-size: 12px;
}

.tip {
  margin-top: 12px;
}
</style>
