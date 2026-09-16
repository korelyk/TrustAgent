package io.trustagent.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 审计记录。{@code prevHash} + {@code recordHash} 构成单向哈希链。 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链严格递增且连续，从 1 开始。验证时用它定位“首个异常位置”。 */
    private Long seq;

    private Long actorId;

    private String actorUsername;

    private String action;

    private String resourceType;

    private String resourceId;

    /** 规范化后的非敏感明细。禁止写入密码、Token、Prompt 原文。 */
    private String detailJson;

    private String traceId;

    private String prevHash;

    private String recordHash;

    private LocalDateTime createdAt;

    /** 参与哈希的时间（epoch 毫秒）。见 {@link AuditHashChain} 的说明。 */
    private Long createdAtEpochMs;
}
