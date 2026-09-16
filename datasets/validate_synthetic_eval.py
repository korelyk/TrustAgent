#!/usr/bin/env python3
"""校验合成评测集是否符合导入端的字段约束。

### 为什么要有这个脚本

评测集是 JSONL 纯文本，一个多余/缺失的字段肉眼很难发现，而**导入端的校验是逐行的**：
不合法的一行会让整次导入被拒（或者被记进 `rejectedLines`）。
在"提交评测任务 → 等几分钟 → 发现数据集是空的"这条路上排查，成本远高于先本地校验一次。

这个脚本刻意**不重复实现 Java 的校验逻辑**，而是复刻它的**约束值**并从 Java 源码里读取
（见 ``JAVA_VOCABULARY``），避免出现"两边规则漂移、脚本说没问题但导入被拒"的情况。

用法::

    python datasets/validate_synthetic_eval.py datasets/synthetic_eval_v1.jsonl
    python datasets/validate_synthetic_eval.py datasets/*.jsonl

退出码：0 表示全部通过，1 表示有问题。
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

# 输出编码兜底：Windows 控制台默认是 GBK，终端里出现任何 GBK 编不出的字符
# （排版减号 U+2212、emoji、部分全角符号）都会让 print 抛 UnicodeEncodeError。
# 后果特别具有误导性：校验逻辑已经跑完并通过，脚本却以非零码结束，
# 看起来像"数据集有问题"。这里把 stdout 换成 UTF-8 并忽略无法编码的字符——
# 校验结果不受影响，只是个别符号可能显示成 "?"。
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (ValueError, OSError):  # pragma: no cover - 仅在异常终端下触发
        pass

# ---------------------------------------------------------------------------
# 约束值 —— 与 Java 侧保持一致
# ---------------------------------------------------------------------------
# 来源：backend/src/main/java/io/trustagent/eval/EvalVocabulary.java
CATEGORIES = {
    "NORMAL",
    "PROMPT_INJECTION_DIRECT",
    "PROMPT_INJECTION_INDIRECT",
    "ENCODED_INSTRUCTION",
    "SYSTEM_PROMPT_LEAK",
    "TOOL_ABUSE",
    "PII_EXPOSURE",
    "OUTPUT_UNSAFE",
}
ACTIONS = {"ALLOW", "BLOCK", "MASK", "CALL_TOOL"}

# 来源：backend/src/main/java/io/trustagent/app/ToolCatalog.java
# 这四个是内置的模拟工具。写错工具名会在评测时判成"工具不符合预期"，
# 而原因看起来像"模型调错了工具"，很难联想到是数据集写错了。
TOOLS = {"calculator", "search_local_docs", "query_traffic_metrics", "send_notification"}

MAX_CASE_ID = 64
MAX_TEXT = 8000          # 与 EvalDatasetService.MAX_TEXT_LENGTH 一致
MAX_TAGS = 20
MAX_TAG_LEN = 32
MAX_TAGS_JOINED = 512

REQUIRED = ("caseId", "category", "prompt", "expectedAction", "containsSensitiveData")


def validate_file(path: Path) -> int:
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()

    problems: list[str] = []
    # 提示（不是错误）：语义上值得看一眼，但确有合理场景，不应当阻断导入
    notes: list[str] = []
    seen_ids: dict[str, int] = {}
    categories: Counter[str] = Counter()
    actions: Counter[str] = Counter()
    rows = 0

    for lineno, line in enumerate(raw.decode("utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        rows += 1

        try:
            obj = json.loads(line)
        except json.JSONDecodeError as exc:
            problems.append(f"第 {lineno} 行不是合法 JSON: {exc}")
            continue

        if not isinstance(obj, dict):
            problems.append(f"第 {lineno} 行不是 JSON 对象")
            continue

        case_id = obj.get("caseId")

        for field in REQUIRED:
            if field not in obj:
                problems.append(f"第 {lineno} 行缺少必填字段 {field}")

        if not isinstance(case_id, str) or not case_id.strip():
            problems.append(f"第 {lineno} 行 caseId 缺失或为空")
        else:
            if len(case_id) > MAX_CASE_ID:
                problems.append(f"{case_id}: caseId 长度 {len(case_id)} > {MAX_CASE_ID}")
            if case_id in seen_ids:
                problems.append(
                    f"{case_id}: caseId 与第 {seen_ids[case_id]} 行重复"
                    "（导入端会按 (dataset_id, case_id) 建唯一索引，重复会被拒）"
                )
            seen_ids[case_id] = lineno

        category = obj.get("category")
        if category not in CATEGORIES:
            problems.append(f"{case_id}: 未知 category {category!r}")
        else:
            categories[category] += 1

        action = obj.get("expectedAction")
        if action not in ACTIONS:
            problems.append(f"{case_id}: 未知 expectedAction {action!r}")
        else:
            actions[action] += 1

        prompt = obj.get("prompt")
        if not isinstance(prompt, str) or not prompt.strip():
            problems.append(f"{case_id}: prompt 缺失或为空")
        elif len(prompt) > MAX_TEXT:
            problems.append(f"{case_id}: prompt 长度 {len(prompt)} > {MAX_TEXT}")

        context = obj.get("context")
        if context is not None:
            if not isinstance(context, str):
                problems.append(f"{case_id}: context 必须是字符串或省略")
            elif len(context) > MAX_TEXT:
                problems.append(f"{case_id}: context 长度 {len(context)} > {MAX_TEXT}")

        if not isinstance(obj.get("containsSensitiveData"), bool):
            problems.append(f"{case_id}: containsSensitiveData 必须是布尔值")

        tool = obj.get("expectedTool")
        if tool is not None and tool != "":
            if tool not in TOOLS:
                problems.append(f"{case_id}: 未知 expectedTool {tool!r}（必须是内置工具之一）")
        # 期望调用工具却没写工具名：这条用例无法判定"调用是否正确"
        if action == "CALL_TOOL" and not tool:
            problems.append(f"{case_id}: expectedAction=CALL_TOOL 但缺少 expectedTool")
        # 注意：**BLOCK + expectedTool 是合法且有意义的组合**，不要当成矛盾。
        # TOOL_ABUSE 类用例就是这么写的：expectedAction=BLOCK 表示"期望被拦下"，
        # expectedTool=send_notification 表示"针对的是哪个工具"，
        # 评测时用它判断"是不是恰好拦在了该拦的那个工具上"。
        # （第一版校验器曾把这一组合判为错误，结果在 v1 上报了 15 条假问题——
        #   那是校验规则错了，不是数据集错了。这里保留注释以免以后又改回去。）

        tags = obj.get("tags")
        if tags is not None:
            if not isinstance(tags, list):
                problems.append(f"{case_id}: tags 必须是数组")
            else:
                if len(tags) > MAX_TAGS:
                    problems.append(f"{case_id}: tags 数量 {len(tags)} > {MAX_TAGS}")
                for tag in tags:
                    if not isinstance(tag, str):
                        problems.append(f"{case_id}: tags 里含非字符串元素")
                    elif len(tag) > MAX_TAG_LEN:
                        problems.append(f"{case_id}: tag {tag!r} 长度 {len(tag)} > {MAX_TAG_LEN}")
                joined = ",".join(str(t) for t in tags)
                if len(joined) > MAX_TAGS_JOINED:
                    problems.append(
                        f"{case_id}: tags 拼接后长度 {len(joined)} > {MAX_TAGS_JOINED}"
                    )

        # 统计口径自检：这里复刻的是 Java/Python 两侧共用的分组规则。
        # 如果它和 metrics 的实现对不上，指标就会和数据集"看起来不一致"。
        # PII_EXPOSURE 的期望动作通常是 MASK：把它写成 BLOCK 会让一次**成功的脱敏**
        # 被算成"没拦住"，从而污染 ASR（见 docs/BENCHMARK.md 的口径说明）。
        # 这里只提示、不计入失败：确有场景希望把含敏感信息的输入直接拒掉，
        # 那是有意为之，不应该被工具挡住。
        if category == "PII_EXPOSURE" and action not in {"MASK", "BLOCK"}:
            notes.append(
                f"{case_id}: PII_EXPOSURE 的 expectedAction={action}，"
                "通常应为 MASK（或有意拒绝时用 BLOCK）"
            )

    # ------------------------------------------------------------------ 报告
    print(f"=== {path.name} ===")
    print(f"  条数      : {rows}")
    print(f"  SHA256    : {digest}")
    attack = sum(v for k, v in actions.items() if k == "BLOCK")
    benign = sum(v for k, v in actions.items() if k in {"ALLOW", "CALL_TOOL"})
    mask = actions.get("MASK", 0)
    print(f"  分组      : attackCases={attack} benignCases={benign} maskCases={mask}")
    # 注释：这里刻意用 ASCII 减号 "-" 而不是排版用的 U+2212 "−"。
    # Windows 控制台默认是 GBK，编码不出来的字符会让脚本在**最后一步**抛
    # UnicodeEncodeError（校验已经跑完了，却以退出码 1 结束，看起来像"校验失败"）。
    print(f"              混淆矩阵合计 = {attack + benign}（= 总数 {rows} - mask {mask}）")
    print("  category 分布:")
    for name, count in categories.most_common():
        print(f"    {name:<28} {count}")
    print("  expectedAction 分布:")
    for name, count in actions.most_common():
        print(f"    {name:<28} {count}")

    if notes:
        print(f"\n  {len(notes)} 条提示（不阻断导入，但建议确认是否有意为之）:")
        for item in notes[:20]:
            print(f"    · {item}")

    if problems:
        print(f"\n  发现 {len(problems)} 个问题:")
        for item in problems[:50]:
            print(f"    - {item}")
        if len(problems) > 50:
            print(f"    ...（还有 {len(problems) - 50} 个未显示）")
        return 1

    print("  校验通过")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    exit_code = 0
    for arg in argv[1:]:
        path = Path(arg)
        if not path.is_file():
            print(f"跳过（不是文件）: {arg}")
            exit_code = 1
            continue
        exit_code |= validate_file(path)
        print()
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
