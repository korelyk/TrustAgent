<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { ApiError } from '@/api/client'
import {
  createRun,
  importDataset,
  listApps,
  listDatasetCases,
  listDatasets,
  listRuns,
} from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import type { AgentAppView, DatasetView, EvalCaseView, EvalRunView } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const datasets = ref<DatasetView[]>([])
const runs = ref<EvalRunView[]>([])
const apps = ref<AgentAppView[]>([])

// ---------------------------------------------------------------- 导入评测集

const importVisible = ref(false)
const importing = ref(false)
const importForm = reactive({
  name: '',
  description: '',
  sourceCategory: 'SYNTHETIC_SELF_AUTHORED',
  content: '',
})

function pickFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    importForm.content = String(reader.result ?? '')
    ElMessage.success(`已读取 ${file.name}`)
  }
  reader.onerror = () => ElMessage.error('读取文件失败')
  // 显式按 UTF-8 读：评测集里有中文，按默认编码读会乱码
  reader.readAsText(file, 'utf-8')
  input.value = ''
}

async function handleImport(): Promise<void> {
  if (!importForm.name.trim()) {
    ElMessage.warning('请填写评测集名称')
    return
  }
  if (!importForm.content.trim()) {
    ElMessage.warning('请粘贴 JSONL 内容或选择文件')
    return
  }
  importing.value = true
  try {
    const report = await importDataset({
      name: importForm.name.trim(),
      description: importForm.description.trim() || undefined,
      sourceCategory: importForm.sourceCategory,
      content: importForm.content,
    })
    // 逐行导入的结果要如实展示：部分成功也是信息
    if (report.rejected > 0) {
      ElMessage.warning(`导入完成：成功 ${report.imported} 行，拒绝 ${report.rejected} 行`)
    } else {
      ElMessage.success(`导入完成：共 ${report.totalLines} 行，全部成功`)
    }
    importVisible.value = false
    importForm.name = ''
    importForm.description = ''
    importForm.content = ''
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导入失败')
  } finally {
    importing.value = false
  }
}

// ---------------------------------------------------------------- 用例抽屉

const casesVisible = ref(false)
const casesLoading = ref(false)
const cases = ref<EvalCaseView[]>([])
const casesTotal = ref(0)
const casesPage = ref(1)
const casesDatasetName = ref('')

async function openCases(dataset: DatasetView): Promise<void> {
  casesVisible.value = true
  casesDatasetName.value = dataset.name
  casesPage.value = 1
  await loadCases(dataset.id)
}

async function loadCases(datasetId: number): Promise<void> {
  casesLoading.value = true
  try {
    const result = await listDatasetCases(datasetId, casesPage.value, 20)
    cases.value = result.records
    casesTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载用例失败')
  } finally {
    casesLoading.value = false
  }
}

// ---------------------------------------------------------------- 创建评测任务

const runVisible = ref(false)
const creating = ref(false)
const runForm = reactive({
  datasetId: null as number | null,
  appId: null as number | null,
  mode: 'BOTH',
})

/**
 * 幂等键在**打开对话框时生成一次**，在提交过程中保持不变。
 *
 * 这是正确用法：同一次"创建意图"里的重试必须复用同一个 Key（否则一次超时重试会凭空多出
 * 一个评测任务）；而用户重新打开对话框、重新点创建时应该换一个新 Key（那是新的意图）。
 */
const idempotencyKey = ref('')

function openRunDialog(): void {
  runForm.datasetId = datasets.value[0]?.id ?? null
  runForm.appId = apps.value[0]?.id ?? null
  runForm.mode = 'BOTH'
  idempotencyKey.value = generateKey()
  runVisible.value = true
}

function generateKey(): string {
  try {
    return `web-eval-${crypto.randomUUID()}`
  } catch {
    return `web-eval-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }
}

async function handleCreateRun(): Promise<void> {
  if (!runForm.datasetId || !runForm.appId) {
    ElMessage.warning('请选择评测集与应用')
    return
  }
  creating.value = true
  try {
    const result = await createRun({
      datasetId: runForm.datasetId,
      appId: runForm.appId,
      mode: runForm.mode,
      idempotencyKey: idempotencyKey.value,
    })
    if (result.idempotentReplay) {
      ElMessage.info(`该请求此前已提交过，返回原任务 #${result.runId}`)
    } else {
      ElMessage.success(`任务 #${result.runId} 已创建，共需跑 ${result.totalCases} 条`)
    }
    runVisible.value = false
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建任务失败')
  } finally {
    creating.value = false
  }
}

// ---------------------------------------------------------------- 加载

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const [datasetList, runList, appList] = await Promise.all([
      listDatasets(),
      listRuns(),
      // AUDITOR 也有 app:read，因此这里不会因为它没有 app:create 而失败
      listApps(),
    ])
    datasets.value = datasetList
    runs.value = runList
    apps.value = appList
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

const pendingRunning = computed(() =>
  runs.value.filter((item) => item.status === 'PENDING' || item.status === 'RUNNING'),
)

