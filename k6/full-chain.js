// 完整链路压测：浏览器 → Java 控制面 → Python 执行面 → MockLLM → 工具 → 返回。
//
// 与 control-plane.js 的区别：这里每次请求都会走一次跨服务 HTTP 调用与完整的安检流水线，
// 因此**两者的数字不可互相推断**。
//
// prompt 的选择是有讲究的：
//   * 用"计算类"prompt：会真实调用 calculator 工具，覆盖"决策 → 工具前校验 → 执行 → 输出安检"全路径；
//   * 用"纯对话"prompt：不调用工具，路径更短，可作为对照。
// 脚本里两种交替发送，便于看出工具调用环节带来的额外开销。
//
// 重要提醒：MockLLM 是进程内的确定性逻辑，没有网络调用、没有模型推理。
// 这里的延迟数字**不代表**接入真实模型后的表现，不能作为任何性能声明。

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'admin';
const PASSWORD = __ENV.PASSWORD || '';
const APP_ID = __ENV.APP_ID || '1';

const rateLimited = new Counter('chat_rate_limited');
const toolCallDuration = new Trend('chat_with_tool_duration', true);
const plainDuration = new Trend('chat_plain_duration', true);

const PROMPT_WITH_TOOL = '请帮我算一下 (128 + 472) * 3 等于多少';
const PROMPT_PLAIN = '你好，请简单介绍一下你能做什么';

export const options = {
  stages: [
    { duration: '10s', target: 3 },
    { duration: '40s', target: 10 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    // 仅作"明显异常"的下限，不是目标值（本机从未跑过压测，没有基线）
    'chat_with_tool_duration': ['p(95)<3000'],
    'chat_plain_duration': ['p(95)<2000'],
  },
};

export function setup() {
  if (!PASSWORD) {
    throw new Error('必须通过 -e PASSWORD=... 提供密码（不要写进文件）');
  }
  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200) {
    throw new Error(`登录失败：HTTP ${response.status} ${response.body}`);
  }
  const body = response.json();
  if (body.code !== 0) {
    throw new Error(`登录业务失败：code=${body.code} message=${body.message}`);
  }
  return { token: body.data.token };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };

  // 交替发两种 prompt：偶数迭代走工具，奇数迭代纯对话
  const useTool = __ITER % 2 === 0;
  const prompt = useTool ? PROMPT_WITH_TOOL : PROMPT_PLAIN;
  const tag = useTool ? 'chat_with_tool' : 'chat_plain';

  const response = http.post(
    `${BASE_URL}/api/apps/${APP_ID}/chat`,
    JSON.stringify({ prompt }),
    { headers, tags: { name: tag } },
  );

  if (response.status === 429) {
    // 限流命中：记录但不算失败。压测前应调高额度（见 k6/README.md），
    // 否则这个计数会很高，说明测的是限流而不是链路性能。
    rateLimited.add(1);
    sleep(2);
    return;
  }

  const ok = check(response, {
    [`${tag} 返回 200`]: (r) => r.status === 200,
    [`${tag} 业务码为 0`]: (r) => {
      if (r.status !== 200) return false;
      return r.json('code') === 0;
    },
    [`${tag} 有 traceId`]: (r) => {
      if (r.status !== 200) return false;
      const traceId = r.json('data.traceId');
      return typeof traceId === 'string' && traceId.length > 0;
    },
  });

  if (ok && response.status === 200) {
    const elapsed = response.json('data.elapsedMs');
    if (typeof elapsed === 'number') {
      (useTool ? toolCallDuration : plainDuration).add(elapsed);
    }
  }

  sleep(1);
}
