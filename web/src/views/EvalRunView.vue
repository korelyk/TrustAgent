<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/api/client'
import { exportReport, getRun, listRunResults } from '@/api/endpoints'
import type { EvalCaseResultView, EvalRunView, Metrics } from '@/types/api'

const route = useRoute()
const router = useRouter()
const runId = Number(route.params.id)

const loading = ref(false)
const resultsLoading = ref(false)
const exporting = ref('')
const run = ref<EvalRunView | null>(null)
const results = ref<EvalCaseResultView[]>([])
const resultsTotal = ref(0)
const page = ref(1)
const size = ref(20)
const categoryFilter = ref('')
const passedFilter = ref<'' | 'true' | 'false'>('')

const guardOn = computed<Metrics | null>(() => run.value?.metrics?.PAIRED?.guardOn ?? null)
const guardOff = computed<Metrics | null>(() => run.value?.metrics?.PAIRED?.guardOff ?? null)

/**
 * 指标表的行定义。
 *
 * `better` 只用于界面提示（"越低越好 / 越高越好"）。
 * 注意：**不要把这里的判断当成结论**——比如 asr 越低越好，但 0 也可能是
 * "被过度拦截换来的"，必须和误报率一起看。
 */
const METRIC_ROWS: Array<{ key: keyof Metrics; label: string; better: 'low' | 'high' | 'none' }> = [
  { key: 'asr', label: '攻击成功率 asr', better: 'low' },
  { key: 'detectionPrecision', label: '检测精确率 precision', better: 'high' },
  { key: 'detectionRecall', label: '检测召回率 recall', better: 'high' },
  { key: 'f1', label: 'F1', better: 'high' },
  { key: 'falsePositiveRate', label: '误报率 FPR', better: 'low' },
  { key: 'benignPassRate', label: '正常放行率', better: 'high' },
  { key: 'piiLeakageRate', label: '敏感信息泄露率', better: 'low' },
  { key: 'taskSuccessRate', label: '整体通过率', better: 'high' },
  { key: 'p50LatencyMs', label: 'P50 延迟(ms)', better: 'none' },
  { key: 'p95LatencyMs', label: 'P95 延迟(ms)', better: 'none' },
  { key: 'totalCases', label: '用例数', better: 'none' },
  { key: 'attackCases', label: '攻击用例数', better: 'none' },
  { key: 'benignCases', label: '正常用例数', better: 'none' },
  { key: 'maskCases', label: '脱敏用例数', better: 'none' },
  { key: 'truePositive', label: 'TP（拦住该拦的）', better: 'none' },
  { key: 'falsePositive', label: 'FP（误拦）', better: 'none' },
  { key: 'falseNegative', label: 'FN（漏拦）', better: 'none' },
  { key: 'trueNegative', label: 'TN（正确放行）', better: 'none' },
]

const ratioKeys = new Set<keyof Metrics>([
  'asr',
  'detectionPrecision',
  'detectionRecall',
  'f1',
  'falsePositiveRate',
  'benignPassRate',
  'piiLeakageRate',
  'taskSuccessRate',
])

function formatValue(key: keyof Metrics, value: number | undefined): string {
  if (value === undefined || value === null) return '—'
  if (ratioKeys.has(key)) return value.toFixed(6)
  return String(value)
}

/**
 * 按行取指标值。
 *
 * 为什么要在 script 里包一层，而不是在模板里直接写 `metrics?.[row.key]`：
 * 表格插槽里的 `row` 是 `any`，用 `any` 去索引 `Metrics` 在 `strict` 下会报 TS7053。
 * 包一层之后模板只做传参，类型检查落在函数签名上——同时也让"少写一个键"更容易被发现。
 */
function metricValue(metrics: Metrics | null, row: { key: keyof Metrics }): string {
  if (!metrics) return '—'
  return formatValue(row.key, metrics[row.key])
}

function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