function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function progressPercent(run: EvalRunView): number {
  if (!run.totalCases) return 0
  return Math.min(100, Math.round((run.completedCases / run.totalCases) * 100))
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading">
    <el-alert
      v-if="pendingRunning.length"
      class="block"
      type="warning"
      :closable="false"
      show-icon
      :title="`有 ${pendingRunning.length} 个任务处于未完成状态`"
      description="评测任务需要执行面 Worker 从 Redis 队列消费。若队列不可用（例如 dev profile 用的是内存队列、或 Redis 未启动），任务会一直停在 PENDING。"
    />

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>评测集</span>
          <div class="actions">
            <el-button @click="loadAll">刷新</el-button>
            <el-button
              v-if="auth.hasPermission('eval:dataset:write')"
              type="primary"
              @click="importVisible = true"
            >
              导入评测集
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="datasets" border size="small">
        <template #empty>
          <el-empty description="还没有评测集。可导入 datasets/synthetic_eval_v1.jsonl" />
        </template>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="sourceCategory" label="来源类别" width="220" />
        <el-table-column prop="caseCount" label="用例数" width="90" align="center" />
        <el-table-column prop="description" label="说明" min-width="240" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCases(row)">查看用例</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="数据来源必须显式标注。SYNTHETIC_SELF_AUTHORED = 自建合成数据，其结论强度低于真实数据，报告里不能混为一谈。"
      />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>评测任务</span>
          <el-button v-if="auth.hasPermission('eval:run')" type="primary" @click="openRunDialog">
            发起评测
          </el-button>
          <el-tooltip v-else content="当前账号没有 eval:run 权限" placement="bottom">
            <el-button disabled>发起评测</el-button>
          </el-tooltip>
        </div>
      </template>

      <el-table :data="runs" border size="small">
        <template #empty>
          <el-empty description="还没有评测任务" />
        </template>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="datasetName" label="评测集" min-width="150" />
        <el-table-column prop="appName" label="应用" min-width="130" />
        <el-table-column prop="mode" label="模式" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="progressPercent(row)" :stroke-width="10" />
            <span class="progress-text">{{ row.completedCases }} / {{ row.totalCases }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <RouterLink :to="{ name: 'eval-run', params: { id: row.id } }" class="link">查看报告</RouterLink>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 导入对话框 -->
    <el-dialog v-model="importVisible" title="导入评测集（JSONL）" width="720px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="importForm.name" placeholder="例如 synthetic-eval-v1" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="importForm.description" placeholder="可选" />
        </el-form-item>
        <el-form-item label="来源类别">
          <el-select v-model="importForm.sourceCategory" class="full">
            <el-option label="SYNTHETIC_SELF_AUTHORED（自建合成数据）" value="SYNTHETIC_SELF_AUTHORED" />
            <el-option label="PUBLIC_BENCHMARK_ADAPTED（公开基准改编）" value="PUBLIC_BENCHMARK_ADAPTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="选择文件">
          <input type="file" accept=".jsonl,.json,.txt" @change="pickFile" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="importForm.content"
            type="textarea"
            :rows="10"
            placeholder="每行一个 JSON 对象；坏行会被逐条拒绝并给出原因，不会整包失败"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="handleImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 创建任务对话框 -->
    <el-dialog v-model="runVisible" title="发起评测任务" width="560px">
      <el-form label-width="90px">
        <el-form-item label="评测集">
          <el-select v-model="runForm.datasetId" class="full" placeholder="选择评测集">
            <el-option v-for="item in datasets" :key="item.id" :label="`${item.name}（${item.caseCount} 条）`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="应用">
          <el-select v-model="runForm.appId" class="full" placeholder="选择应用">
            <el-option v-for="item in apps" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模式">
          <el-radio-group v-model="runForm.mode">
            <el-radio value="GUARD_ON">仅防护开启</el-radio>
            <el-radio value="GUARD_OFF">仅防护关闭（基线）</el-radio>
            <el-radio value="BOTH">两轮对照</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="幂等键">
          <!-- 展示 + 允许复制：出错重试时要复用同一个 Key -->
          <el-input v-model="idempotencyKey" readonly class="mono" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="BOTH 模式会把同一批用例跑两轮（防护开 + 防护关），总条数 = 用例数 × 2。"
      />
      <template #footer>
        <el-button @click="runVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreateRun">创建</el-button>
      </template>
    </el-dialog>

    <!-- 用例抽屉 -->
    <el-drawer v-model="casesVisible" :title="`用例预览 · ${casesDatasetName}`" size="60%">
      <el-table v-loading="casesLoading" :data="cases" border size="small">
        <el-table-column prop="caseId" label="caseId" width="110" />
        <el-table-column prop="category" label="类别" width="200" />
        <el-table-column prop="expectedAction" label="期望动作" width="110" />
        <el-table-column prop="expectedTool" label="期望工具" width="150" />
        <el-table-column label="敏感" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.containsSensitiveData" type="warning" size="small">是</el-tag>
            <span v-else>否</span>
          </template>
        </el-table-column>
        <el-table-column label="prompt" min-width="320">
          <template #default="{ row }">
            <span class="wrap-anywhere">{{ row.prompt }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="casesTotal"
        :current-page="casesPage"
        :page-size="20"
        @current-change="
          (value: number) => {
            casesPage = value
            void loadCases(datasets.find((d) => d.name === casesDatasetName)?.id ?? 0)
          }
        "
      />
    </el-drawer>
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

.full {
  width: 100%;
}

.tip {
  margin-top: 12px;
}

.progress-text {
  font-size: 12px;
  color: #6b7280;
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
