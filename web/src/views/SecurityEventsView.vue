<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { ApiError } from '@/api/client'
import { listSecurityEvents } from '@/api/endpoints'
import type { SecurityEventRow } from '@/types/api'

const loading = ref(false)
const rows = ref<SecurityEventRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const eventType = ref('')
const severity = ref('')

function severityType(value: string): 'danger' | 'warning' | 'info' {
  if (value === 'HIGH') return 'danger'
  if (value === 'MEDIUM') return 'warning'
  return 'info'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listSecurityEvents({
      page: page.value,
      size: size.value,
      eventType: eventType.value.trim() || undefined,
      severity: severity.value || undefined,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载安全事件失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  void load()
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>安全事件</span>
        <div class="filters">
          <el-input
            v-model="eventType"
            placeholder="按事件类型过滤，如 TOOL_NOT_ALLOWED"
            clearable
            class="input"
            @keyup.enter="handleSearch"
          />
          <el-select v-model="severity" placeholder="级别" clearable class="select">
            <el-option label="HIGH" value="HIGH" />
            <el-option label="MEDIUM" value="MEDIUM" />
            <el-option label="LOW" value="LOW" />
          </el-select>
          <el-button type="primary" @click="handleSearch">查询</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" border size="small">
      <template #empty>
        <el-empty description="没有符合条件的安全事件" />
      </template>
      <el-table-column label="类型" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.eventType }}</span>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detector" label="检测器" width="150" />
      <el-table-column prop="ruleId" label="命中规则" width="190" />
      <el-table-column prop="actionTaken" label="处置" width="90" align="center" />
      <el-table-column label="证据（已脱敏）" min-width="240">
        <template #default="{ row }">
          <span class="mono wrap-anywhere">{{ row.evidenceMasked ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Trace" width="140">
        <template #default="{ row }">
          <!-- 从事件直接跳到对应 Trace：这是排查攻击链的主要路径 -->
          <RouterLink
            v-if="row.traceId"
            :to="{ name: 'trace', params: { traceId: row.traceId } }"
            class="mono link"
          >
            查看
          </RouterLink>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :current-page="page"
      :page-size="size"
      @current-change="
        (value: number) => {
          page = value
          void load()
        }
      "
    />
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input {
  width: 280px;
}

.select {
  width: 120px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.link {
  color: #409eff;
  text-decoration: none;
}
</style>
