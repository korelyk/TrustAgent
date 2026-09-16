"""控制面内部接口的异步客户端。

只做三件事：拉执行配置、拉用例、回传结果。**不做任何业务判断**——
"该跑哪一轮""用哪套策略"都由控制面决定，执行面只是执行者。

### 错误分类为什么重要

``ControlPlaneError.retryable`` 决定 Worker 是"不 ACK、等重新投递"还是"直接判定任务失败"。
分错的代价很不对称：
  * 把不可重试的错误当成可重试 → 消息在队列里反复重投，直到耗尽重试次数，
    期间把 Redis 和日志都刷满，真正的原因反而被埋住；
  * 把可重试的错误当成不可重试 → 一次网络抖动就把整个评测任务判死，
    用户看到的是"任务失败"，而不是"稍后自动恢复"。
因此这里按 HTTP 语义区分：网络异常 / 超时 / 5xx 视为可重试，4xx 视为不可重试。
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)

#: 控制面的统一响应包：{ code, message, data, traceId }
_ENVELOPE_OK = 0


class ControlPlaneError(RuntimeError):
    """调用控制面失败。``retryable`` 为真时调用方应保留消息等待重新投递。"""

    def __init__(self, message: str, *, status_code: int | None = None, retryable: bool = True) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


class ControlPlaneClient:
    """控制面内部接口客户端。内部接口需要 ``X-Internal-Token``，与用户 JWT 完全隔离。"""

    def __init__(self, settings: Settings, *, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._closed_owned_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=settings.java_base_url.rstrip("/"),
            timeout=httpx.Timeout(settings.java_callback_timeout_seconds),
            headers={settings.internal_header: settings.internal_token},
        )

    async def aclose(self) -> None:
        if self._closed_owned_client:
            await self._client.aclose()

    # ------------------------------------------------------------------ 公开接口

    async def exec_config(self, run_id: int, round_key: str | None = None) -> dict[str, Any]:
        """拉取执行配置。``round_key`` 取 ``GUARD_ON`` / ``GUARD_OFF``；``BOTH`` 模式必须传。"""
        params: dict[str, Any] = {"runId": run_id}
        if round_key:
            params["round"] = round_key
        return await self._get("/internal/eval/config", params=params)

    async def dataset_cases(self, dataset_id: int) -> dict[str, Any]:
        return await self._get(f"/internal/eval/datasets/{dataset_id}/cases")

    async def callback(self, payload: dict[str, Any], *, trace_id: str | None = None) -> dict[str, Any]:
        return await self._post("/internal/eval/callback", payload, trace_id=trace_id)

    async def healthy(self) -> bool:
        try:
            response = await self._client.get("/health")
            return response.status_code < 400
        except httpx.HTTPError:
            return False

    # ------------------------------------------------------------------ 内部

    async def _get(self, path: str, *, params: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            response = await self._client.get(path, params=params)
        except httpx.HTTPError as exc:
            # 连接失败/超时：控制面可能只是正在重启，属于可重试
            raise ControlPlaneError(f"请求控制面失败 {path}: {exc}", retryable=True) from exc
        return self._unwrap(response, path)

    async def _post(self, path: str, payload: dict[str, Any], *, trace_id: str | None) -> dict[str, Any]:
        headers = {"X-Trace-Id": trace_id} if trace_id else None
        try:
            response = await self._client.post(path, json=payload, headers=headers)
        except httpx.HTTPError as exc:
            raise ControlPlaneError(f"请求控制面失败 {path}: {exc}", retryable=True) from exc
        return self._unwrap(response, path)

    def _unwrap(self, response: httpx.Response, path: str) -> dict[str, Any]:
        status = response.status_code
        if status >= 400:
            detail = _safe_detail(response)
            # 429 是"稍后再来"，属于可重试；其余 4xx 说明请求本身有问题，重试也不会变好
            retryable = status >= 500 or status == 429
            raise ControlPlaneError(
                f"控制面返回 {status} {path}: {detail}", status_code=status, retryable=retryable)

        try:
            body = response.json()
        except ValueError as exc:
            raise ControlPlaneError(f"控制面响应不是 JSON {path}", status_code=status,
                                    retryable=True) from exc

        code = body.get("code")
        if code != _ENVELOPE_OK:
            # 业务码非 0：控制面已经明确拒绝，重试没有意义
            raise ControlPlaneError(
                f"控制面业务错误 {path}: code={code} message={body.get('message')}",
                status_code=status, retryable=False)
        data = body.get("data")
        return data if isinstance(data, dict) else {"value": data}


def _safe_detail(response: httpx.Response) -> str:
    """尽量给出可排查的信息，同时限制长度——错误详情可能被写进日志。"""
    try:
        text = response.text
    except Exception:  # pragma: no cover - 极端情况下读取响应体也会抛
        return "<unreadable>"
    return text[:500] if text else "<empty>"
