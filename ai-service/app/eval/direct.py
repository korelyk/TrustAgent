"""同步驱动一次评测（**不经过 Redis 队列**）。

### 为什么提供这样一个入口

它不是"绕过队列的捷径"，而是一个**二分定位工具**：

* 本地没有 Redis 时，仍然可以验证"取配置 → 跑用例 → 回传结果 → 控制面复算指标"这条链路是否成立；
* 评测出问题时，先跑一遍 ``--direct``：
  * 能跑通、指标正常 → 问题在**队列侧**（消费组、ACK、重投、死信）；
  * 跑不通 → 问题在**执行侧**（策略、工具、指标），与队列无关。

没有这个入口时，只能对着一个"停在 PENDING 的任务"猜是队列问题还是执行问题。

### 与 Worker 的关系

两者共用同一个 :class:`~app.eval.worker.EvalJobHandler`，
所以"跳过队列"只跳过了**消息投递**这一段，其余行为（轮次、分批、幂等回调、指标复算）完全一致。
唯一的额外输入是消息里的定位信息（runId / datasetId / appId / mode），由调用方从
``GET /api/eval/runs/{id}`` 读出来传进来。

用法：
    python -m app.eval.direct --run-id 7 --dataset-id 3 --app-id 1 --mode BOTH
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Sequence

from app.config import get_settings
from app.eval.java_client import ControlPlaneClient
from app.eval.runner import EvalRunner
from app.eval.worker import EvalJobHandler, EvalJobMessage
from app.observability.logging import configure_logging
from app.runtime import build_runtime


async def run_direct(*, run_id: int, dataset_id: int, app_id: int, mode: str,
                     trace_id: str | None, batch_size: int | None) -> dict:
    settings = get_settings()
    runtime = build_runtime(settings)
    control_plane = ControlPlaneClient(settings)
    try:
        handler = EvalJobHandler(
            runner=EvalRunner(runtime.orchestrator),
            control_plane=control_plane,
            batch_size=batch_size or settings.eval_batch_size,
        )
        return await handler.handle(EvalJobMessage(
            run_id=run_id, dataset_id=dataset_id, app_id=app_id,
            mode=mode.strip().upper(), attempt=1, trace_id=trace_id))
    finally:
        await control_plane.aclose()


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="同步驱动一次评测（不使用 Redis 队列）")
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--dataset-id", type=int, required=True)
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--mode", default="BOTH", help="GUARD_ON / GUARD_OFF / BOTH")
    parser.add_argument("--trace-id", default=None)
    parser.add_argument("--batch-size", type=int, default=None)
    args = parser.parse_args(argv)

    settings = get_settings()
    configure_logging(settings.log_level)

    try:
        result = asyncio.run(run_direct(
            run_id=args.run_id, dataset_id=args.dataset_id, app_id=args.app_id,
            mode=args.mode, trace_id=args.trace_id, batch_size=args.batch_size))
    except KeyboardInterrupt:  # pragma: no cover
        return 130
    except Exception as exc:
        print(json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, ensure_ascii=False))
        return 1

    print(json.dumps({"ok": True, "runId": result.get("runId"), "results": result.get("results"),
                      "runStatus": result.get("runStatus")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
