"""执行面配置。全部通过环境变量注入，代码里不出现任何密钥默认值。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent

#: 演示文档目录的默认值。与 ``demo_docs_dir`` 的 validator 共用同一个常量，
#: 避免"定义默认值的地方"和"空值回落的地方"各写一份而逐渐漂移。
DEFAULT_DEMO_DOCS_DIR = BASE_DIR / "resources" / "demo_docs"
DEFAULT_OUTBOX_FILE = BASE_DIR / ".data" / "mock_outbox.jsonl"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="TRUSTAGENT_",
        env_file=None,  # 由部署层注入环境变量；本地开发用 scripts 里的导出脚本
        extra="ignore",
    )

    # ---- 服务 ----
    service_name: str = "trustagent-ai-service"
    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # ---- 服务间鉴权 ----
    # 与 Java 侧 trustagent.internal.token 必须一致。留空则所有内部接口一律拒绝（fail closed）。
    internal_token: str = ""
    internal_header: str = "X-Internal-Token"

    # ---- LLM ----
    # mock（默认，确定性、无需任何 Key）| openai_compatible
    llm_provider: str = "mock"
    llm_model: str = "mock-llm-v1"
    openai_base_url: str = ""
    openai_api_key: str = ""
    openai_timeout_seconds: float = 30.0

    # ---- 执行面自身的兜底上限 ----
    # 即使控制面策略配错，执行面也不会无限制地跑
    hard_max_input_length: int = 20000
    hard_max_tool_calls: int = 10
    hard_task_timeout_ms: int = 60000

    # ---- 演示文档 ----
    demo_docs_dir: Path = Field(default=DEFAULT_DEMO_DOCS_DIR)
    doc_search_top_k: int = 3
    # 片段长度要足以覆盖演示文档的正文（尤其 04_upgrade_notice.md 里靠后的注入样例），
    # 否则"间接注入检测"会因为内容被截断而检测不到
    doc_snippet_max_length: int = 1200

    # ---- Mock Outbox ----
    outbox_file: Path = Field(default=DEFAULT_OUTBOX_FILE)

    # ---- 评测 Worker ----
    eval_worker_enabled: bool = True
    redis_url: str = "redis://localhost:6379/0"
    eval_stream_key: str = "eval.jobs"
    eval_dead_letter_key: str = "eval.jobs.dlq"
    eval_consumer_group: str = "cg:eval"
    eval_consumer_name: str = "worker-1"
    eval_max_deliveries: int = 3
    eval_read_block_ms: int = 5000
    eval_batch_size: int = 20
    #: 一条消息"多久没被 ACK 就认为它卡住了、允许换一个消费者重新认领"。
    #: 这是 Worker 崩溃后任务能自愈的关键参数，必须明显大于一条任务的最长执行时间，
    #: 否则会出现"任务还在跑，另一个 Worker 又把它抢过去跑"的重复执行。
    eval_reclaim_idle_ms: int = 60000

    # ---- 空值回落 ----------------------------------------------------------
    # 背景（这是一个真实踩过的坑）：.env.example 里这两个键是留空的，而
    # scripts/_env.ps1 会把"空值"也写进环境变量（得到真正的空字符串），
    # 于是 pydantic-settings 用它覆盖了 Field 的默认值。Path("") 会被解析成
    # "."（当前工作目录），结果 search_local_docs 在整个 ai-service/ 目录里检索，
    # 演示文档里那条"间接提示注入"样本永远不会被加载 ——
    # 场景 C 与全部 10 条 PROMPT_INJECTION_INDIRECT 评测用例会静默失效。
    #
    # 因此这里把"空字符串"显式视为"未配置"，回落成默认值。
    # 注意：不能只依赖 Field(default=...)，因为默认值只在"字段缺失"时生效，
    # 而"存在但为空"是部署中最常见的形态。

    @field_validator("demo_docs_dir", "outbox_file", mode="before")
    @classmethod
    def _blank_path_falls_back_to_default(cls, value: object, info: object) -> object:
        if value is None:
            return cls.model_fields[info.field_name].default  # type: ignore[attr-defined]
        if isinstance(value, str) and not value.strip():
            return cls.model_fields[info.field_name].default  # type: ignore[attr-defined]
        return value

    # ---- 回调 Java 控制面 ----
    java_base_url: str = "http://localhost:8080"
    java_callback_timeout_seconds: float = 30.0


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """配置单例。测试里可用 get_settings.cache_clear() 后重新读取环境变量。"""
    return Settings()
