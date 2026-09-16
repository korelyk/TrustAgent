package io.trustagent.app.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 应用列表的联接查询投影。
 *
 * <p>为什么不逐个应用再查一次 owner 名称：那是典型的 N+1 查询，
 * 列表页有 50 个应用就会打出 51 条 SQL。这里用一次 LEFT JOIN + 子查询统计启用工具数。
 */
@Data
public class AgentAppRow {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Long ownerId;
    private String ownerName;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer enabledToolCount;
}
