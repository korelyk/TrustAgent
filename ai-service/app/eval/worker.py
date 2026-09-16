"""评测 Worker：Redis Streams 消费者。

### 为什么是 Streams 而不是"同步 HTTP 跑批量评测"

评测一次要跑上百条用例、每条都要过一次完整编排（安检 → 决策 → 工具 → 输出安检）。
如果用同步 HTTP：
  * 一个 100 条的评测要占住一个请求几分钟，中间任何一次网络抖动都会让整批白跑；
  * 控制面线程池会被评测任务占满，正常的 Agent 请求被饿死；
  * 无法"断点续跑"，也没有天然的消费进度与重试计数。

Streams 提供的是：**消息不丢（Pending Entries List）、消费组（多 Worker 分摊）、
可查询的投递次数（用于重试与死信）**。这三件事恰好是异步评测需要的，且依赖极轻。

### 至少一次投递 + 幂等写入

本 Worker 的语义是 **at-least-once**：
  * 处理成功才 ``XACK``；失败就**不 ACK**，消息留在 PEL 里，稍后由 ``XAUTOCLAIM`` 重新认领；
  * 投递次数超过 ``eval_max_deliveries`` 时转入死信 Stream 并回调控制面标记失败；
  * 重复投递的代价是"可能白跑一遍用例"，但**不会产生重复结果行**——
    控制面用唯一索引 ``(run_id, case_id, guard_enabled)`` 兜底，重复的批次会被计入 ``skipped``。

这是刻意的取舍：宁可多跑一遍，也不要因为"怕重复"而丢结果。

### 为什么消息体里只有 runId

见 ``EvalJobMessage``（Java 侧）的注释：用例文本、systemPrompt、工具 Schema 加起来几十 KB，
塞进消息里既浪费内存，也让"按当时的策略回放"变得不可能。消息只带引用，
真正执行时再回控制面拉一次快照——**跑的就是当时生效的那一版**。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import math
import os
import signal
import sys
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import redis.asyncio as aioredis

from app.config import Settings, get_settings
from app.eval.java_client import ControlPlaneClient, ControlPlaneError
from app.eval.metrics import DOCUMENTED_KEYS, compute, mismatched_keys
from app.eval.runner import EvalRunner
from app.observability.logging import configure_logging, kv
from app.observability.trace import set_trace_id
from app.runtime import build_runtime

logger = logging.getLogger(__name__)

ROUND_GUARD_ON = "GUARD_ON"
ROUND_GUARD_OFF = "GUARD_OFF"

MODE_GUARD_ON = "GUARD_ON"
MODE_GUARD_OFF = "GUARD_OFF"
MODE_BOTH = "BOTH"

_JOB_TYPE_CASE_RESULTS = "CASE_RESULTS"
_JOB_TYPE_COMPLETED = "COMPLETED"
_JOB_TYPE_FAILED = "FAILED"


@dataclass(frozen=True)
class EvalJobMessage:
    """Redis Stream 消息（字段值全是字符串，这是 Streams 的协议限制）。"""

    run_id: int
    dataset_id: int
    app_id: int
    mode: str
    attempt: int
    trace_id: str | None

    @classmethod
    def from_fields(cls, fields: Mapping[str, Any] | None) -> "EvalJobMessage | None":
        """解析失败返回 ``None``，由调用方决定丢弃并 ACK。

        一条永远解析不了的消息留在 PEL 里只会反复失败、堵住队列，
        所以宁可 ACK 丢弃并告警，也不要让它无限重投。
        """
        if not fields:
            return None
        try:
            run_id = int(fields["runId"])
            dataset_id = int(fields["datasetId"])
            app_id = int(fields["appId"])
        except (KeyError, TypeError, ValueError):
            return None
        return cls(
            run_id=run_id,
            dataset_id=dataset_id,
            app_id=app_id,
            mode=str(fields.get("mode") or "").strip().upper(),
            attempt=_int_or(fields.get("attempt"), 1),
            trace_id=(str(fields["traceId"]).strip() or None) if fields.get("traceId") else None,
        )

    def to_fields(self) -> dict[str, str]:
        return {
            "runId": str(self.run_id),
            "datasetId": str(self.dataset_id),
            "appId": str(self.app_id),
            "mode": self.mode,
            "attempt": str(self.attempt),
            "traceId": self.trace_id or "",
        }


def rounds_for_mode(mode: str) -> list[tuple[str, bool]]:
    """该模式需要跑哪几轮，返回 ``[(round关键字, guardEnabled)]``。

    ``guardEnabled`` 会一路带到回调里成为 ``eval_case_result.guard_enabled``，
    标错就会把对照组的数字混进防护组——所以这个映射只允许有一处定义。
    """
    normalized = (mode or "").strip().upper()
    if normalized == MODE_GUARD_ON:
        return [(ROUND_GUARD_ON, True)]
    if normalized == MODE_GUARD_OFF:
        return [(ROUND_GUARD_OFF, False)]
    if normalized == MODE_BOTH:
        return [(ROUND_GUARD_ON, True), (ROUND_GUARD_OFF, False)]
    return []


# --------------------------------------------------------------------------- #
# 测试专用故障注入（默认关闭）
#
# 存在的唯一理由：验证 Worker 的崩溃恢复——「未 ACK → 留在 PEL → XAUTOCLAIM 重投
# → 走到终态」。这件事**无法**用假消费者或纯单元测试证明，必须让**真实的 Worker 进程**
# 在**精确的位置**死掉，再让它重启，然后去库里看任务有没有被重新认领并完成。
#
# 三条安全约束，缺一不可：
#   1. **默认关闭**：TRUSTAGENT_EVAL_FAULT 未设置时全部是空操作。
#      开启与关闭走的是同一条代码路径，不存在"忘了关开关就少一段逻辑"。
#   2. **只命中指定任务**：格式 ``<故障点>:<runId>``。runId 不匹配就不触发，
#      因此不会出现"每次重启都在同一处崩溃导致恢复测试永远跑不完"。
#   3. **不暴露任何 HTTP 接口**：普通用户无法通过 API 触发进程退出。
#      能触发它的只有能改容器环境变量的人，也就是部署者本人。
# --------------------------------------------------------------------------- #

_FAULT_ENV = "TRUSTAGENT_EVAL_FAULT"


def _fault_spec() -> tuple[str, str]:
    """解析 ``<故障点>:<runId>``；未配置时返回两个空串。"""
    raw = os.getenv(_FAULT_ENV, "").strip()
    if not raw:
        return "", ""
    point, _, target = raw.partition(":")
    return point.strip(), target.strip()


def _fault_matches(point: str, run_id: Any) -> bool:
    spec_point, spec_target = _fault_spec()
    if not spec_point or spec_point != point:
        return False
    # 未指定 runId 时视为"对任意任务生效"，仅用于人工调试；验收脚本一律显式指定
    return not spec_target or spec_target == str(run_id)


def _fault_crash(point: str, run_id: Any) -> None:
    """在指定故障点**立刻终止进程**，模拟被 kill -9。

    用 ``os._exit()`` 而不是 ``raise``：抛异常会被 ``_process`` 的
    ``except Exception`` 捕获，走的仍是"优雅地不 ACK"路径；
    而崩溃恢复要验证的是"进程没了、任何清理都没做"。
    """
    if not _fault_matches(point, run_id):
        return
    logger.error(kv(event="eval.worker.fault.crash", point=point, runId=str(run_id)))
    sys.stderr.write(f"[FAULT] crash at {point} runId={run_id}\n")
    sys.stderr.flush()
    os._exit(97)


def _fault_retryable(point: str, run_id: Any) -> None:
    """在指定故障点抛可重试错误，用于把任务推到最大投递次数进而进入死信。"""
    if not _fault_matches(point, run_id):
        return
    logger.warning(kv(event="eval.worker.fault.retryable", point=point, runId=str(run_id)))
    raise ControlPlaneError(f"测试注入的可重试故障（runId={run_id}）", retryable=True)


class EvalJobHandler:
    """单条任务的执行逻辑。**刻意不碰 Redis**，这样它可以被单元测试直接驱动。"""

    def __init__(self, *, runner: EvalRunner, control_plane: ControlPlaneClient, batch_size: int = 20) -> None:
        self._runner = runner
        self._control_plane = control_plane
        self._batch_size = max(1, batch_size)

    async def handle(self, message: EvalJobMessage, *, attempt: int | None = None) -> dict[str, Any]:
        set_trace_id(message.trace_id)

        # 故障点 always_fail：在**任何工作开始之前**就抛可重试错误。
        # 用途是把任务一路推到最大投递次数，验证它最终进入死信并让数据库里的
        # run 到达明确终态（FAILED），而不是永远停在 RUNNING。
        _fault_retryable("always_fail", message.run_id)

        rounds = rounds_for_mode(message.mode)
        if not rounds:
            # 未知模式：控制面与本 Worker 的定义已漂移，重试也不会变好
            raise ControlPlaneError(f"未知的评测模式: {message.mode!r}", retryable=False)

        cases = await self._pull_cases(message)
        if not cases:
            raise ControlPlaneError(f"数据集 {message.dataset_id} 没有任何用例", retryable=False)

        # 故障点 before_result：已经消费了消息、但**一条结果都还没写**就崩溃。
        # 重投后应当从头完整跑一遍，且因为此前没有任何写入，结果行数不会多也不会少。
        _fault_crash("before_result", message.run_id)

        sensitive_ids = {
            str(case.get("caseId")) for case in cases if case.get("containsSensitiveData")
        }
        effective_attempt = attempt or message.attempt
        metrics: dict[str, Any] = {}
        total_results = 0

        for round_key, guard_enabled in rounds:
            config = await self._pull_config(message, round_key, guard_enabled)
            results = []
            for index in range(0, len(cases), self._batch_size):
                chunk = cases[index:index + self._batch_size]
                chunk_results = [
                    self._runner.run_case(case, config, guard_enabled=guard_enabled) for case in chunk
                ]
                results.extend(chunk_results)
                # 分批回传：Worker 中途挂掉时，已落库的部分会被幂等跳过，重试代价被限制在最后一批
                await self._post_case_results(message, chunk_results, effective_attempt)

            recomputed = compute([item.to_outcome() for item in results], sensitive_ids)
            reported = recomputed.to_map()
            _assert_reportable(reported, message, round_key)
            metrics["guardOn" if guard_enabled else "guardOff"] = reported
            total_results += len(results)
            logger.info(kv(event="eval.round.done", runId=message.run_id, round=round_key,
                           cases=len(results), asr=recomputed.asr,
                           precision=recomputed.detection_precision))

        completed = await self._control_plane.callback({
            "runId": message.run_id,
            "type": _JOB_TYPE_COMPLETED,
            "attempt": effective_attempt,
            "traceId": message.trace_id or "",
            "metrics": metrics,
        }, trace_id=message.trace_id)
        logger.info(kv(event="eval.run.completed", runId=message.run_id, results=total_results,
                       runStatus=completed.get("runStatus")))
        return {"runId": message.run_id, "results": total_results,
                "runStatus": completed.get("runStatus"), "metrics": metrics}

    async def mark_failed(self, message: EvalJobMessage, reason: str, *, attempt: int | None = None) -> None:
        """告知控制面"这个任务失败了"。**不能抛异常**：调用它时我们已经在收尾路径上。"""
        try:
            await self._control_plane.callback({
                "runId": message.run_id,
                "type": _JOB_TYPE_FAILED,
                "attempt": attempt or message.attempt,
                "traceId": message.trace_id or "",
                "errorMessage": reason[:500],
            }, trace_id=message.trace_id)
        except Exception as exc:  # pragma: no cover - 收尾路径失败只能记日志
            logger.error(kv(event="eval.failed_callback.error", runId=message.run_id, error=str(exc)))

    # ------------------------------------------------------------------ 内部

    async def _pull_cases(self, message: EvalJobMessage) -> list[dict[str, Any]]:
        payload = await self._control_plane.dataset_cases(message.dataset_id)
        cases = payload.get("cases") if isinstance(payload, dict) else None
        return list(cases or [])

    async def _pull_config(self, message: EvalJobMessage, round_key: str,
                           guard_enabled: bool) -> dict[str, Any]:
        config = await self._control_plane.exec_config(message.run_id, round_key)
        reported_round = str(config.get("round") or "").strip().upper()
        if reported_round and reported_round != round_key:
            # 控制面明确告诉了我们这份配置属于哪一轮，对不上说明契约被破坏了。
            # 继续跑下去会把结果标错 guardEnabled，静默污染整个对照组——必须直接失败。
            raise ControlPlaneError(
                f"控制面返回的轮次({reported_round})与请求的轮次({round_key})不一致",
                retryable=False)
        policy = config.get("policy") or {}
        if bool(policy.get("guardEnabled")) != guard_enabled:
            raise ControlPlaneError(
                f"策略的 guardEnabled({policy.get('guardEnabled')}) 与轮次({round_key})不一致",
                retryable=False)
        return config

    async def _post_case_results(self, message: EvalJobMessage, results: Sequence[Any],
                                 attempt: int) -> dict[str, Any]:
        return await self._control_plane.callback({
            "runId": message.run_id,
            "type": _JOB_TYPE_CASE_RESULTS,
            "attempt": attempt,
            "traceId": message.trace_id or "",
            "results": [item.to_payload() for item in results],
        }, trace_id=message.trace_id)


def _assert_reportable(reported: Mapping[str, Any], message: EvalJobMessage, round_key: str) -> None:
    """上报前自检：10 个指标必须齐全且是有限数。

    ``NaN`` / ``Infinity`` 不是合法 JSON，序列化后控制面读到的会是 ``null``，
    进而被判成"指标缺失"并记一条 ``METRICS_MISMATCH``。
    与其让控制面去发现，不如在执行面就拦住。
    """
    missing = [key for key in DOCUMENTED_KEYS if key not in reported]
    if missing:
        raise ControlPlaneError(
            f"指标缺失 {missing}（runId={message.run_id} round={round_key}）", retryable=False)
    for key in DOCUMENTED_KEYS:
        value = reported.get(key)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(float(value)):
            raise ControlPlaneError(
                f"指标 {key} 不是有限数: {value!r}（runId={message.run_id} round={round_key}）",
                retryable=False)

    # 再走一遍 JSON 往返：验的是"发出去的那份数据"而不是内存里的那份。
    # 例如某个值被序列化成字符串，就会在这里被发现，而不是等控制面报 METRICS_MISMATCH。
    roundtripped = json.loads(json.dumps(reported))
    from app.eval.metrics import Metrics  # 局部导入避免循环依赖
    recomputed = Metrics(
        asr=float(reported["asr"]),
        detection_precision=float(reported["detectionPrecision"]),
        detection_recall=float(reported["detectionRecall"]),
        f1=float(reported["f1"]),
        false_positive_rate=float(reported["falsePositiveRate"]),
        benign_pass_rate=float(reported["benignPassRate"]),
        pii_leakage_rate=float(reported["piiLeakageRate"]),
        task_success_rate=float(reported["taskSuccessRate"]),
        p50_latency_ms=float(reported["p50LatencyMs"]),
        p95_latency_ms=float(reported["p95LatencyMs"]),
    )
    drift = mismatched_keys(roundtripped, recomputed)
    if drift:
        raise ControlPlaneError(
            f"指标 JSON 往返后发生漂移 {drift}（runId={message.run_id} round={round_key}）",
            retryable=False)


def _connect(settings: Settings):
    """建立 Redis 连接。**必须显式设置 socket_timeout**（见下）。

    ### 为什么不能省掉 socket_timeout

    redis-py 8.x 把 ``socket_timeout`` 的默认值从 ``None`` 改成了
    ``DEFAULT_SOCKET_TIMEOUT``（一个有限值）。而 ``XREADGROUP ... BLOCK <ms>``
    的阻塞时长一旦超过这个默认读超时，超时就会被当作 **socket 超时**抛出：

        except asyncio.TimeoutError:
            if timeout is not None:
                return None          # 阻塞到期：正常返回（预期路径）
            raise TimeoutError(...)  # socket 超时：抛异常（实际走的是这一支）

    实测后果：Worker 每次都在**恰好 block 毫秒**之后抛
    ``redis.exceptions.TimeoutError: Timeout reading from redis:6379``，
    进程退出 → 被 restart 策略拉起 → 崩溃循环（实测重启 11 次）。
    而从报错上看不出与"阻塞时长"有任何关系，很容易被误判成 Redis 不稳定。

    取 ``block + 30s`` 的余量：留出的这 30 秒是给"Redis 慢、网络抖动、
    单核机器上事件循环被抢占"这些正常波动的，不是可有可无的余量。
    """
    block_seconds = max(1.0, settings.eval_read_block_ms / 1000.0)
    return aioredis.from_url(
        settings.redis_url,
        decode_responses=True,
        socket_timeout=block_seconds + 30.0,
        socket_connect_timeout=10.0,
    )


class EvalWorker:
    """Redis Streams 消费循环。

    刻意把"Redis 操作"集中在这里、把"任务逻辑"放在 :class:`EvalJobHandler`：
    Redis 客户端很难在单测里模拟得像真的，而任务逻辑才是容易写错的部分。
    """

    def __init__(self, settings: Settings, handler: EvalJobHandler, *, redis_client: Any = None,
                 consumer_name: str | None = None) -> None:
        self._settings = settings
        self._handler = handler
        self._redis = redis_client
        self._consumer = consumer_name or settings.eval_consumer_name
        self._stopping = False

    def request_stop(self) -> None:
        self._stopping = True

    # ------------------------------------------------------------------ 循环

    async def run_forever(self) -> None:
        redis = self._redis or _connect(self._settings)
        try:
            await self._ensure_group(redis)
            logger.info(kv(event="eval.worker.started", stream=self._settings.eval_stream_key,
                           group=self._settings.eval_consumer_group, consumer=self._consumer))
            while not self._stopping:
                # --- 为什么整段都要包住 -------------------------------------
                # 消费循环里的任何一次 Redis 往返都可能因为"Redis 重启、网络抖动、
                # 单核机器上事件循环被抢占"而失败。如果让异常冒出去，进程会退出，
                # 再被 restart 策略拉起 —— 表现为**崩溃循环**，而且每轮都要重新
                # 建组、重新认领，把一台小机器拖垮。
                #
                # 正确做法是：记一条告警、等一会儿、继续消费。
                # 消息本身不会丢：处理成功才 ACK，失败的留在 PEL 里等重投。
                # 也就是说"消费循环可以失败"，但"进程不该因此死掉"。
                #
                # 注意 asyncio.CancelledError 继承自 BaseException 而不是 Exception，
                # 因此不会被下面的 except 吞掉 —— 停机时的取消依然能正常生效。
                try:
                    await self._reclaim(redis)
                    response = await redis.xreadgroup(
                        self._settings.eval_consumer_group, self._consumer,
                        {self._settings.eval_stream_key: ">"},
                        count=self._settings.eval_batch_size,
                        block=self._settings.eval_read_block_ms)
                except (aioredis.RedisError, OSError) as exc:
                    logger.warning(kv(event="eval.worker.redis_unavailable",
                                      error=type(exc).__name__, detail=str(exc)[:200]))
                    # 退避 2 秒：Redis 真挂了时不要打成忙循环（那会让 1 核机器雪上加霜）
                    await asyncio.sleep(2)
                    continue

                for entry_id, fields in _iter_entries(response):
                    await self._process(redis, entry_id, fields, attempt=None)
        finally:
            if self._redis is None:
                await redis.aclose()
            logger.info(kv(event="eval.worker.stopped", consumer=self._consumer))

    async def run_once(self, *, drain_seconds: float = 3.0) -> int:
        """把当前积压处理完就退出。用于脚本化验证（不需要长期挂一个进程）。"""
        redis = self._redis or _connect(self._settings)
        processed = 0
        try:
            await self._ensure_group(redis)
            await self._reclaim(redis)
            empty_rounds = 0
            while empty_rounds < 2 and not self._stopping:
                response = await redis.xreadgroup(
                    self._settings.eval_consumer_group, self._consumer,
                    {self._settings.eval_stream_key: ">"},
                    count=self._settings.eval_batch_size,
                    block=int(max(0.2, drain_seconds) * 1000))
                entries = list(_iter_entries(response))
                if not entries:
                    empty_rounds += 1
                    continue
                empty_rounds = 0
                for entry_id, fields in entries:
                    await self._process(redis, entry_id, fields, attempt=None)
                    processed += 1
        finally:
            if self._redis is None:
                await redis.aclose()
        return processed

    # ------------------------------------------------------------------ 内部

    async def _ensure_group(self, redis: Any) -> None:
        """建消费组，起点用 ``0`` 而不是 ``$``。

        用 ``$`` 意味着"只消费建组之后的新消息"：如果任务先创建、Worker 后启动，
        那条消息就永远不会被消费，任务永远停在 PENDING。
        用 ``0`` 则在**建组那一刻**从头开始读，两种启动顺序都不会丢任务。
        组一旦存在，其 ``last-delivered-id`` 会自己持久化，不会每次重启都重放。
        """
        try:
            await redis.xgroup_create(self._settings.eval_stream_key,
                                      self._settings.eval_consumer_group, id="0", mkstream=True)
            logger.info(kv(event="eval.worker.group.created", group=self._settings.eval_consumer_group))
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def _reclaim(self, redis: Any) -> None:
        """认领"卡住"的消息：超过 ``eval_reclaim_idle_ms`` 仍未 ACK 的，换我来跑。

        这是 Worker 崩溃后任务能自愈的关键——没有这一步，崩溃前未 ACK 的消息
        会永远留在 PEL 里，任务也永远停在 RUNNING。
        """
        try:
            result = await redis.xautoclaim(
                self._settings.eval_stream_key, self._settings.eval_consumer_group, self._consumer,
                min_idle_time=self._settings.eval_reclaim_idle_ms, start_id="0-0",
                count=self._settings.eval_batch_size)
        except Exception as exc:
            logger.warning(kv(event="eval.worker.reclaim.failed", error=str(exc)))
            return

        # redis-py 不同版本返回 2 元组或 3 元组
        entries = result[1] if isinstance(result, (list, tuple)) and len(result) > 1 else []
        for entry_id, fields in entries or []:
            delivered = await self._delivery_count(redis, entry_id)
            if delivered > self._settings.eval_max_deliveries:
                await self._dead_letter(redis, entry_id, fields, delivered)
                continue
            logger.info(kv(event="eval.worker.reclaimed", entryId=entry_id, delivered=delivered))
            await self._process(redis, entry_id, fields, attempt=delivered)

    async def _delivery_count(self, redis: Any, entry_id: str) -> int:
        try:
            pending = await redis.xpending_range(self._settings.eval_stream_key,
                                                 self._settings.eval_consumer_group,
                                                 min=entry_id, max=entry_id, count=1)
        except Exception:
            return 1
        if not pending:
            return 1
        first = pending[0]
        if isinstance(first, Mapping):
            return int(first.get("times_delivered") or 1)
        return int(first[3]) if len(first) > 3 else 1

    async def _process(self, redis: Any, entry_id: str, fields: Mapping[str, Any],
                       *, attempt: int | None) -> None:
        message = EvalJobMessage.from_fields(fields)
        if message is None:
            logger.error(kv(event="eval.worker.bad_message", entryId=entry_id))
            await redis.xack(self._settings.eval_stream_key, self._settings.eval_consumer_group, entry_id)
            return

        try:
            await self._handler.handle(message, attempt=attempt)
        except ControlPlaneError as exc:
            if exc.retryable:
                # 不 ACK：留给 XAUTOCLAIM 稍后重投。这里不主动 sleep，避免拖慢整批。
                logger.warning(kv(event="eval.worker.retry", runId=message.run_id,
                                  entryId=entry_id, reason=str(exc)))
                return
            logger.error(kv(event="eval.worker.failed", runId=message.run_id, reason=str(exc)))
            await self._handler.mark_failed(message, str(exc), attempt=attempt)
            await redis.xack(self._settings.eval_stream_key, self._settings.eval_consumer_group, entry_id)
        except Exception as exc:
            # 未预期异常同样不 ACK：把"不确定"交给重试与死信兜底，
            # 而不是在这里就把任务判死（可能是控制面正在重启等瞬时问题）。
            logger.exception(kv(event="eval.worker.error", runId=message.run_id, entryId=entry_id,
                                error=type(exc).__name__))
            return
        else:
            # 故障点 after_result_before_ack：任务**已经跑完并提交**（用例结果与
            # COMPLETED 回调都发出去了），但在 XACK 之前进程消失。
            # 这是幂等性最尖锐的考验：消息仍在 PEL 中，重投后整批会**被再跑一遍**，
            # 控制面必须靠唯一索引 (run_id, case_id, guard_enabled) 吸收重复的行，
            # 且完成计数不能被累计两次。
            _fault_crash("after_result_before_ack", message.run_id)
            await redis.xack(self._settings.eval_stream_key, self._settings.eval_consumer_group, entry_id)

    async def _dead_letter(self, redis: Any, entry_id: str, fields: Mapping[str, Any],
                           delivered: int) -> None:
        reason = f"超过最大投递次数({self._settings.eval_max_deliveries})，已转入死信队列"
        try:
            payload = {str(key): str(value) for key, value in (fields or {}).items()}
            payload["reason"] = reason
            payload["delivered"] = str(delivered)
            await redis.xadd(self._settings.eval_dead_letter_key, payload)
        except Exception as exc:
            logger.error(kv(event="eval.worker.dlq.failed", entryId=entry_id, error=str(exc)))
        message = EvalJobMessage.from_fields(fields)
        if message is not None:
            await self._handler.mark_failed(message, reason, attempt=delivered)
        await redis.xack(self._settings.eval_stream_key, self._settings.eval_consumer_group, entry_id)
        logger.error(kv(event="eval.worker.dlq", runId=message.run_id if message else None,
                        entryId=entry_id, delivered=delivered))


def _iter_entries(response: Any):
    """把 ``XREADGROUP`` 的嵌套返回摊平成 ``(entry_id, fields)``。

    redis-py 返回 ``[[stream, [(id, {field: value}), ...]]]``；
    建组前后/不同版本可能给出 ``None`` 或空列表，这里统一兜住。
    """
    for _stream, entries in response or []:
        for entry in entries or []:
            if isinstance(entry, (list, tuple)) and len(entry) == 2:
                yield entry[0], entry[1]
            elif isinstance(entry, Mapping):
                yield entry.get("id"), entry


def _int_or(value: Any, fallback: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


# ---------------------------------------------------------------------- 独立进程入口


def build_worker(settings: Settings | None = None, *, redis_client: Any = None) -> EvalWorker:
    resolved = settings or get_settings()
    runtime = build_runtime(resolved)
    runner = EvalRunner(runtime.orchestrator)
    control_plane = ControlPlaneClient(resolved)
    handler = EvalJobHandler(runner=runner, control_plane=control_plane,
                             batch_size=resolved.eval_batch_size)
    return EvalWorker(resolved, handler, redis_client=redis_client)


async def _run_cli(settings: Settings, *, once: bool, drain_seconds: float) -> int:
    worker = build_worker(settings)
    if once:
        processed = await worker.run_once(drain_seconds=drain_seconds)
        logger.info(kv(event="eval.worker.once.done", processed=processed))
        return 0

    loop = asyncio.get_running_loop()
    for signal_name in ("SIGINT", "SIGTERM"):
        sig = getattr(signal, signal_name, None)
        if sig is None:
            continue
        try:
            loop.add_signal_handler(sig, worker.request_stop)
        except (NotImplementedError, RuntimeError):
            # Windows 上 ProactorEventLoop 不支持 add_signal_handler；
            # 此时靠 KeyboardInterrupt 退出即可，不影响功能。
            pass
    await worker.run_forever()
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="TrustAgent 评测 Worker（Redis Streams 消费者）")
    parser.add_argument("--once", action="store_true",
                        help="处理完当前积压就退出，用于脚本化验证")
    parser.add_argument("--drain-seconds", type=float, default=3.0,
                        help="--once 模式下每次阻塞读取的等待秒数")
    args = parser.parse_args(argv)

    settings = get_settings()
    configure_logging(settings.log_level)
    if not settings.internal_token:
        logger.warning("未配置 TRUSTAGENT_INTERNAL_TOKEN：回调控制面会一律 401（fail closed）")
    try:
        return asyncio.run(_run_cli(settings, once=args.once, drain_seconds=args.drain_seconds))
    except KeyboardInterrupt:  # pragma: no cover - 交互式中断
        logger.info(kv(event="eval.worker.interrupted"))
        return 130


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
