package io.trustagent.gateway;

import io.trustagent.gateway.dto.AgentRunRequest;
import io.trustagent.gateway.dto.AgentRunResponse;

/**
 * 执行面客户端抽象。
 *
 * <p>抽成接口的实际收益：集成测试里可以用一个"假执行面"Bean 替换真实 HTTP 调用，
 * 从而在**不启动 Python 进程**的前提下验证控制面的完整链路（落库、事件、审计、指标）。
 * 这是把"外部依赖"变成"可替换协作者"的典型做法。
 */
public interface PythonAgentClient {

    /**
     * 执行一次 Agent 请求。
     *
     * @param traceId 控制面生成的 Trace ID，会通过 {@code X-Trace-Id} 透传
     * @throws io.trustagent.common.BizException 执行面不可用（502）或超时（504）
     */
    AgentRunResponse run(AgentRunRequest request, String traceId);

    /** 执行面是否可用（用于健康检查/仪表盘，不做业务判断）。 */
    boolean healthy();
}
