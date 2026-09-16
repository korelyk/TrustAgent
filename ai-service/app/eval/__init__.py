"""评测执行面：指标计算、用例运行、Redis Streams 消费。

与 Java 控制面的分工：
  * 控制面决定"跑什么"（数据集、模式、策略快照），并把任务投进 ``eval.jobs``；
  * 执行面决定"怎么跑"（逐条调用编排器），把逐条结果与**自己算的**指标回调给控制面；
  * 控制面**独立重算**一遍指标并比对，不一致就记 ``METRICS_MISMATCH`` 安全事件。

因此 ``metrics.py`` 与 ``backend/.../EvalMetricsCalculator.java`` 的公式必须逐字一致。
"""

from app.eval.metrics import CaseOutcome, Metrics, compute, pass_of
from app.eval.runner import CaseRunResult, EvalRunner

__all__ = ["CaseOutcome", "Metrics", "compute", "pass_of", "CaseRunResult", "EvalRunner"]
