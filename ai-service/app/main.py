"""FastAPI 应用入口。

启动期只做三件事：读配置、构建工具与检测器、装配编排器。
**不做任何网络调用与模型加载**：这样执行面能在毫秒级启动，也符合"默认 Mock、不出网"的约束。
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import routes_agent, routes_health
from app.config import Settings, get_settings
from app.observability.logging import configure_logging, kv
from app.observability.trace import set_trace_id
from app.runtime import build_runtime

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        configure_logging(resolved_settings.log_level)
        logger.info(kv(event="service.start", provider=resolved_settings.llm_provider,
                       tools=len(app.state.tools), workerEnabled=resolved_settings.eval_worker_enabled))
        if not resolved_settings.internal_token:
            logger.warning("未配置 TRUSTAGENT_INTERNAL_TOKEN：所有需要鉴权的接口都会返回 401（fail closed）")

        worker_task: asyncio.Task | None = None
        if resolved_settings.eval_worker_enabled:
            worker_task = asyncio.create_task(
                _run_eval_worker(resolved_settings), name="eval-worker")

        try:
            yield
        finally:
            if worker_task is not None:
                # 优雅停止：先请它自己退出（把正在处理的消息跑完并 ACK），再兜底取消。
                # 直接 cancel 会让已处理一半的批次留在 PEL 里，虽然能靠重投恢复，
                # 但会白跑一遍。
                worker_task.cancel()
                with suppress(asyncio.CancelledError):
                    await worker_task
            logger.info(kv(event="service.stop"))

    app = FastAPI(
        title="TrustAgent 执行面 API",
        version="0.1.0",
        description="Agent 执行、输入/输出安全检查、工具策略校验。内部接口需要 X-Internal-Token。",
        lifespan=lifespan,
    )

    app.state.settings = resolved_settings
    # 装配只有一处（app/runtime.py）：API 与评测 Worker 必须用同一个编排器，
    # 否则"评测结果"就不再代表线上行为。
    runtime = build_runtime(resolved_settings)
    app.state.outbox = runtime.outbox
    app.state.tools = runtime.tools
    app.state.pipeline = runtime.pipeline
    app.state.orchestrator = runtime.orchestrator

    @app.middleware("http")
    async def trace_id_middleware(request: Request, call_next):
        trace_id = set_trace_id(request.headers.get("X-Trace-Id"))
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_handler(_request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=400, content={
            "error": "VALIDATION_FAILED",
            "detail": exc.errors()[:5],
        })

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(_request: Request, exc: StarletteHTTPException):
        return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})

    @app.exception_handler(Exception)
    async def unhandled_handler(_request: Request, exc: Exception):
        # 不把堆栈返回给调用方；细节留在服务端日志里（带 traceId）
        logger.exception(kv(event="request.error", error=type(exc).__name__))
        return JSONResponse(status_code=500, content={"error": "INTERNAL_ERROR"})

    app.include_router(routes_health.router)
    app.include_router(routes_agent.router)
    return app


async def _run_eval_worker(settings: Settings) -> None:
    """在 API 进程内顺带跑评测 Worker。

    **连不上 Redis 时不让进程启动失败**：Agent 接口本身并不依赖 Redis
    （限流与队列都有内存实现），为了评测把整个服务拖垮不划算。
    真正的表现是日志里一条告警 + 评测任务停在 PENDING。

    需要独立扩缩容时，用 ``python -m app.eval.worker`` 单独起进程，
    并把 ``TRUSTAGENT_EVAL_WORKER_ENABLED`` 设为 false 关掉内置的这份。
    """
    from app.eval.worker import build_worker

    try:
        worker = build_worker(settings)
        await worker.run_forever()
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        logger.warning(kv(event="eval.worker.disabled", error=type(exc).__name__,
                          hint="Redis 不可用时评测任务会停留在 PENDING；"
                               "异步评测需要默认部署形态（Redis）或启动容器化的 Redis"))


app = create_app()
