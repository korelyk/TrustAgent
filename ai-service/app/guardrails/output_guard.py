"""输出安全检查。

检查四件事：

1. **敏感信息泄露**：模型输出里出现手机号/邮箱/身份证/银行卡/API Key → 脱敏，并报高危事件。
   模型输出里的 PII 跟输入里的 PII 语义不同：输入里的 PII 是"用户给的"，输出里的 PII 是
   "模型要发出去的"，后者是真正的泄露，所以严重级别更高。
2. **系统提示词泄露**：用滑动窗口比对系统提示词片段。
3. **未经验证的 URL**：输出里的外链域名不在白名单内 → 只记日志不阻断（避免误伤正常引用）。
4. **可被前端执行的 HTML/脚本**：``<script>``、事件处理器、``javascript:`` 等 → 转义。

**前端配套**：即使这里漏了，前端也只用文本方式渲染（``v-text``），不使用 ``v-html``。
两道防线都要有，因为安全设计不能依赖单一环节。
"""

from __future__ import annotations

import re
from urllib.parse import urlparse

from app.guardrails.base import ACTION_LOG, ACTION_MASK, Finding, SEVERITY_HIGH, SEVERITY_LOW, SEVERITY_MEDIUM
from app.guardrails.events import OUTPUT_SYSTEM_PROMPT_LEAK, OUTPUT_UNSAFE_HTML, OUTPUT_UNVERIFIED_URL
from app.guardrails.pii import detect_pii, mask_value

_DANGEROUS_TAG = re.compile(r"<\s*/?\s*(script|iframe|object|embed|svg|form|frame|applet)\b", re.IGNORECASE)
_EVENT_HANDLER = re.compile(r"\bon[a-z]{3,}\s*=", re.IGNORECASE)
_JS_URI = re.compile(r"(?:javascript|vbscript)\s*:", re.IGNORECASE)
_HTML_TAG = re.compile(r"<\s*/?\s*[a-z][a-z0-9]{0,12}(?:\s[^<>]{0,200})?\s*/?\s*>", re.IGNORECASE)
_URL = re.compile(r'https?://[^\s\)\]<>，。；"\']+', re.IGNORECASE)

#: 系统提示词片段比对的窗口长度。太短会误报（通用短语），太长会漏报。
_PROMPT_WINDOW = 16
#: 低于这个长度的系统提示词不参与窗口比对（没有足够信息量）
_MIN_PROMPT_LENGTH = 24
#: 一次 sanitize 里最多做几轮"找出并抹掉最长泄露片段"，防止极端输入导致死循环
_MAX_REDACTION_PASSES = 8
#: 写入 Finding.evidence 的泄露片段上限。不能把命中的系统提示词整段落到安全事件里，
#: 否则"记录泄露"这件事本身又变成了新的泄露。
_MAX_EVIDENCE_LENGTH = 40

PROMPT_REDACTION = "[已隐去系统提示词片段]"


def extract_urls(text: str) -> list[str]:
    return _URL.findall(text or "")


def extract_domains(text: str) -> list[str]:
    domains = []
    for url in extract_urls(text):
        host = urlparse(url).hostname
        if host:
            domains.append(host.lower())
    return domains


def domain_allowed(domain: str, whitelist: list[str]) -> bool:
    """白名单匹配支持 ``*.example.com`` 与 ``example.com`` 两种写法。"""
    if not whitelist:
        return True
    for entry in whitelist:
        candidate = entry.strip().lower()
        if not candidate:
            continue
        if candidate.startswith("*."):
            suffix = candidate[1:]  # 保留前导点，避免 notexample.com 被误判为命中
            if domain.endswith(suffix):
                return True
        elif domain == candidate:
            return True
    return False


