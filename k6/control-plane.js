// 只测 Java 控制接口的压测脚本。
//
// 覆盖四类接口，代表控制面上不同的负载类型：
//   * POST /api/auth/login        写审计 + 走限流（写路径）
//   * GET  /api/apps              分页查询（读路径）
//   * GET  /api/apps/{id}/policy  单行查询（读路径）
//   * GET  /api/audit/verify      全表扫描 + 哈希重算（重读路径，最容易成为瓶颈）
//
// 最后一项是刻意放进去的：审计链验证要读全表并逐条重算 SHA-256，
// 数据量增长后它会先于其他接口变慢。压测能提前暴露这一点。
//
// 运行前请先按 k6/README.md 调整限流额度，否则测到的是限流器。

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'admin';
const PASSWORD = __ENV.PASSWORD || '';
const APP_ID = __ENV.APP_ID || '1';

const loginFailures = new Rate('login_failures');
const auditVerifyDuration = new Trend('audit_verify_duration', true);

export const options = {
  // 冒烟优先：先用 1 个 VU 跑 10 秒确认链路通，再逐步加压。
  // 直接上高并发会把"脚本写错了"和"系统扛不住"混在一起，无法区分。
  stages: [
    { duration: '10s', target: 5 },
    { duration: '30s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    // 注意：这些数字不是"目标值"，而是"明显异常"的下限。
    // 本项目尚未在本机跑过压测，没有基线可以定出合理的阈值。
    http_req_failed: ['rate<0.05'],
    'http_req_duration{name:list_apps}': ['p(95)<500'],
    'http_req_duration{name:get_policy}': ['p(95)<500'],
    'audit_verify_duration': ['p(95)<2000'],
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
  // 控制面的统一响应包：{ code, message, data, traceId }
  if (body.code !== 0) {
    throw new Error(`登录业务失败：code=${body.code} message=${body.message}`);
  }
  return { token: body.data.token };
}

export default function (data) {
  const authHeader = { headers: { Authorization: `Bearer ${data.token}` } };

  // 1) 分页查询（读）
  const apps = http.get(`${BASE_URL}/api/apps`, { ...authHeader, tags: { name: 'list_apps' } });
  check(apps, { 'list_apps 返回 200': (r) => r.status === 200 });

  // 2) 单行查询（读）
  const policy = http.get(`${BASE_URL}/api/apps/${APP_ID}/policy`, {
    ...authHeader,
    tags: { name: 'get_policy' },
  });
  check(policy, { 'get_policy 返回 2xx': (r) => r.status >= 200 && r.status < 300 });

  // 3) 审计链验证（重读：全表扫描 + 逐条重算哈希）
  const verify = http.get(`${BASE_URL}/api/audit/verify`, {
    ...authHeader,
    tags: { name: 'audit_verify' },
  });
  auditVerifyDuration.add(verify.timings.duration);
  check(verify, { 'audit_verify 返回 200': (r) => r.status === 200 });

  // 4) 登录（写路径 + 限流）
  // 刻意放在最后：前面的请求已经消耗了一段时间，能测出限流窗口行为是否符合预期。
  const login = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  const ok = check(login, {
    'login 返回 200 或 429': (r) => r.status === 200 || r.status === 429,
  });
  // 429 是限流生效（预期行为），不算失败；5xx 才算。
  loginFailures.add(login.status >= 500);

  sleep(1);
}
