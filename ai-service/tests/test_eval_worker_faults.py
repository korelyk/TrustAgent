"""故障注入点的行为测试。

这些测试保护的不是"崩溃恢复能不能工作"（那要跑真实的 Worker 进程，见
``scripts/server/verify-stack.sh`` 的 Worker 恢复组），而是**故障注入本身是安全的**：

* 默认关闭——环境变量没设时，故障点必须是空操作。
  如果这一条不成立，生产环境的 Worker 会莫名其妙地崩溃，而且原因极难定位。
* 只命中指定任务——runId 不匹配就不触发。
  如果这一条不成立，恢复测试会因为"每次重启都在同一处崩溃"而永远跑不完。
* 崩溃点用 ``os._exit``——异常会被上层吞掉，那是另一条路径。

用 monkeypatch 拦截 ``os._exit`` / 直接调用故障函数，
而不是真的让测试进程退出（那会把 pytest 一起带走）。
"""

from __future__ import annotations

import os

import pytest

from app.eval.java_client import ControlPlaneError
from app.eval.worker import (
    _FAULT_ENV,
    _fault_crash,
    _fault_matches,
    _fault_retryable,
    EvalJobHandler,
    EvalJobMessage,
)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """每个用例都从"未设置"开始，避免用例之间通过环境变量串味。"""
    monkeypatch.delenv(_FAULT_ENV, raising=False)


def _message(run_id: int = 4242) -> EvalJobMessage:
    message = EvalJobMessage.from_fields({
        "runId": str(run_id), "datasetId": "1", "appId": "1",
        "mode": "GUARD_ON", "attempt": "1", "traceId": "trace-fault-test",
    })
    assert message is not None
    return message


# --------------------------------------------------------------------------- 默认关闭

def test_no_fault_when_env_absent() -> None:
    assert _fault_matches("before_result", 4242) is False
    # 不抛异常、不退出，就是"什么都没发生"
    _fault_crash("before_result", 4242)
    _fault_retryable("always_fail", 4242)


def test_handle_is_not_disturbed_when_env_absent(monkeypatch: pytest.MonkeyPatch) -> None:
    """未配置故障时，handle() 不该被故障点影响——它会继续往下走到真实逻辑。

    用一个"一被调用就炸"的假控制面来证明：如果故障点提前短路了，
    这里就看不到 ControlPlaneError（数据集拉取会先失败）。
    """
    class ExplodingControlPlane:
        async def dataset_cases(self, dataset_id: int) -> dict:
            raise RuntimeError("假控制面被调用（说明故障点没有提前短路，符合预期）")

        async def exec_config(self, run_id: int, round_key: str) -> dict:
            raise RuntimeError("不应到达")

        async def callback(self, payload: dict, *, trace_id: str | None = None) -> dict:
            raise RuntimeError("不应到达")

    handler = EvalJobHandler(runner=None, control_plane=ExplodingControlPlane())  # type: ignore[arg-type]
    with pytest.raises(RuntimeError, match="假控制面被调用"):
        import asyncio
        asyncio.run(handler.handle(_message()))


# --------------------------------------------------------------------------- 只命中指定任务

def test_fault_matches_exact_point_and_run_id(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "before_result:4242")
    assert _fault_matches("before_result", 4242) is True
    # 故障点不同 -> 不触发
    assert _fault_matches("always_fail", 4242) is False
    # runId 不同 -> 不触发（这是"不会每次重启都崩溃"的关键）
    assert _fault_matches("before_result", 9999) is False


def test_blank_target_matches_any_run_id(monkeypatch: pytest.MonkeyPatch) -> None:
    """只写故障点、不写 runId 时对任意任务生效（留作人工调试用）。"""
    monkeypatch.setenv(_FAULT_ENV, "before_result")
    assert _fault_matches("before_result", 1) is True
    assert _fault_matches("before_result", 2) is True
    assert _fault_matches("always_fail", 1) is False


def test_unknown_point_never_fires(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "no_such_point:4242")
    assert _fault_matches("before_result", 4242) is False


# --------------------------------------------------------------------------- 崩溃点行为

def test_crash_point_exits_hard_for_matching_run(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "before_result:4242")
    observed: list[int] = []

    def fake_exit(code: int) -> None:
        observed.append(code)
        # 不真的退出：os._exit 一旦执行，pytest 进程就没了
        raise SystemExit(code)

    monkeypatch.setattr(os, "_exit", fake_exit)

    with pytest.raises(SystemExit) as excinfo:
        _fault_crash("before_result", 4242)
    assert excinfo.value.code == 97
    assert observed == [97]


def test_crash_point_is_noop_for_other_run(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "before_result:4242")

    def must_not_be_called(code: int) -> None:  # pragma: no cover
        raise AssertionError(f"不应退出，但收到了 os._exit({code})")

    monkeypatch.setattr(os, "_exit", must_not_be_called)
    _fault_crash("before_result", 7777)   # runId 不匹配 -> 空操作


# --------------------------------------------------------------------------- 可重试故障

def test_retryable_fault_raises_retryable_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "always_fail:4242")

    with pytest.raises(ControlPlaneError) as excinfo:
        _fault_retryable("always_fail", 4242)
    # 必须是 retryable，否则 Worker 会直接标失败而不是重投，就测不到死信路径
    assert excinfo.value.retryable is True


def test_retryable_fault_is_noop_for_other_run(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(_FAULT_ENV, "always_fail:4242")
    _fault_retryable("always_fail", 1234)   # runId 不匹配 -> 不应抛异常