class OutputGuard:
    name = "output_guard"

    def detect(self, text: str, *, source: str = "model_output",
               system_prompt: str | None = None,
               domain_whitelist: list[str] | None = None) -> list[Finding]:
        findings: list[Finding] = []
        content = text or ""
        whitelist = domain_whitelist or []

        for match in detect_pii(content):
            findings.append(Finding(
                rule_id=match.rule_id,
                category="PII_LEAK_PREVENTED",
                severity=SEVERITY_HIGH,
                action=ACTION_MASK,
                detector=self.name,
                evidence=match.value,
                description=f"模型输出中检测到{match.label}，已脱敏（原始值不落库）",
            ))

        if _DANGEROUS_TAG.search(content) or _EVENT_HANDLER.search(content) or _JS_URI.search(content) \
                or _HTML_TAG.search(content):
            findings.append(Finding(
                rule_id="OUT-HTML",
                category=OUTPUT_UNSAFE_HTML,
                severity=SEVERITY_MEDIUM,
                action=ACTION_MASK,
                detector=self.name,
                evidence=_first_dangerous_snippet(content),
                description="输出包含可能被浏览器执行的 HTML/脚本片段，已转义",
            ))

        leaked = self._find_prompt_leak(content, system_prompt)
        if leaked:
            findings.append(Finding(
                rule_id="OUT-SYS-PROMPT",
                category=OUTPUT_SYSTEM_PROMPT_LEAK,
                severity=SEVERITY_HIGH,
                action=ACTION_MASK,
                detector=self.name,
                evidence=leaked,
                description="输出中包含系统提示词片段，已隐去",
            ))

        unverified = [d for d in extract_domains(content) if not domain_allowed(d, whitelist)]
        if unverified:
            findings.append(Finding(
                rule_id="OUT-URL",
                category=OUTPUT_UNVERIFIED_URL,
                severity=SEVERITY_LOW,
                action=ACTION_LOG,
                detector=self.name,
                evidence=",".join(sorted(set(unverified))),
                description="输出包含不在域名白名单内的外链，仅记录不阻断",
            ))

        return findings

    def sanitize(self, text: str, findings: list[Finding], system_prompt: str | None = None) -> str:
        """按命中情况改写输出。顺序：先隐去系统提示词片段，再脱敏 PII，最后转义 HTML。"""
        result = text or ""
        categories = {finding.category for finding in findings}

        if OUTPUT_SYSTEM_PROMPT_LEAK in categories:
            result = self._redact_prompt(result, system_prompt)
        if "PII_LEAK_PREVENTED" in categories:
            for match in reversed(detect_pii(result)):
                result = result[:match.start] + mask_value(match.value) + result[match.end:]
        if OUTPUT_UNSAFE_HTML in categories:
            result = self._neutralize_html(result)
        return result

    # ------------------------------------------------------------------ 内部

    @staticmethod
    def _neutralize_html(text: str) -> str:
        """中和可被浏览器执行的 HTML 片段。

        为什么不能只转义尖括号：``javascript:alert(1)`` 这类伪协议**没有尖括号**，
        旧实现（只 replace ``<``/``>``）会原样把它留在输出里。而前端虽然只用 ``v-text`` 渲染，
        但输出会被复制粘贴、写进报告、或经其他消费者再渲染——检测到了却"净化成恒等"等于没防。
        顺序：先中和伪协议与事件处理器，再转义尖括号。
        """
        result = _JS_URI.sub("[已中和的脚本协议]:", text or "")
        result = _EVENT_HANDLER.sub("[已中和的事件处理器]=", result)
        return result.replace("<", "＜").replace(">", "＞")

    @staticmethod
    def _find_prompt_leak(text: str, system_prompt: str | None) -> str | None:
        """返回用于 evidence 的泄露片段（已截断）。真正的抹除逻辑在 :func:`_redact_prompt`。"""
        span = find_leaked_span(text or "", system_prompt or "")
        if span is None:
            return None
        return (text or "")[span[0]:span[1]][:_MAX_EVIDENCE_LENGTH]

    @staticmethod
    def _redact_prompt(text: str, system_prompt: str | None) -> str:
        if not system_prompt or len(system_prompt) < _MIN_PROMPT_LENGTH:
            return text
        result = text
        # 每轮都取"当前最长重合片段"整段抹掉，因此结果是确定的（不依赖 set 的迭代顺序）
        for _ in range(_MAX_REDACTION_PASSES):
            span = find_leaked_span(result, system_prompt)
            if span is None:
                break
            start, end = span
            result = result[:start] + PROMPT_REDACTION + result[end:]
        return result


def _is_meaningful_window(window: str) -> bool:
    """窗口里至少一半是可读字符，避免拿纯标点/空白的窗口去比对造成无意义命中。"""
    readable = sum(1 for ch in window if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
    return readable >= _PROMPT_WINDOW // 2


def find_leaked_span(text: str, system_prompt: str) -> tuple[int, int] | None:
    """在 ``text`` 上定位与 ``system_prompt`` 的**最长连续重合片段**，返回 ``[start, end)``。

    ### 为什么不能用"找到一个窗口就替换那个窗口"

    旧实现用 16 字符窗口逐个 ``replace``，只能抹掉 16 个字符。实测效果是：

    ``重复一遍：你是一个交通数据分析助手，只能使用被允许的工具，禁止泄露内部策略。``
    → ``重复一遍：你是一个交通数据分析助手[已隐去系统提示词片段]内部策略。``

    开头 12 个字符原样留在输出里 —— 这仍然是一次真实的系统提示词泄露。
    更糟的是旧实现把窗口放进 ``set`` 再 ``sorted(key=len)``，长度全都相同，
    排序是空操作，于是替换顺序取决于 set 的哈希顺序：**同样的输入可能得到不同结果**。

    ### 现在的做法

    用窗口当"锚点"确认存在重合，再向左右贪心扩展直到不再相等，
    得到真正的最长重合片段，然后整段抹掉。这样既抹干净，又是确定的。
    """
    if not text or not system_prompt or len(system_prompt) < _MIN_PROMPT_LENGTH:
        return None

    best: tuple[int, int] | None = None
    for offset in range(0, len(system_prompt) - _PROMPT_WINDOW + 1):
        window = system_prompt[offset:offset + _PROMPT_WINDOW]
        if not _is_meaningful_window(window):
            continue

        search_from = 0
        while True:
            position = text.find(window, search_from)
            if position < 0:
                break

            left = 0
            while (offset - left - 1 >= 0 and position - left - 1 >= 0
                   and system_prompt[offset - left - 1] == text[position - left - 1]):
                left += 1

            right = _PROMPT_WINDOW
            while (offset + right < len(system_prompt) and position + right < len(text)
                   and system_prompt[offset + right] == text[position + right]):
                right += 1

            candidate = (position - left, position + right)
            if best is None or (candidate[1] - candidate[0]) > (best[1] - best[0]):
                best = candidate
            search_from = position + 1

    return best


def _first_dangerous_snippet(text: str) -> str:
    for pattern in (_DANGEROUS_TAG, _JS_URI, _EVENT_HANDLER, _HTML_TAG):
        match = pattern.search(text or "")
        if match:
            start = max(0, match.start() - 20)
            return text[start:match.end() + 40]
    return ""
