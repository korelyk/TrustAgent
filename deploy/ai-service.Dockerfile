# Python 执行面镜像
#
# 只装 requirements.txt（纯 Python wheel，不含任何模型权重、不含 torch/transformers）。
# 真实模型通过 TRUSTAGENT_OPENAI_BASE_URL / TRUSTAGENT_OPENAI_API_KEY 环境变量接入，
# **镜像内不含任何密钥**，因此同一个镜像可以在不同环境直接复用。

FROM python:3.12-slim AS runtime
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

COPY ai-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY ai-service/app ./app
COPY ai-service/resources ./resources

# Mock Outbox 的落盘目录（send_notification 只写这里，不连任何真实邮件/短信通道）
RUN useradd --system --uid 1001 --no-create-home trustagent \
    && mkdir -p /app/.data \
    && chown -R trustagent:trustagent /app
USER trustagent

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