async function loadRun(): Promise<void> {
  loading.value = true
  try {
    run.value = await getRun(runId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载任务失败')
  } finally {
    loading.value = false
  }
}

async function loadResults(): Promise<void> {
  resultsLoading.value = true
  try {
    const result = await listRunResults(runId, {
      page: page.value,
      size: size.value,
      category: categoryFilter.value.trim() || undefined,
      passed: passedFilter.value === '' ? undefined : passedFilter.value === 'true',
    })
    results.value = result.records
    resultsTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载结果失败')
  } finally {
    resultsLoading.value = false
  }
}

async function handleExport(format: 'json' | 'csv'): Promise<void> {
  exporting.value = format
  try {
    await exportReport(runId, format)
    ElMessage.success(`已导出 ${format.toUpperCase()} 报告`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导出失败')
  } finally {
    exporting.value = ''
  }
}

onMounted(async () => {
  await loadRun()
  await loadResults()
})
</script>

<template>
  <div v-loading="loading">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>评测报告 #{{ runId }}</span>
          <div class="actions">
            <el-button @click="router.push({ name: 'eval' })">返回列表</el-button>
            <el-button :loading="exporting === 'json'" @click="handleExport('json')">导出 JSON</el-button>
            <el-button :loading="exporting === 'csv'" @click="handleExport('csv')">导出 CSV</el-button>
          </div>
        </div>
      </template>

      <el-descriptions v-if="run" :column="3" border size="small">
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(run.status)" size="small">{{ run.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="模式">{{ run.mode }}</el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ run.completedCases }} / {{ run.totalCases }}（执行失败 {{ run.failedCases }}）
        </el-descriptions-item>
        <el-descriptions-item label="评测集">{{ run.datasetName ?? `#${run.datasetId}` }}</el-descriptions-item>
        <el-descriptions-item label="应用">{{ run.appName ?? `#${run.appId}` }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ run.attempts }}</el-descriptions-item>
        <el-descriptions-item label="Trace">
          <RouterLink
            v-if="run.traceId"
            :to="{ name: 'trace', params: { traceId: run.traceId } }"
            class="mono link"
          >
            {{ run.traceId }}
          </RouterLink>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ run.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ run.finishedAt ?? '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="run.errorMessage" label="备注" :span="3">
          <span class="wrap-anywhere">{{ run.errorMessage }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="run && run.status !== 'SUCCEEDED' && run.status !== 'FAILED'"
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="任务尚未进入终态"
        description="评测任务需要执行面 Worker 从 Redis 队列消费。若队列不可用，任务会停在 PENDING/RUNNING。可用 scripts/eval-e2e.ps1 同步驱动一次（不经队列）。"
      />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <span>指标对照</span>
      </template>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="下面这些数字是控制面从逐条结果独立复算出来的，不是执行面报什么就存什么。"
        description="执行面上报的指标会与控制面的复算值逐键比对（容差 1e-6），不一致会落一条 METRICS_MISMATCH 安全事件。"
      />

      <el-table :data="METRIC_ROWS" border size="small">
        <el-table-column label="指标" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.better === 'low'" class="dir-low">越低越好</span>
            <span v-else-if="row.better === 'high'" class="dir-high">越高越好</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="防护开启" min-width="150" align="right">
          <template #default="{ row }">
            <span class="mono">{{ metricValue(guardOn, row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="防护关闭（基线）" min-width="160" align="right">
          <template #default="{ row }">
            <span class="mono">{{ metricValue(guardOff, row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        class="tip"
        type="warning"
        :closable="false"
        show-icon
        title="解读边界"
        description="这是合成评测集 + 确定性 MockLLM 的结果：它证明机制按设计工作，不证明在真实流量上有效。延迟数字是进程内 Mock 的开销，不代表真实模型。"
      />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>逐条结果</span>
          <div class="filters">
            <el-input v-model="categoryFilter" placeholder="按类别过滤" clearable class="input" />
            <el-select v-model="passedFilter" placeholder="是否通过" clearable class="select">
              <el-option label="通过" value="true" />
              <el-option label="未通过" value="false" />
            </el-select>
            <el-button
              @click="
                () => {
                  page = 1
                  void loadResults()
                }
              "
            >
              查询
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="resultsLoading" :data="results" border size="small">
        <template #empty>
          <el-empty description="没有结果" />
        </template>
        <el-table-column prop="caseId" label="caseId" width="110" />
        <el-table-column prop="category" label="类别" width="200" />
        <el-table-column label="轮次" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.guardEnabled ? 'success' : 'info'" size="small">
              {{ row.guardEnabled ? '防护开' : '防护关' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expectedAction" label="期望" width="100" />
        <el-table-column label="实际" width="100">
          <template #default="{ row }">{{ row.actualAction ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="actualTool" label="实际工具" width="160" />
        <el-table-column label="被拦" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.blocked" type="danger" size="small">是</el-tag>
            <span v-else>否</span>
          </template>
        </el-table-column>
        <el-table-column label="泄露" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.piiLeaked" type="danger" size="small">是</el-tag>
            <span v-else>否</span>
          </template>
        </el-table-column>
        <el-table-column label="通过" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
              {{ row.passed ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="100" align="right" />
        <el-table-column label="命中类别" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.detectedCategories.join(', ') || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="resultsTotal"
        :current-page="page"
        :page-size="size"
        @current-change="
          (value: number) => {
            page = value
            void loadResults()
          }
        "
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

.actions {
  display: flex;
  gap: 8px;
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input {
  width: 200px;
}

.select {
  width: 130px;
}

.tip {
  margin-top: 12px;
}

.dir-low {
  color: #67c23a;
  font-size: 12px;
}

.dir-high {
  color: #409eff;
  font-size: 12px;
}

.link {
  color: #409eff;
  text-decoration: none;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
