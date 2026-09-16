// 与控制面 DTO 对应的类型定义。
//
// 命名与后端保持一致（camelCase）。这些类型是**手工同步**的，
// 改后端 DTO 时这里要跟着改；类型不匹配会在 `npm run build` 的类型检查阶段暴露出来。

/** 控制面统一响应包。`code === 0` 才算成功。 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  traceId: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// ---------------------------------------------------------------- 认证

export interface UserInfo {
  id: number
  username: string
  displayName: string
  email: string | null
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresInSeconds: number
  user: UserInfo
}

// ---------------------------------------------------------------- Agent 应用

export interface AgentAppView {
  id: number
  code: string
  name: string
  description: string | null
  ownerId: number
  ownerName: string | null
  systemPrompt: string | null
  status: string
  enabledToolCount: number
  createdAt: string
  updatedAt: string
}

export interface SecurityPolicyView {
  appId: number
  version: number
  guardEnabled: boolean
  maxToolCalls: number
  maxInputLength: number
  taskTimeoutMs: number
  domainWhitelist: string[]
  deniedParamRules: string[]
  confirmSideEffects: boolean
  injectionDetectionEnabled: boolean
  piiMaskingEnabled: boolean
  outputGuardEnabled: boolean
  updatedAt: string
}

export interface SecurityPolicyRequest {
  guardEnabled: boolean
  maxToolCalls: number
  maxInputLength: number
  taskTimeoutMs: number
  domainWhitelist: string[]
  deniedParamRules: string[]
  confirmSideEffects: boolean
  injectionDetectionEnabled: boolean
  piiMaskingEnabled: boolean
  outputGuardEnabled: boolean
  /** 乐观锁版本。与服务端不一致时返回 409。 */
  version: number | null
}

export interface ToolView {
  toolName: string
  enabled: boolean
  paramSchema: string | null
  description: string | null
  sideEffect: boolean
}

// ---------------------------------------------------------------- Chat / Trace

export interface ToolCallView {
  toolName: string
  allowed: boolean
  denyReason: string | null
  argumentsMasked: string | null
  resultMasked: string | null
  latencyMs: number
}

export interface SecurityEventView {
  eventType: string
  severity: string
  detector: string
  ruleId: string | null
  evidenceMasked: string | null
  actionTaken: string
}

export interface ChatResponse {
  traceId: string
  status: string
  guardDecision: string
  guardCategories: string[]
  inputMasked: string
  outputMasked: string
  toolCalls: ToolCallView[]
  securityEvents: SecurityEventView[]
  model: string
  elapsedMs: number
  policyVersion: number
}

/** Trace 主体（对应后端 `AgentTrace` 实体）。 */
export interface AgentTrace {
  id: number
  traceId: string
  appId: number | null
  userId: number | null
  requestId: string | null
  inputPreview: string | null
  outputPreview: string | null
  guardDecision: string | null
  guardCategories: string | null
  status: string
  toolCallCount: number
  totalLatencyMs: number
  errorMessage: string | null
  createdAt: string
}

/**
 * GET /api/traces/{traceId} 的返回。
 *
 * 注意这是**嵌套结构**（`{ trace, toolCalls, securityEvents, auditLogs }`），
 * 不是把 trace 字段平铺在顶层 —— 页面上要按这个层级取数据。
 */
export interface TraceDetail {
  trace: AgentTrace
  toolCalls: ToolCallView[]
  securityEvents: SecurityEventRow[]
  auditLogs: AuditLogView[]
}

/** 安全事件（列表接口与 Trace 详情共用同一个结构）。 */
export interface SecurityEventRow {
  id: number
  traceId: string | null
  appId: number | null
  eventType: string
  severity: string
  detector: string
  ruleId: string | null
  evidenceMasked: string | null
  actionTaken: string
  createdAt: string
}

// ---------------------------------------------------------------- 评测

export interface DatasetView {
  id: number
  name: string
  description: string | null
  sourceCategory: string
  caseCount: number
  createdAt: string
}

export interface ImportReport {
  datasetId: number
  name: string
  sourceCategory: string
  totalLines: number
  imported: number
  rejected: number
  errors: string[]
}

export interface EvalCaseView {
  caseId: string
  category: string
  prompt: string
  context: string | null
  expectedAction: string
  expectedTool: string | null
  containsSensitiveData: boolean
  tags: string[]
}

/** 指标快照。附带 tp/fp/fn/tn 等计数，目的是让报告能自证。 */
export interface Metrics {
  asr: number
  detectionPrecision: number
  detectionRecall: number
  f1: number
  falsePositiveRate: number
  benignPassRate: number
  piiLeakageRate: number
  taskSuccessRate: number
  p50LatencyMs: number
  p95LatencyMs: number
  totalCases: number
  attackCases: number
  benignCases: number
  maskCases: number
  truePositive: number
  falsePositive: number
  falseNegative: number
  trueNegative: number
}

export interface EvalRunView {
  id: number
  datasetId: number
  datasetName: string | null
  appId: number
  appName: string | null
  ownerId: number
  mode: string
  status: string
  totalCases: number
  completedCases: number
  failedCases: number
  attempts: number
  errorMessage: string | null
  traceId: string | null
  /** 列表接口不返回指标（避免响应体膨胀），详情接口才返回。所以这里是可选的。 */
  metrics?: { PAIRED?: { guardOn?: Metrics; guardOff?: Metrics } }
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface EvalCaseResultView {
  caseId: string
  category: string
  guardEnabled: boolean
  expectedAction: string | null
  actualAction: string | null
  expectedTool: string | null
  actualTool: string | null
  blocked: boolean
  detectedCategories: string[]
  piiLeaked: boolean
  passed: boolean
  latencyMs: number
  detailJson: string | null
  errorMessage: string | null
}

// ---------------------------------------------------------------- 审计

export interface AuditLogView {
  seq: number
  actorUsername: string | null
  action: string
  resourceType: string | null
  resourceId: string | null
  detailJson: string | null
  traceId: string | null
  prevHash: string
  recordHash: string
  createdAt: string
}

export interface AuditVerifyResult {
  valid: boolean
  checkedRecords: number
  firstBrokenSeq: number | null
  reason: string
  detail: string | null
  verifiedAt: string
}
