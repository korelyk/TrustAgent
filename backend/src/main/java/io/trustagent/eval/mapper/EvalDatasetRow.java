package io.trustagent.eval.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测集列表的联接投影。
 *
 * <p>与 {@code AgentAppRow} 同样的理由：列表页要显示属主用户名，
 * 逐个数据集再查一次 {@code sys_user} 就是 N+1。这里一次 LEFT JOIN 解决。
 */
@Data
public class EvalDatasetRow {

    private Long id;
    private String name;
    private String description;
    private String sourceCategory;
    private String checksum;
    private Integer caseCount;
    private Long ownerId;
    private String ownerName;
    private LocalDateTime createdAt;
}
