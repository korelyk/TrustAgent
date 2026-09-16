"""内部接口鉴权依赖测试。

回归背景：``secrets.compare_digest`` 对 ``str`` 的语义是"每个字符必须是 ASCII"，
一旦任一侧含非 ASCII 字符就抛 ``TypeError``。旧实现没有捕获它，
于是 ``X-Internal-Token: 中文`` 让鉴权接口返回 **500** 而不是 **401**：
对攻击者是"服务在跑"的信息泄露，对运维是一次被误报成故障的告警。
"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.api.deps import verify_internal_token
from app.config import Settings

TOKEN = "test-internal-token-0123456789"


def _settings(token: str = TOKEN) -> Settings:
    return Settings(internal_token=token)


class TestInternalTokenVerification:
    def test_accepts_the_exact_token(self):
        verify_internal_token(x_internal_token=TOKEN, settings=_settings())

    def test_rejects_a_wrong_token_with_401(self):
        with pytest.raises(HTTPException) as excinfo:
            verify_internal_token(x_internal_token="wrong-token-0000000000", settings=_settings())
        assert excinfo.value.status_code == 401

    def test_rejects_missing_header_with_401(self):
        with pytest.raises(HTTPException) as excinfo:
            verify_internal_token(x_internal_token=None, settings=_settings())
        assert excinfo.value.status_code == 401

    @pytest.mark.parametrize("candidate", [
        "中文令牌",                     # 非 ASCII：旧实现抛 TypeError → 500
        "tökén-with-umlaut",           # 非 ASCII 的另一种形态
        "😀😀😀😀😀😀😀😀",              # 非 BMP 字符
        "\u200btoken",                # 零宽字符
    ])
    def test_non_ascii_token_is_rejected_with_401_not_500(self, candidate: str):
        with pytest.raises(HTTPException) as excinfo:
            verify_internal_token(x_internal_token=candidate, settings=_settings())
        assert excinfo.value.status_code == 401, "非 ASCII 输入必须走 401，不能变成 500"

    def test_unconfigured_service_fails_closed(self):
        """没配 Token 时必须全部拒绝，而不是"没配就放行"。"""
        with pytest.raises(HTTPException) as excinfo:
            verify_internal_token(x_internal_token="anything", settings=_settings(""))
        assert excinfo.value.status_code == 401
        assert "fail closed" in excinfo.value.detail

    def test_non_ascii_never_raises_type_error(self):
        """直接钉住异常类型：TypeError 逃出去就是 500。"""
        for candidate in ("中文", "tökén", "😀" * 4):
            try:
                verify_internal_token(x_internal_token=candidate, settings=_settings())
            except HTTPException:
                pass
            except TypeError as exc:  # pragma: no cover - 只在回归时触发
                pytest.fail(f"非 ASCII 输入触发了 TypeError（会变成 500）: {exc}")

