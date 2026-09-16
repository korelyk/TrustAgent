// 所有后端调用的集中出口。
//
// 集中放在一个文件里，好处是"前端到底依赖了哪些接口"一眼可见——
// 排查接口变更影响时不需要满仓库搜 URL。

import { downloadFile, request } from './client'

import type {
  AgentAppView,
  AuditLogView,
  AuditVerifyResult,
  ChatResponse,
  DatasetView,
  EvalCaseResultView,
  EvalCaseView,
  EvalRunView,
  ImportReport,
  LoginResponse,
  PageResult,
  SecurityEventRow,
  SecurityPolicyRequest,
  SecurityPolicyView,
  ToolView,
  TraceDetail,
  UserInfo,
} from '@/types/api'

// ---------------------------------------------------------------- 认证

export function login(username: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>({ url: '/auth/login', method: 'POST', data: { username, password } })
}

export function fetchMe(): Promise<UserInfo> {
  return request<UserInfo>({ url: '/auth/me', method: 'GET' })
}

// ---------------------------------------------------------------- Agent 应用

export function listApps(): Promise<AgentAppView[]> {
  return request<AgentAppView[]>({ url: '/apps', method: 'GET' })
}

export function getApp(appId: number): Promise<AgentAppView> {
  return request<AgentAppView>({ url: `/apps/${appId}`, method: 'GET' })
}

export function createApp(payload: {
  code: string
  name: string
  description?: string
  systemPrompt?: string
}): Promise<AgentAppView> {
  return request<AgentAppView>({ url: '/apps', method: 'POST', data: payload })
}

export function updateApp(
  appId: number,
  payload: { name: string; description?: string; systemPrompt?: string; status: string },
): Promise<AgentAppView> {
  return request<AgentAppView>({ url: `/apps/${appId}`, method: 'PUT', data: payload })
}

export function getPolicy(appId: number): Promise<SecurityPolicyView> {
  return request<SecurityPolicyView>({ url: `/apps/${appId}/policy`, method: 'GET' })
}

export function savePolicy(appId: number, payload: SecurityPolicyRequest): Promise<SecurityPolicyView> {
  return request<SecurityPolicyView>({ url: `/apps/${appId}/policy`, method: 'PUT', data: payload })
}

export function listTools(appId: number): Promise<ToolView[]> {
  return request<ToolView[]>({ url: `/apps/${appId}/tools`, method: 'GET' })
}

export function chat(appId: number, prompt: string, context?: string): Promise<ChatResponse> {
  return request<ChatResponse>({
    url: `/apps/${appId}/chat`,
    method: 'POST',
    // context 为空字符串时不要下发：控制面对空串与 null 的校验行为不同
    data: context ? { prompt, context } : { prompt },
  })
}

// ---------------------------------------------------------------- Trace

export function getTrace(traceId: string): Promise<TraceDetail> {
  return request<TraceDetail>({ url: `/traces/${encodeURIComponent(traceId)}`, method: 'GET' })
}

// ---------------------------------------------------------------- 评测

export function listDatasets(): Promise<DatasetView[]> {
  return request<DatasetView[]>({ url: '/eval/datasets', method: 'GET' })
}

export function importDataset(payload: {
  name: string
  description?: string
  sourceCategory: string
  content: string
}): Promise<ImportReport> {
  return request<ImportReport>({ url: '/eval/datasets/import', method: 'POST', data: payload })
}

export function listDatasetCases(
  datasetId: number,
  page = 1,
  size = 20,
): Promise<PageResult<EvalCaseView>> {
  return request<PageResult<EvalCaseView>>({
    url: `/eval/datasets/${datasetId}/cases`,
    method: 'GET',
    params: { page, size },
  })
}

export function listRuns(status?: string): Promise<EvalRunView[]> {
  return request<EvalRunView[]>({ url: '/eval/runs', method: 'GET', params: status ? { status } : {} })
}

export function getRun(runId: number): Promise<EvalRunView> {
  return request<EvalRunView>({ url: `/eval/runs/${runId}`, method: 'GET' })
}

/**
 * 创建评测任务。
 *
 * **必须带 Idempotency-Key**：控制面缺这个头会直接返回 400，而不是退化成"非幂等"。
 * 用时间戳 + 随机串生成，保证同一个页面上连续点击两次会创建两个任务（用户意图如此），
 * 而"网络重试"这种情况应该复用同一个 Key —— 这里由调用方决定传什么 Key。
 */
export function createRun(payload: {
  datasetId: number
  appId: number
  mode: string
  idempotencyKey: string
}): Promise<{ runId: number; status: string; totalCases: number; idempotentReplay: boolean }> {
  return request({
    url: '/eval/runs',
    method: 'POST',
    headers: { 'Idempotency-Key': payload.idempotencyKey },
    data: { datasetId: payload.datasetId, appId: payload.appId, mode: payload.mode },
  })
}

export function listRunResults(
  runId: number,
  params: { category?: string; passed?: boolean; page?: number; size?: number } = {},
): Promise<PageResult<EvalCaseResultView>> {
  return request<PageResult<EvalCaseResultView>>({
    url: `/eval/runs/${runId}/results`,
    method: 'GET',
    params,
  })
}

export function exportReport(runId: number, format: 'json' | 'csv'): Promise<void> {
  return downloadFile(`/eval/runs/${runId}/report?format=${format}`, `eval-run-${runId}.${format}`)
}

// ---------------------------------------------------------------- 安全事件

export function listSecurityEvents(
  params: { page?: number; size?: number; eventType?: string; severity?: string; appId?: number } = {},
): Promise<PageResult<SecurityEventRow>> {
  return request<PageResult<SecurityEventRow>>({ url: '/security/events', method: 'GET', params })
}

// ---------------------------------------------------------------- 审计

export function listAuditLogs(
  params: { page?: number; size?: number; action?: string } = {},
): Promise<PageResult<AuditLogView>> {
  return request<PageResult<AuditLogView>>({ url: '/audit/logs', method: 'GET', params })
}

export function verifyAuditChain(): Promise<AuditVerifyResult> {
  return request<AuditVerifyResult>({ url: '/audit/verify', method: 'GET' })
}
