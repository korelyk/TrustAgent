package io.trustagent.eval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评测相关的请求/响应 DTO。
 *
 * <p>范围约束写在注解上而不是在 Service 里手写 if：好处是校验信息能直接返回给前端，
 * 且 OpenAPI 文档会自动体现约束（与 {@code AppDtos} 保持一致的做法）。
 */
public final class EvalDtos {

    private EvalDtos() {
    }

    /**
     * 导入评测集。
     *
     * <p>{@code content} 是 JSONL 原文（每行一个 JSON 对象）。
     * {@code @Size(max = 5_000_000)} 是**协议层的硬上限**：单次导入最多 5MB 文本，
     * 约合几万条用例，远超 {@code max-cases-per-dataset} 的业务上限。
     * 两道限制的理由不同——一个防请求体打爆内存，一个防任务跑到天亮。
     */
    public record DatasetImportRequest(
            @NotBlank(message = "评测集名称不能为空") @Size(max = 128) String name,
            @Size(max = 512) String description,
            @NotBlank(message = "来源类别不能为空") @Size(max = 64) String sourceCategory,
            @NotBlank(message = "评测集内容不能为空")
            @Size(max = 5_000_000, message = "单次导入内容不得超过 5MB") String content) {
    }

    public record ImportErrorView(int lineNumber, String caseId, String reason) {
    }

    public record ImportReport(Long datasetId, int totalLines, int imported, int rejected,
                               Map<String, Integer> categoryCounts, List<ImportErrorView> errors) {
    }

    public record DatasetView(Long id, String name, String description, String sourceCategory,
                              String checksum, Integer caseCount, Long ownerId, String ownerName,
                              LocalDateTime createdAt) {
    }

    public record CaseView(String caseId, String category, String categoryDescription, String prompt,
                           String context, String expectedAction, String expectedTool,
                           Boolean containsSensitiveData, List<String> tags) {
    }

    public record RunCreateRequest(
            @NotNull(message = "datasetId 不能为空") Long datasetId,
            @NotNull(message = "appId 不能为空") Long appId,
            @NotBlank(message = "mode 不能为空")
            @Pattern(regexp = "GUARD_ON|GUARD_OFF|BOTH", message = "mode 只能是 GUARD_ON / GUARD_OFF / BOTH")
            String mode) {
    }

    public record RunCreateResponse(Long runId, String status, int totalCases, boolean idempotentReplay) {
    }

    /**
     * 任务详情。
     *
     * <p>{@code metrics} 的结构是 {@code { "PAIRED": { "guardOn": {...}, "guardOff": {...} } }}。
     * 用嵌套 Map 而不是强类型 record，是为了让"指标口径扩展"不需要改动接口签名；
     * 代价是前端要按约定取字段。口径本身由 {@link io.trustagent.eval.EvalMetricsCalculator} 锁死。
     */
    public record RunView(Long id, Long datasetId, String datasetName, Long appId, String appName,
                          Long ownerId, String mode, String status, int totalCases, int completedCases,
                          int failedCases, int attempts, String errorMessage, String traceId,
                          Map<String, Object> metrics, LocalDateTime createdAt,
                          LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record CaseResultView(String caseId, String category, boolean guardEnabled, String expectedAction,
                                 String actualAction, String expectedTool, String actualTool, boolean blocked,
                                 List<String> detectedCategories, boolean piiLeaked, boolean passed,
                                 int latencyMs, String detailJson, String errorMessage) {
    }
}
