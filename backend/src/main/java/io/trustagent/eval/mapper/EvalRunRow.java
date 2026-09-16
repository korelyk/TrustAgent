package io.trustagent.eval.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测任务列表的联接投影。
 *
 * <p>刻意**不包含** {@code guardMetricsJson} / {@code baselineMetricsJson}：
 * 列表页只显示进度，明细与指标走详情接口。把两段大 JSON 塞进列表查询会让响应体膨胀几十倍。
 */
@Data
public class EvalRunRow {

    private Long id;
    private Long datasetId;
    private String datasetName;
    private Long appId;
    private String appName;
    private Long ownerId;
    private String mode;
    private String status;
    private Integer totalCases;
    private Integer completedCases;
    private Integer failedCases;
    private Integer attempts;
    private String errorMessage;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
