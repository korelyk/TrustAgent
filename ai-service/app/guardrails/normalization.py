"""文本规范化：把"伪装过的指令"还原成可检测的形式。

攻击者常用手段与对策：

===========================  ==================================================
攻击手段                     规范化处理
===========================  ==================================================
零宽字符插入（如 ``忽\\u200b略``）  删除零宽/双向控制字符
全角字符（如 ``ｉｇｎｏｒｅ``）       NFKC 归一化（全角→半角、兼容字符→标准字符）
同形异义字符（Cyrillic ``а``）     保守处理：不做映射，仅记录（见下）
Base64 编码指令               尝试解码，产出"额外视图"
ROT13 编码指令                产出 ROT13 视图
十六进制编码指令              尝试解码，产出"额外视图"
===========================  ==================================================

设计要点：**不改写原文，只产出多个"视图"**。检测器在原始文本与所有视图上分别匹配，
再按规则去重。这样做的好处是：
1. 误报可控——不会因为"规范化后文本变了"而把正常内容判成攻击；
2. 可解释——每个命中都能说清"是在哪个视图里命中的"，前端的证据片段也有意义；
3. 便于扩展——将来加 Unicode 同形字映射，只需新增一个视图。

同形异义（homoglyph）攻击在第一版**不做映射**，理由是：把 Cyrillic ``а`` 映射成 Latin ``a``
会同时改写正常的多语言文本，容易造成误报；正确做法是引入 Unicode confusable 表并配套白名单，
这属于阶段4的工作，已在 docs/SECURITY_LIMITATIONS.md 中记录为已知缺口。
"""

from __future__ import annotations

import base64
import binascii
import codecs
import re
import unicodedata
from dataclasses import dataclass, field

#: 不可见/格式类字符：正常文本里几乎不会出现，出现即视为混淆意图。
#:
#: 早期实现是手工枚举一批字符（\u200b\u202a\u2060 ...），但**枚举清单天然滞后**：
#: 实测 U+2066/U+2067/U+2068/U+2069（LRI/RLI/FSI/PDI）、U+180E、U+3164 都不在清单里，
#: 于是 ``忽略\u2066之前的指令`` 这类注入可以完整绕过规则（对照组 ``\u200b`` 能命中）。
#: 现在改为按 Unicode 类别过滤，覆盖整类而不是逐个补漏：
#:
#:   * ``Cf`` 格式字符（含全部 bidi 控制、零宽字符、word joiner、软连字符）
#:   * ``Mn``/``Me`` 非间距/包围标记（组合用不可见标记）
#:   * ``Cc`` 控制字符（除 \t\n\r，它们有正常语义）
#:   * ``Cs`` 代理项、``Co`` 私用区（不应出现在正常文本里）
#:
#: 注意：**不**过滤 ``Zs``（各类空格）—— 它们会改变 token 间距，用 \s 归一化更合适，
#: 且全量删除会让 "a b" 变成 "ab"，反而制造出新的可匹配串。
_INVISIBLE_CATEGORIES = frozenset({"Cf", "Mn", "Me", "Cc", "Cs", "Co"})
#: 保留的可见空白（有正常语义，不能当混淆字符删掉）
_KEEP_CONTROL_CHARS = frozenset("\t\n\r")

#: 类别过滤覆盖不到的"不可见字母"。它们属于 Lo/Lm 等**正常字母**类别，
#: 但渲染上不可见，因此是插入混淆的常用手段（实测 U+3164 能绕过规则）。
#: NFKC 会把 U+3164 映射成 U+1160，两者都要在列表里。
#: 完整清单见 Unicode 的 "Hangul filler" 系列；这里保留最小必要集合，
#: 新增只需往这个集合里加一个码点。
_INVISIBLE_LETTERS = frozenset("\u115f\u1160\u3164\uffa0\u2800")


def _is_invisible(char: str) -> bool:
    if char in _KEEP_CONTROL_CHARS:
        return False
    if char in _INVISIBLE_LETTERS:
        return True
    return unicodedata.category(char) in _INVISIBLE_CATEGORIES

#: 候选 Base64 片段：长度 >= 16 且只含 base64 字符，可带 padding
_BASE64_CANDIDATE = re.compile(r"(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{16,}={0,2})(?![A-Za-z0-9+/=])")
#: 候选十六进制片段：长度 >= 24 的偶数长度 hex 串
_HEX_CANDIDATE = re.compile(r"(?<![0-9a-fA-F])((?:[0-9a-fA-F]{2}){12,})(?![0-9a-fA-F])")

MAX_DECODE_CANDIDATES = 6
MAX_DECODED_LENGTH = 2000


@dataclass(frozen=True)
class NormalizedInput:
    """原始文本 + 多个检测视图。``views`` 顺序固定：raw → nfkc → 解码视图。"""

    original: str
    views: tuple[tuple[str, str], ...] = field(default_factory=tuple)
    transforms: tuple[str, ...] = field(default_factory=tuple)

    def view(self, label: str) -> str | None:
        for name, text in self.views:
            if name == label:
                return text
        return None


def _is_meaningful_text(text: str) -> bool:
    """解码结果必须是"人类可读文本"才有意义，避免把二进制垃圾当指令误报。"""
    if len(text) < 8:
        return False
    printable = sum(1 for ch in text if ch.isprintable() or ch in "\n\t ")
    return printable / len(text) > 0.9


def _decode_base64_views(text: str) -> list[tuple[str, str]]:
    views: list[tuple[str, str]] = []
    for index, match in enumerate(_BASE64_CANDIDATE.finditer(text)):
        if index >= MAX_DECODE_CANDIDATES:
            break
        candidate = match.group(1)
        padded = candidate + "=" * (-len(candidate) % 4)
        try:
            decoded = base64.b64decode(padded, validate=True).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError, ValueError):
            continue
        if _is_meaningful_text(decoded):
            views.append((f"base64#{index}", decoded[:MAX_DECODED_LENGTH]))
    return views


def _decode_hex_views(text: str) -> list[tuple[str, str]]:
    views: list[tuple[str, str]] = []
    for index, match in enumerate(_HEX_CANDIDATE.finditer(text)):
        if index >= MAX_DECODE_CANDIDATES:
            break
        candidate = match.group(1)
        try:
            decoded = bytes.fromhex(candidate).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            continue
        if _is_meaningful_text(decoded):
            views.append((f"hex#{index}", decoded[:MAX_DECODED_LENGTH]))
    return views


def normalize(text: str) -> NormalizedInput:
    """产出检测视图。纯函数、无副作用，便于单测与复用。"""
    if text is None:
        text = ""

    transforms: list[str] = []
    stripped = "".join(ch for ch in text if not _is_invisible(ch))
    if stripped != text:
        transforms.append("removed_invisible_chars")

    nfkc = unicodedata.normalize("NFKC", stripped)
    if nfkc != stripped:
        transforms.append("nfkc_normalized")

    views: list[tuple[str, str]] = [("raw", text)]
    if nfkc != text:
        views.append(("nfkc", nfkc))

    base64_views = _decode_base64_views(nfkc)
    if base64_views:
        transforms.append("base64_decoded")
        views.extend(base64_views)

    hex_views = _decode_hex_views(nfkc)
    if hex_views:
        transforms.append("hex_decoded")
        views.extend(hex_views)

    # ROT13 视图：只对 ASCII 字母生效，因此中文文本不会产生噪音视图
    rot13 = codecs.encode(nfkc, "rot13")
    if rot13 != nfkc:
        views.append(("rot13", rot13))
        transforms.append("rot13_view")

    return NormalizedInput(original=text, views=tuple(views), transforms=tuple(transforms))
