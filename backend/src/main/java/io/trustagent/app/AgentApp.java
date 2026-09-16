package io.trustagent.app;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_app")
public class AgentApp {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外稳定标识，被用于 Trace、日志与评测任务引用。 */
    private String code;

    private String name;

    private String description;

    private Long ownerId;

    /** 系统提示词。属于敏感配置：输出安检会检测它是否被泄露。 */
    private String systemPrompt;

    /** ACTIVE / DISABLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
