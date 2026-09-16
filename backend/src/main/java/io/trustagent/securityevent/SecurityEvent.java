package io.trustagent.securityevent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 安全事件。
 *
 * <p>{@code evidenceMasked} 只存命中片段的**脱敏**版本（例如 {@code 138****8000}），
 * 这样既能证明“我们确实检测到了什么”，又不会把敏感值二次落库。
 */
@Data
@TableName("security_event")
public class SecurityEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private Long appId;

    /** 见 {@link SecurityEventType} */
    private String eventType;

    /** LOW / MEDIUM / HIGH / CRITICAL */
    private String severity;

    private String detector;

    private String ruleId;

    private String evidenceMasked;

    /** ALLOW / MASK / BLOCK / LOG */
    private String actionTaken;

    private LocalDateTime createdAt;
}
