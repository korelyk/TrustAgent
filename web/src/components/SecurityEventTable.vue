<script setup lang="ts">
import { computed } from 'vue'

import type { SecurityEventView } from '@/types/api'

const props = defineProps<{ events: SecurityEventView[] }>()

function severityType(severity: string): 'danger' | 'warning' | 'info' {
  if (severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  return 'info'
}

/**
 * 去重展示。
 *
 * 同一次请求里，同一类事件可能因为多个规则命中而重复出现
 * （实测：一次间接注入会产生 5 条 `PROMPT_INJECTION_INDIRECT`）。
 * 展示时按"事件类型 + 命中规则"聚合，避免前端看起来像刷屏；
 * 但**保留计数**，因为重复次数本身也是信息（命中了几条规则）。
 */
const rows = computed(() => {
  const grouped = new Map<string, SecurityEventView & { count: number }>()
  for (const event of props.events) {
    const key = `${event.eventType}|${event.ruleId ?? ''}|${event.actionTaken}`
    const existing = grouped.get(key)
    if (existing) {
      existing.count += 1
    } else {
      grouped.set(key, { ...event, count: 1 })
    }
  }
  return [...grouped.values()]
})
</script>

<template>
  <el-table :data="rows" border size="small">
    <template #empty>
      <el-empty description="本次请求没有安全事件" :image-size="60" />
    </template>
    <el-table-column label="类型" min-width="200">
      <template #default="{ row }">
        <span class="mono">{{ row.eventType }}</span>
        <el-tag v-if="row.count > 1" size="small" type="info" class="count">×{{ row.count }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="级别" width="100" align="center">
      <template #default="{ row }">
        <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="detector" label="检测器" width="140" />
    <el-table-column prop="ruleId" label="命中规则" width="180" />
    <el-table-column prop="actionTaken" label="处置" width="90" align="center" />
    <el-table-column label="证据（已脱敏）" min-width="260">
      <template #default="{ row }">
        <span class="mono wrap-anywhere">{{ row.evidenceMasked ?? '—' }}</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.count {
  margin-left: 6px;
}
</style>
