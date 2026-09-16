"""接口依赖：内部 Token 校验、容器对象。

**fail closed**：``TRUSTAGENT_INTERNAL_TOKEN`` 未配置时，所有需要鉴权的接口
一律返回 401，而不是"没配就默认放行"。这是安全默认值的基本要求。

用 ``secrets.compare_digest`` 做常量时间比较（按 UTF-8 字节比较，避免非 ASCII 输入触发 TypeError → 500）。
"""

from __future__ import annotations

import secrets

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings


def verify_internal_token(
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
    settings: Settings = Depends(get_settings),
) -> None:
    expected = settings.internal_token or ""
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="服务未配置内部凭证，内部接口已全部拒绝（fail closed）",
        )
    if not _token_matches(expected, x_internal_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部凭证无效")



def _token_matches(expected: str, provided: str | None) -> bool:
    """常量时间比较内部凭证。

    ``secrets.compare_digest`` 对 ``str`` 的语义是"每个字符必须是 ASCII"：
    一旦任一侧含非 ASCII 字符（例如攻击者把 Header 写成 ``X-Internal-Token: 中文``），
    它会抛 ``TypeError: comparing strings with non-ASCII characters is not supported``，
    而这个异常没有被调用方捕获 —— 表现为**鉴权接口返回 500 而不是 401**。
    500 既泄露了"服务在跑"的信息，也让监控把一次攻击误报成故障。

    先按 UTF-8 编码成 bytes 再比较：``compare_digest`` 对 bytes 没有字符集限制，
    仍然是常量时间，长度不同时也只会退化到"按较长者比较"，不泄露长度以外的信息。
    """
    if not provided:
        return False
    return secrets.compare_digest(expected.encode("utf-8"), provided.encode("utf-8"))

InternalAuth = Depends(verify_internal_token)
