package io.trustagent.app;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_tool")
public class AgentTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appId;

    private String toolName;

    private Boolean enabled;

    /** 参数 JSON Schema 文本。存文本以便同一份迁移脚本同时跑 MySQL 与 H2。 */
    private String paramSchema;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
