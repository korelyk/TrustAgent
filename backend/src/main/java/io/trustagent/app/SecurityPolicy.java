package io.trustagent.app;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 每个 Agent 应用一份生效策略。
 *
 * <p>这些字段是**执行面必须强制执行的硬规则**，不是给模型的“建议”。
 * 修改策略会递增 {@code version} 并写审计记录。字段含义见 docs/DATABASE.md §2.2。
 */
@Data
@TableName("security_policy")
public class SecurityPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    /** 乐观锁用的版本号：客户端提交的版本与服务端不一致时返回 409。 */
    private Integer version;

    /** 总开关。关闭后执行面不做注入/PII/输出检测，仅用于“防护关闭”对照评测。 */
    private Boolean guardEnabled;

    private Integer maxToolCalls;

    private Integer maxInputLength;

    private Integer taskTimeoutMs;

    /** 逗号分隔的域名白名单；为空表示不限制域名（仅对需要外连的工具生效）。 */
    private String domainWhitelist;

    /** 逗号分隔的敏感参数名，命中即拒绝该次工具调用。 */
    private String deniedParamRules;

    private Boolean confirmSideEffects;

    private Boolean injectionDetectionEnabled;

    private Boolean piiMaskingEnabled;

    private Boolean outputGuardEnabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
