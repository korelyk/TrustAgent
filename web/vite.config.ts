import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, type Plugin } from 'vite'

// ---------------------------------------------------------------------------
// CSP：开发与生产必须用**两份不同的策略**
//
// index.html 里的 <meta http-equiv="Content-Security-Policy"> 会被原样打进产物。
// 如果只有一份，那它一定是为了让 dev server 能跑（Vite HMR 需要 ws:），
// 于是**生产产物也带着这条放宽策略** —— 而这里恰恰是最不该放宽的地方。
//
// 生产策略必须与 deploy/Caddyfile.private 的响应头保持一致：
//   connect-src 'self'   —— 前端只访问同源 /api，不允许外部地址，也不允许 WebSocket。
//   本应用运行时**不需要任何 WebSocket**（没有实时推送）。
//
// 因此在构建时按 mode 替换 index.html 中的占位符：dev 宽松、prod 收紧。
// ---------------------------------------------------------------------------
const CSP_PLACEHOLDER = '__TRUSTAGENT_CSP__'

const CSP_COMMON =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

// 开发：Vite HMR 走 WebSocket，必须放行 ws:
const CSP_DEV = CSP_COMMON + "; connect-src 'self' ws:"
// 生产：只允许同源 HTTP，收紧掉 ws:
const CSP_PROD = CSP_COMMON + "; connect-src 'self'"

/**
 * 把 index.html 里的 CSP 占位符替换成当前 mode 对应的策略。
 *
 * 用 apply:'html' + replaceAll（而不是 replace）：
 *   replace 只替换**第一个**匹配，而 index.html 的注释里也提到了占位符，
 *   于是策略会被注入到注释里、真正的 meta 反而保持未替换。
 *   （这是实测踩出来的：产物中 meta 仍是占位符，注释却变成了策略串。）
 */
function cspPlugin(isProd: boolean): Plugin {
  return {
    name: 'trustagent-csp',
    transformIndexHtml: {
      order: 'pre',
      handler(html) {
        return html.replaceAll(CSP_PLACEHOLDER, isProd ? CSP_PROD : CSP_DEV)
      },
    },
  }
}

// 开发服务器把 /api 代理到控制面，这样**前端代码里不出现后端地址**。
// 好处是同一份构建产物可以部署到任何环境（生产由网关/反向代理转发 /api），
// 不需要为每个环境重新打包。
// 用函数式 defineConfig：command 在这里是**可靠**的（'build' | 'serve'）。
// 依赖 transformIndexHtml 的 ctx 则不可靠：实测 ctx.bundle 在该钩子下仍是 undefined，
// 结果生产构建被塞进了开发策略（ws: 又回来了）。
export default defineConfig(({ command }) => ({
  plugins: [vue(), cspPlugin(command === 'build')],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 生产构建不做 sourcemap：这个项目会展示给外部，没必要暴露源码结构
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
}))
