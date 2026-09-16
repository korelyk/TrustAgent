"""合成交通指标查询工具。

**数据是合成的**（由 ``sha256(road|date)`` 派生），不是真实交管数据，
项目里所有涉及"交通"的内容都只是为将来接入真实预测模型预留的接口形态。

关键设计：**注入时钟**。

``today_provider`` 默认是 ``date.today``，但测试里可以注入一个固定日期。
这样"昨天"这种相对时间在测试里也能得到确定结果，而演示环境又能跟随真实日期。
这是处理"不确定外部输入"（时间、随机数）的通用手法：把不确定性收敛到一个可替换的依赖上。
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

from app.tools.base import ToolContext, ToolResult


@dataclass(frozen=True)
class RoadProfile:
    name: str
    lanes: int
    free_flow_speed: float


ROADS: dict[str, RoadProfile] = {
    "二环路": RoadProfile("二环路", 6, 60.0),
    "三环路": RoadProfile("三环路", 8, 70.0),
    "四环路": RoadProfile("四环路", 8, 80.0),
    "滨海大道": RoadProfile("滨海大道", 6, 65.0),
    "迎宾路": RoadProfile("迎宾路", 4, 50.0),
    "海秀路": RoadProfile("海秀路", 4, 45.0),
}

_RELATIVE_DAYS = {"今天": 0, "昨天": 1, "前天": 2, "today": 0, "yesterday": 1}


def _unit_hash(seed: str) -> float:
    """把任意字符串映射到 [0, 1) 的确定性伪随机数。"""
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / float(1 << 64)


def _resolve_date(raw: str, today: date) -> date:
    if not raw:
        return today - timedelta(days=1)
    if raw in _RELATIVE_DAYS:
        return today - timedelta(days=_RELATIVE_DAYS[raw])
    try:
        return date.fromisoformat(raw)
    except ValueError:
        return today - timedelta(days=1)


class QueryTrafficMetricsTool:
    name = "query_traffic_metrics"
    description = "查询合成的交通指标（平均车速 / 流量 / 拥堵指数），为接入真实预测模型预留"
    side_effect = False
    param_schema: dict[str, Any] = {
        "type": "object",
        "properties": {
            "metric": {"type": "string", "enum": ["avg_speed", "volume", "congestion_index"]},
            "road": {"type": "string", "enum": sorted(ROADS.keys())},
            "date": {"type": "string", "maxLength": 16},
        },
        "required": ["metric", "road"],
        "additionalProperties": False,
    }

    def __init__(self, today_provider: Callable[[], date] = date.today) -> None:
        self._today_provider = today_provider

    def invoke(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        metric = str(arguments.get("metric", "")).strip()
        road = str(arguments.get("road", "")).strip()
        if metric not in {"avg_speed", "volume", "congestion_index"}:
            return ToolResult(ok=False, value="", error=f"不支持的 metric: {metric}")
        profile = ROADS.get(road)
        if profile is None:
            return ToolResult(ok=False, value="", error=f"未收录的道路: {road}")

        target_date = _resolve_date(str(arguments.get("date", "")).strip(), self._today_provider())
        value = self._compute(metric, profile, target_date)
        return ToolResult(ok=True, value=value, raw={"metric": metric, "road": road,
                                                    "date": target_date.isoformat()})

    def _compute(self, metric: str, profile: RoadProfile, target_date: date) -> str:
        day_seed = _unit_hash(f"{profile.name}|{target_date.isoformat()}")
        weekday = target_date.weekday()
        # 工作日高峰更拥堵，周末更顺畅（确定性规则，不引入真实数据）
        peak_factor = 0.72 if weekday < 5 else 0.92

        if metric == "avg_speed":
            speed = profile.free_flow_speed * peak_factor * (0.9 + 0.2 * day_seed)
            blocked_minutes = round(6 + 18 * (1 - peak_factor) * (0.7 + 0.6 * day_seed), 1)
            return (f"{profile.name} {target_date.isoformat()} 平均车速 {speed:.1f} km/h"
                    f"（自由流 {profile.free_flow_speed:.0f} km/h，平均延误 {blocked_minutes} 分钟）。"
                    f"数据来源: 合成数据（非真实交管数据）")
        if metric == "volume":
            volume = int(profile.lanes * 900 * (0.75 + 0.5 * day_seed))
            return (f"{profile.name} {target_date.isoformat()} 断面流量约 {volume} 辆/小时"
                    f"（{profile.lanes} 车道）。数据来源: 合成数据（非真实交管数据）")
        index = min(10.0, max(0.0, 10 * (1 - peak_factor) * (0.6 + 0.8 * day_seed)))
        return (f"{profile.name} {target_date.isoformat()} 拥堵指数 {index:.1f} / 10"
                f"（0 畅通，10 严重拥堵）。数据来源: 合成数据（非真实交管数据）")
