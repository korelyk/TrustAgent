<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/api/client'
import { getTrace } from '@/api/endpoints'
import SecurityEventTable from '@/components/SecurityEventTable.vue'
import ToolCallTable from '@/components/ToolCallTable.vue'
import type { TraceDetail } from '@/types/api'

const route = useRoute()
const router = useRouter()
const traceId = String(route.params.traceId ?? '')

const loading = ref(false)
const detail = ref<TraceDetail | null>(null)

const statusType = computed(() => (detail.value?.trace.status === 'SUCCEEDED' ? 'success' : 'danger'))

const guardCategories = computed(() => {
  const raw = detail.value?.trace.guardCategories
  if (!raw) return []
  return raw.split(',').map((item) => item.trim()).filter(Boolean)
})

async function load(): Promise<void> {
  loading.value = true
  try {
    detail.value = await getTrace(traceId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载 Trace 失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>Trace 详情</span>
          <el-button @click="router.back()">返回</el-button>
        </div>
      </template>

      <el-empty v-if="!detail" description="没有查到该 Trace（可能已被清理，或不属于当前账号）" />

      <template v-else>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="Trace ID">
            <span class="mono">{{ detail.trace.traceId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType" size="small">{{ detail.trace.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="应用">
            <RouterLink v-if="detail.trace.appId" :to="{ name: 'playground', params: { id: detail.trace.appId } }">
              #{{ detail.trace.appId }}
            </RouterLink>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="发起人 ID">{{ detail.trace.userId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="guardDecision">{{ detail.trace.guardDecision ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="工具调用次数">{{ detail.trace.toolCallCount }}</el-descriptions-item>
          <el-descriptions-item label="总耗时">{{ detail.trace.totalLatencyMs }} ms</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.trace.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="命中类别" :span="2">
            <template v-if="guardCategories.length">
              <el-tag v-for="item in guardCategories" :key="item" size="small" class="cat">{{ item }}</el-tag>
            </template>
            <span v-else>无</span>
          </el-descriptions-item>
          <el-descriptions-item label="输入预览（已脱敏）" :span="2">
            <!-- 文本插值：这些内容来自用户输入与模型输出，禁止 v-html -->
            <div class="mono wrap-anywhere">{{ detail.trace.inputPreview ?? '—' }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="输出预览（已脱敏）" :span="2">
            <div class="mono wrap-anywhere">{{ detail.trace.outputPreview ?? '—' }}</div>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.trace.errorMessage" label="错误信息" :span="2">
            <span class="wrap-anywhere">{{ detail.trace.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <template v-if="detail">
      <el-card shadow="never" class="block">
        <template #header><span>工具调用</span></template>
        <ToolCallTable :calls="detail.toolCalls" />
      </el-card>

      <el-card shadow="never" class="block">
        <template #header><span>安全事件</span></template>
        <SecurityEventTable :events="detail.securityEvents" />
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <span>该 Trace 关联的审计记录</span>
        </template>
        <el-table :data="detail.auditLogs" border size="small">
          <template #empty>
            <el-empty description="没有关联的审计记录" :image-size="60" />
          </template>
          <el-table-column prop="seq" label="seq" width="80" />
          <el-table-column prop="action" label="动作" width="220" />
          <el-table-column prop="actorUsername" label="操作者" width="120" />
          <el-table-column prop="resourceType" label="资源类型" width="140" />
          <el-table-column prop="resourceId" label="资源 ID" width="100" />
          <el-table-column label="记录哈希" min-width="240">
            <template #default="{ row }">
              <span class="mono">{{ row.recordHash }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="180" />
        </el-table>
        <el-alert
          class="tip"
          type="info"
          :closable="false"
          show-icon
          title="审计记录与 Trace 通过同一个 traceId 关联：从一次请求的结果可以反查到它留下的所有留痕记录。"
        />
      </el-card>
    </template>
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

.cat {
  margin-right: 6px;
}

.tip {
  margin-top: 12px;
}
</style>
