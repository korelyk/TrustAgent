// Token 的存取单独放一个模块。
//
// 为什么不放在 pinia store 里：`api/client.ts` 需要在请求拦截器里读 Token，
// 而 store 又需要调用 api 层的 login —— 两者直接互相引用会形成循环依赖。
// 把"存哪儿"这件事抽出来，两个模块就都只依赖它。
//
// **已知取舍（安全相关，必须说清楚）**：
// 用 localStorage 存 JWT，意味着一旦发生 XSS，Token 会被直接读走。
// 本项目用两道措施降低这个风险：
//   1. 前端禁止 v-html / innerHTML，模型输出只用文本插值（CI 里有强制检查）；
//   2. index.html 里配置了 CSP，禁止内联脚本。
// 生产环境更稳妥的做法是 httpOnly Cookie + CSRF Token，本项目未采用。

const TOKEN_KEY = 'trustagent.token'

export function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用，退化到"未登录"而不是抛异常
    return null
  }
}

export function writeToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // 存不下就只在当前会话内存里用，登录本身仍然成功
  }
}

export function clearToken(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY)
  } catch {
    // 忽略
  }
}
