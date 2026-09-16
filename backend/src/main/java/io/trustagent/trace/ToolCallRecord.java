package io.trustagent.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一次工具调用的留痕。
 *
 * <p>**被拒绝的调用同样要记录**（{@code allowed=false} + {@code denyReason}）：
 * “谁在什么时候试图调用什么被拦了”本身就是最有价值的安全信号，
 * 只记成功调用会让越权尝试在数据里凭空消失。
 */
@Data
@TableName("tool_call_record")
public class ToolCallRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String toolName;

    private String argumentsMasked;

    private String resultMasked;

    private Boolean allowed;

    private String denyReason;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
