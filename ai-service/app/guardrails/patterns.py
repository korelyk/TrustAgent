"""提示注入规则库（可解释的第一版实现）。

每条规则包含：规则 ID、命中后的类目、严重级别、处置动作、正则、说明。
规则是**数据**而不是散落在 if/else 里的字符串，因此可以直接：
* 被单测逐条覆盖（见 tests/test_injection.py 的 parametrize 表）；
* 被报告导出（``docs/BENCHMARK.md`` 按 rule_id 统计命中分布）；
* 在阶段4 换成"规则 + 模型"并联（模型输出置信度，规则提供可解释锚点）。

正则统一使用 ``IGNORECASE | DOTALL``：DOTALL 让 ``.{0,N}`` 能跨行匹配，
因为注入指令经常被拆成多行（例如文档里的"重要提示：\n忽略以上规则"）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.guardrails.base import ACTION_BLOCK, SEVERITY_HIGH, SEVERITY_MEDIUM
from app.guardrails.events import (
    ENCODED_INSTRUCTION,
    PROMPT_INJECTION_DIRECT,
    SYSTEM_PROMPT_LEAK_ATTEMPT,
)

#: 命中片段保留的最大长度（落库前还会再脱敏）
EVIDENCE_MAX_LENGTH = 160


@dataclass(frozen=True)
class InjectionPattern:
    rule_id: str
    category: str
    severity: str
    action: str
    regex: re.Pattern[str]
    description: str
    #: True 表示只在"间接来源"（工具返回值、检索到的文档）上启用
    tool_output_only: bool = False


def _pattern(rule_id: str, category: str, severity: str, regex: str, description: str,
             action: str = ACTION_BLOCK, tool_output_only: bool = False) -> InjectionPattern:
    return InjectionPattern(
        rule_id=rule_id,
        category=category,
        severity=severity,
        action=action,
        regex=re.compile(regex, re.IGNORECASE | re.DOTALL),
        description=description,
        tool_output_only=tool_output_only,
    )


INJECTION_PATTERNS: tuple[InjectionPattern, ...] = (
    _pattern(
        "INJ-IGNORE-RULES", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        # 中文限定词允许叠加（如"忽略之前的所有指令"）。
        # 演进：单个可选词 → {0,3} → 无上界。前两版都被"堆修饰语"绕过（{0,3} 实测堆 4 个词即穿透）。有限上界只是把绕过成本从 2 个词提到 N 个词。
        r"忽略\s*(?:[了掉]\s*)?"
        r"(?:(?:之前|以前|以上|上面|前面|上述|所有|一切|全部|这些|那些|既有|原有|系统|初始)\s*(?:的)?\s*)*"
        # 嵌套量词在这里不会 ReDoS：内层是字符类与可选组的交替，无分支竞争，匹配线性。
        r"(?:指令|指示|提示|规则|设定|要求|限制|约束)"
        r"|不要(?:再)?(?:遵守|理会|管)(?:之前|以前|以上|上面)(?:的)?(?:指令|规则|要求)"
        # 英文侧同样要容忍冠词 "the" 与更多同义动词（ignore/disregard/forget）
        r"|ignore\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above|earlier|preceding|system)\s+"
        r"(?:instructions?|prompts?|rules?|directions?|commands?)"
        r"|disregard\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above|earlier|preceding)\s+"
        r"(?:instructions?|rules?|prompts?|directions?)"
        r"|forget\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above|earlier)\s+"
        r"(?:instructions?|rules?|prompts?)",
        "要求忽略既有系统规则/指令（最典型的直接提示注入）",
    ),
    _pattern(
        "INJ-REVEAL-SYSTEM", SYSTEM_PROMPT_LEAK_ATTEMPT, SEVERITY_MEDIUM,
        r"(?:重复|复述|输出|打印|告诉我|展示|泄露|贴出)\s*(?:一下)?\s*(?:你的|你收到的|你被给予的|最初的)?\s*"
        r"(?:系统提示词|系统提示|系统指令|初始指令|隐藏指令|预设指令|system\s*prompt)"
        r"|(?:reveal|repeat|print|show|dump|output)\s+(?:me\s+)?(?:your\s+|the\s+)?"
        r"(?:system\s*prompt|initial\s+instructions?|hidden\s+instructions?|original\s+instructions?)"
        r"|你的(?:系统)?提示词(?:是|是什么|写的是什么)"
        # 中文里"把宾语提前"更常见：把系统提示词打印出来
        r"|(?:把|将)\s*[\s\S]{0,8}(?:系统提示词|系统提示|系统指令|初始指令|隐藏指令|预设指令)"
        r"\s*[\s\S]{0,8}(?:打印|输出|复述|贴出|读出来|发出来|说一遍|念一遍)",
        "诱导泄露系统提示词",
    ),
    _pattern(
        "INJ-ROLE-OVERRIDE", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"你(?:现在)?(?:是|变成|扮演|充当|作为)\s*(?:一个)?\s*"
        r"(?:不受任何限制|不受限制|无限制|无约束|超级|最高权限|管理员|root)"
        r"|(?:从现在开始|现在起|接下来)\s*你(?:就)?(?:是|要扮演)\s*[\s\S]{0,12}(?:管理员|root|无限制|不受限制)"
        r"|\byou\s+are\s+now\s+(?:an?\s+)?(?:unrestricted|unfiltered|unlimited|admin|root|dan)\b"
        r"|\bact\s+as\s+(?:an?\s+)?(?:administrator|root|unrestricted|unfiltered)\b",
        "角色覆盖：把助手改造成更高权限的角色",
    ),
    _pattern(
        "INJ-DEV-MODE", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"(?:开发者|调试|无限制|越狱|上帝|超级管理员)\s*模式"
        r"|\b(?:developer|debug|god|jailbreak|unrestricted|unfiltered)\s*mode\b"
        r"|\bDAN\b",
        "开启所谓开发者模式/越狱模式来绕过限制",
    ),
    _pattern(
        "INJ-BYPASS-GUARD", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        # `防护(?!关闭|开启|开关)`：本项目把"防护关闭/防护开启"用作对照评测的**功能名**
        # （见 security_policy 的 guardEnabled 字段说明），而描述该功能的文档会写成
        # "关闭后所有检查跳过，仅用于『防护关闭』对照评测" —— 这是一句正常的字段说明，
        # 却因为"跳过……防护"相邻被判成绕过尝试（实测命中 03_security_policy.md）。
        # 加上否定前瞻后，"关闭防护""跳过防护"这类真正的绕过表述仍然会被命中。
        #
        # 已知残余局限：同一条规则在**工具输出**上使用时，对"描述安全机制"的技术文档
        # 天然有误报倾向（文档里出现 绕过/关闭 + 安全类名词 是很自然的）。
        # 彻底的解法是让工具输出走独立的、更严格的规则集，属于后续工作，见
        # docs/SECURITY_LIMITATIONS.md。
        r"(?:绕过|跳过|关闭|禁用|无视|解除)\s*[\s\S]{0,6}"
        r"(?:安全|权限|策略|检测|审核|过滤|风控|限制|防护(?!关闭|开启|开关))"
        r"|\bbypass\s+(?:the\s+)?(?:security|safety|polic(?:y|ies)|guard|filter|restrictions?|checks?|limits?)\b"
        r"|\bdisable\s+(?:the\s+)?(?:security|safety|guard|filter)\b",
        "试图绕过/关闭安全策略或审核",
    ),
    _pattern(
        "INJ-FORCE-TOOL", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"(?:调用|执行|使用|触发|帮我)\s*[\s\S]{0,6}[`'\"]?\s*"
        r"(?:send_notification|发送通知|群发通知|群发邮件|发邮件给|发送邮件给)"
        r"|\bcall\s+[\s\S]{0,4}[`'\"]?send_notification\b"
        r"|\bsend\s+(?:an?\s+)?(?:email|notification)\s+to\s+(?:all|everyone|every)",
        "诱导调用有副作用的通知工具",
    ),
    _pattern(
        "INJ-EXFIL", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"(?:把|将|请)\s*[\s\S]{0,12}(?:数据库|密钥|凭据|密码|环境变量|配置文件|token|api\s*key)"
        r"[\s\S]{0,12}(?:发送|导出|打印|输出|泄露|给我|外发|读出来)"
        r"|\b(?:exfiltrate|leak|dump|print|send|export)\s+[\s\S]{0,24}"
        r"(?:credentials?|secrets?|api\s*keys?|env(?:ironment)?\s*variables?|database|passwords?)\b",
        "诱导导出/泄露凭据或内部数据",
    ),
    _pattern(
        "INJ-ENCODED-MARKER", ENCODED_INSTRUCTION, SEVERITY_HIGH,
        r"(?:base64|rot13|rot-13|十六进制|hex|unicode|url)\s*(?:编码|加密)?\s*(?:后的|过的)?\s*"
        r"(?:指令|命令|内容|文本|payload|文字)?\s*[\s\S]{0,12}(?:执行|运行|解码|调用|遵守|照做)"
        r"|\b(?:decode|base64[- ]?decode|rot13)\b[\s\S]{0,24}\b(?:and\s+)?(?:execute|run|follow|obey|do\s+it)\b"
        r"|(?:解码|还原)(?:后|之后)?[\s\S]{0,8}(?:执行|运行|照做|遵守)",
        "编码/混淆指令，要求解码后执行",
    ),
    _pattern(
        "INJ-NEW-INSTRUCTION", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"(?:以下是|下面是|这是)\s*(?:新的|更新后的|最新的|真正的)\s*(?:系统)?\s*(?:指令|指示|规则|提示词)\s*[:：]"
        r"|\bnew\s+(?:system\s+)?(?:instructions?|rules?)\s*:"
        r"|\bupdated\s+(?:system\s+)?(?:instructions?|rules?)\s*:",
        "伪造新指令，试图覆盖原始系统指令",
    ),
    _pattern(
        "INJ-DOC-INSTRUCTION", PROMPT_INJECTION_DIRECT, SEVERITY_HIGH,
        r"\[(?:system|instruction|admin|assistant)\]\s*[\s\S]{0,80}"
        r"|(?:注意|重要|提示|通知)\s*[:：]\s*[\s\S]{0,40}(?:忽略|执行|调用|发送|泄露|输出|读取)"
        # 第三个分支要求"称呼 + 明确的祈使词 + 动作"三者**同时**出现。
        #
        # 原写法是
        #   (?:AI|助手|模型|智能体|agent|assistant)\s*[\s\S]{0,24}
        #   (?:请|必须|应当|立刻|立即)?\s*[\s\S]{0,8}(?:忽略|执行|调用|发送|泄露|输出)
        # 有两个真实缺陷，由 scripts/analyze_detection_delta.py 实测发现：
        #
        #   1) ASCII 词没有词边界，而整条正则开着 IGNORECASE ——
        #      于是 `domainWhitelist` 里的子串 "ai" 被当成"在称呼 AI"，
        #      一份描述策略字段的表格因此被判为注入文档。
        #   2) 祈使词是可选的，所以"仅仅同时出现『Agent』与『调用』"就算命中 ——
        #      而任何介绍 Agent 系统的技术文档都满足这个条件。
        #
        # 实测后果：本项目自己的 4 篇演示文档里有 3 篇被自己的检测器判为提示注入，
        # 这正是 BENCHMARK 里 4 条 NORMAL 误报的主要来源 ——
        # 原先把它全部归因于"投毒文档"是不准确的。
        #
        # 现在：ASCII 词加 \b，祈使词改为必需，两段间隔收窄。
        r"|(?:助手|模型|智能体|\bAI\b|\bagent\b|\bassistant\b)\s*[\s\S]{0,12}"
        r"(?:请|必须|务必|应当|应该|需要|立刻|立即|马上)\s*[\s\S]{0,10}"
        r"(?:忽略|执行|调用|发送|泄露|输出|读取|删除)",
        "把指令伪装成文档正文的一部分（间接提示注入的典型形态）",
        tool_output_only=True,
    ),
)
