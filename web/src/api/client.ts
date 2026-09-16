import axios, { AxiosError, type AxiosInstance, type AxiosResponse } from 'axios'

import type { ApiEnvelope } from '@/types/api'

import { clearToken, readToken } from './token'

/** 业务错误。携带控制面的业务码与 traceId，便于让用户拿着 traceId 去查日志。 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId: string | null
  readonly httpStatus: number | null

  constructor(message: string, code: number, traceId: string | null, httpStatus: number | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.httpStatus = httpStatus
  }
}

// 401 的处理交给路由层：api 层直接 import router 会形成循环依赖，
// 因此把"该做什么"注册进来，而不是在这里写死。
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

const http: AxiosInstance = axios.create({
  // 用相对路径：开发时由 Vite 代理到控制面，生产由网关转发。
  // 前端代码里不出现后端地址，同一份构建产物可以部署到任何环境。
  baseURL: '/api',
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = readToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  // 主动生成 X-Trace-Id：这样"用户这次操作用的 traceId"与"后端日志里的 traceId"是同一个值。
  // 出错时把这个 id 给出去就能直接定位日志，不需要靠时间戳猜。
  try {
    config.headers.set('X-Trace-Id', crypto.randomUUID().replace(/-/g, ''))
  } catch {
    // 非安全上下文下 crypto.randomUUID 可能不可用；此时让后端自己生成 traceId
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError<ApiEnvelope<unknown>>) => {
    const status = error.response?.status ?? null
    const body = error.response?.data

    if (status === 401) {
      clearToken()
      unauthorizedHandler?.()
      return Promise.reject(new ApiError(body?.message ?? '登录已失效，请重新登录', 401, body?.traceId ?? null, status))
    }

    return Promise.reject(
      new ApiError(
        body?.message ?? error.message ?? '请求失败',
        body?.code ?? -1,
        body?.traceId ?? null,
        status,
      ),
    )
  },
)

/**
 * 发起请求并解包统一响应包 `{ code, message, data, traceId }`。
 *
 * 为什么在这里统一解包而不是每个页面各写一遍：控制面**所有**接口都返回同样的信封，
 * 解包逻辑散落各处一定会有人忘记判 `code`，从而把失败当成功处理。
 */
export async function request<T>(config: Parameters<AxiosInstance['request']>[0]): Promise<T> {
  const response = await http.request<ApiEnvelope<T>>(config)
  const envelope = response.data
  if (envelope.code !== 0) {
    throw new ApiError(envelope.message || '业务处理失败', envelope.code, envelope.traceId, response.status)
  }
  return envelope.data
}

/**
 * 清理服务端给的文件名，只保留"最后一个路径段"并剔除危险字符。
 *
 * 浏览器的 link.download 本身会忽略路径分隔符，所以这不是在防当前的漏洞；
 * 防的是**将来**：一旦某个调用方把用户输入接到 filename 上，
 * 或者后端改了 Content-Disposition，下载行为就会变得不可预期。
 * 净化一次的成本是零，收益是"这个函数对任何输入都安全"。
 */
function safeFilename(name: string, fallback: string): string {
  const base = name.split(/[\\/]/).pop() ?? ''
  // 去掉控制字符与 Windows 保留字符，并限制长度
  const cleaned = base.replace(/[\u0000-\u001f<>:"|?*]/g, '').trim().slice(0, 120)
  return cleaned || fallback
}

/**
 * 下载文件（报告导出）。
 *
 * 报告接口**不套统一响应包**，返回的就是文件内容，因此不能走上面的解包逻辑。
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const response = await http.request<Blob>({ url: path, method: 'GET', responseType: 'blob' })
  const url = window.URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = safeFilename(filename, 'download')
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

export default http
