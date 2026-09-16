<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { ApiError } from '@/api/client'
import { listAuditLogs, verifyAuditChain } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import type { AuditLogView, AuditVerifyResult } from '@/types/api'

const auth = useAuthStore()

const loading = ref(false)
const verifying = ref(false)
const rows = ref<AuditLogView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const actionFilter = ref('')
const verifyResult = ref<AuditVerifyResult | null>(null)
const expandedSeq = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listAuditLogs({
      page: page.value,
      size: size.value,
      action: actionFilter.value.trim() || undefined,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载审计日志失败')
  } finally {
    loading.value = false
  }
}

async function handleVerify(): Promise<void> {
  verifying.value = true
  try {
    verifyResult.value = await verifyAuditChain()
    if (verifyResult.value.valid) {
      ElMessage.success(`哈希链完整，已检查 ${verifyResult.value.checkedRecords} 条记录`)
    } else {
      ElMessage.error(
        `发现异常：第 ${verifyResult.value.firstBrokenSeq} 条，原因 ${verifyResult.value.reason}`,
      )
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '验证失败')
  } finally {
    verifying.value = false
  }
}

function toggleDetail(seq: number): void {
  expandedSeq.value = expandedSeq.value === seq ? null : seq
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>审计哈希链验证</span>
          <el-button
            v-if="auth.hasPermission('audit:verify')"
            type="primary"
            :loading="verifying"
            @click="handleVerify"
          >
            验证完整性
          </el-button>
          <el-tooltip v-else content="当前账号没有 audit:verify 权限" placement="bottom">
            <el-button disabled>验证完整性</el-button>
          </el-tooltip>
        </div>
      </template>

      <el-empty v-if="!verifyResult" description="点击「验证完整性」检查审计链是否被篡改" :image-size="80" />

      <template v-else>
        <el-result
          :icon="verifyResult.valid ? 'success' : 'error'"
          :title="verifyResult.valid ? '哈希链完整' : '检测到篡改'"
          :sub-title="
            verifyResult.valid
              ? `已检查 ${verifyResult.checkedRecords} 条记录，未发现断链`
              : `首个异常位置：seq=${verifyResult.firstBrokenSeq}，原因：${verifyResult.reason}`
          "
        >
          <template #extra>
            <el-descriptions :column="1" border size="small" class="verify-desc">
              <el-descriptions-item label="valid">{{ verifyResult.valid }}</el-descriptions-item>
              <el-descriptions-item label="已检查记录数">{{ verifyResult.checkedRecords }}</el-descriptions-item>
              <el-descriptions-item label="首个异常 seq">{{ verifyResult.firstBrokenSeq ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="原因">{{ verifyResult.reason }}</el-descriptions-item>
              <el-descriptions-item v-if="verifyResult.detail" label="详情">
                <span class="wrap-anywhere">{{ verifyResult.detail }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="验证时间">{{ verifyResult.verifiedAt }}</el-descriptions-item>
            </el-descriptions>
          </template>
        </el-result>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="这是「篡改可检测」，不是「不可篡改」"
          description="哈希链只能证明链内自洽：拥有数据库完全写权限的攻击者可以重算整条链。要抵抗这一点必须把链尾哈希外送到攻击者控制不到的地方（外部存证、WORM 存储）。这不是区块链。"
        />
      </template>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>审计日志</span>
          <div class="filters">
            <el-input
              v-model="actionFilter"
              placeholder="按动作过滤，如 SECURITY_POLICY_UPDATED"
              clearable
              class="input"
              @keyup.enter="
                () => {
                  page = 1
                  void load()
                }
              "
            />
            <el-button
              @click="
                () => {
                  page = 1
                  void load()
                }
              "
            >
              查询
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" border size="small">
        <template #empty>
          <el-empty description="没有审计记录" />
        </template>
        <el-table-column prop="seq" label="seq" width="80" />
        <el-table-column prop="action" label="动作" width="220" />
        <el-table-column prop="actorUsername" label="操作者" width="130" />
        <el-table-column prop="resourceType" label="资源类型" width="150" />
        <el-table-column prop="resourceId" label="资源 ID" width="100" />
        <el-table-column label="记录哈希" min-width="220">
          <template #default="{ row }">
            <span class="mono hash">{{ row.recordHash }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="明细" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="toggleDetail(row.seq)">
              {{ expandedSeq === row.seq ? '收起' : '查看' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 明细单独一行展开：detailJson 可能很长，塞进表格列会挤爆其它列 -->
      <div v-if="expandedSeq !== null" class="detail">
        <div class="detail-title">seq = {{ expandedSeq }} 的明细（已脱敏）</div>
        <pre class="mono detail-body">{{ rows.find((item) => item.seq === expandedSeq)?.detailJson ?? '—' }}</pre>
        <div class="detail-title">链式关系</div>
        <div class="mono wrap-anywhere">
          prevHash = {{ rows.find((item) => item.seq === expandedSeq)?.prevHash }}
        </div>
      </div>

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

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input {
  width: 320px;
}

.verify-desc {
  max-width: 520px;
  margin: 0 auto;
  text-align: left;
}

.hash {
  font-size: 11px;
}

.detail {
  margin-top: 12px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}

.detail-title {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 6px;
}

.detail-body {
  margin: 0 0 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
