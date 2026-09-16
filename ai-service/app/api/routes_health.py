"""健康检查。刻意不返回版本号与依赖版本，减少指纹信息。"""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.schemas import CamelModel

router = APIRouter()


class HealthResponse(CamelModel):
    status: str
    provider: str
    uptime_seconds: int


_STARTED_AT = time.monotonic()


@router.get("/health", response_model=HealthResponse, tags=["health"])
def health(settings: Settings = Depends(get_settings)) -> HealthResponse:
    return HealthResponse(
        status="UP",
        provider=settings.llm_provider,
        uptime_seconds=int(time.monotonic() - _STARTED_AT),
    )
