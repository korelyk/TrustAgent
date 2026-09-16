package io.trustagent.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一次 Agent 请求的摘要。
 *
 * <p>注意：{@code inputPreview} / {@code outputPreview} 只允许存**截断且已脱敏**的片段。
 * 原始 Prompt 与原始模型输出不入库，理由见 docs/SECURITY_LIMITATIONS.md 与 docs/THREAT_MODEL.md §6。
 */
@Data
@TableName("agent_trace")
public class AgentTrace {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long appId;

    private Long userId;

    private String requestId;

    private String inputPreview;

    private String outputPreview;

    /** ALLOW / MASK / BLOCK */
    private String guardDecision;

    private String guardCategories;

    private String status;

    private Integer toolCallCount;

    private Integer totalLatencyMs;

    private String errorMessage;

    private LocalDateTime createdAt;
}
