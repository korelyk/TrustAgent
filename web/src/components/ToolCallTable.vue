<script setup lang="ts">
import type { ToolCallView } from '@/types/api'

defineProps<{ calls: ToolCallView[] }>()
</script>

<template>
  <el-table :data="calls" border size="small">
    <template #empty>
      <el-empty description="本次请求没有工具调用" :image-size="60" />
    </template>
    <el-table-column prop="toolName" label="工具" width="170" />
    <el-table-column label="是否允许" width="100" align="center">
      <template #default="{ row }">
        <el-tag :type="row.allowed ? 'success' : 'danger'" size="small">
          {{ row.allowed ? '允许' : '拦截' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="拒绝原因" min-width="180">
      <template #default="{ row }">
        <span v-if="row.denyReason" class="wrap-anywhere">{{ row.denyReason }}</span>
        <span v-else>—</span>
      </template>
    </el-table-column>
    <el-table-column label="参数（已脱敏）" min-width="200">
      <template #default="{ row }">
        <!-- 用文本插值渲染：这些内容来自工具参数，其中可能包含用户输入，绝不能用 v-html -->
        <span class="mono wrap-anywhere">{{ row.argumentsMasked ?? '—' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="返回（已脱敏）" min-width="220">
      <template #default="{ row }">
        <span class="mono wrap-anywhere">{{ row.resultMasked ?? '—' }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="latencyMs" label="耗时(ms)" width="100" align="right" />
  </el-table>
</template>
