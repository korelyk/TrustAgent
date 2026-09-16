package io.trustagent.eval.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 内部接口（{@code /internal/eval/**}）的请求/响应模型。
 *
 * <p>这些接口只由 Python 评测 Worker 调用，走 {@code X-Internal-Token}，
 * 浏览器与前端一律拿不到。字段命名与 {@code docs/API.md} §7 严格一致。
 *
 * <h3>为什么对回调体做长度限制</h3>
 * 回调是**半可信输入**：Token 只证明"调用方是执行面"，不证明"payload 是合理的"。
 * 一次误发的 10 万条结果足以把控制面打满，因此 {@code results} 有数量上限、
 * 各字段有长度上限，超限直接 400，而不是默默写库。
 */
public final class InternalEvalDtos {

    private InternalEvalDtos() {
    }

    /** 单次回调最多携带多少条用例结果。 */
    public static final int MAX_RESULTS_PER_CALLBACK = 500;

    /**
     * Worker → 控制面 的回调。
     *
     * <p>{@code type} 取 {@code CASE_RESULTS} / {@code COMPLETED} / {@code FAILED}：
     * <ul>
     *   <li>{@code CASE_RESULTS}：分批回传逐条结果（幂等，靠唯一键兜底）；</li>
     *   <li>{@code COMPLETED}：宣布本轮跑完，带执行面算出的指标供控制面比对；</li>
     *   <li>{@code FAILED}：宣告任务失败（例如评测集被删、下游不可用），带原因。</li>
     * </ul>
     */
    public record CallbackRequest(
            @NotNull(message = "runId 不能为空") Long runId,
            @NotBlank(message = "type 不能为空")
            @Size(max = 32) String type,
            @Min(1) @Max(100) Integer attempt,
            @Size(max = 64) String traceId,
            @Valid @Size(max = MAX_RESULTS_PER_CALLBACK,
                    message = "单次回调结果数不得超过 " + MAX_RESULTS_PER_CALLBACK) List<CaseResultPayload> results,
            /** 执行面上报的指标：{ guardOn: {...}, guardOff: {...} }。控制面会独立重算并比对。 */
            Object metrics,
            @Size(max = 512) String errorMessage) {
    }

    public record CaseResultPayload(
            @NotBlank @Size(max = 64) String caseId,
            @NotBlank @Size(max = 48) String category,
            @NotNull(message = "guardEnabled 不能为空") Boolean guardEnabled,
            @Size(max = 32) String expectedAction,
            @Size(max = 32) String actualAction,
            @Size(max = 64) String expectedTool,
            @Size(max = 64) String actualTool,
            @NotNull(message = "blocked 不能为空") Boolean blocked,
            @Size(max = 100) List<@Size(max = 48) String> detectedCategories,
            @NotNull(message = "piiLeaked 不能为空") Boolean piiLeaked,
            @NotNull(message = "passed 不能为空") Boolean passed,
            @NotNull @Min(0) @Max(600_000) Integer latencyMs,
            /** 任意结构的明细（命中规则、输出预览等），控制面原样作为 JSON 落库并脱敏。 */
            Object detail,
            @Size(max = 512) String errorMessage) {
    }

    /** 回调确认：{@code accepted} 为真的写入条数，{@code skipped} 为因幂等被跳过的条数。 */
    public record CallbackResponse(int accepted, int skipped, String runStatus) {
    }

    /** Worker 拉取评测集用例（避免把上百条用例塞进 Redis Stream 消息体）。 */
    public record CasesPullResponse(Long datasetId, String name, String sourceCategory, int total,
                                    List<CasePayload> cases) {
    }

    public record CasePayload(String caseId, String category, String prompt, String context,
                              String expectedAction, String expectedTool, boolean containsSensitiveData,
                              List<String> tags) {
    }

    /**
     * Worker 拉取"这一次任务该怎么跑"的全部执行参数。
     *
     * <p>为什么不在 Redis 消息里带这些：{@code systemPrompt}、工具 Schema 加起来可能几 KB，
     * 而且是**会变的**（用户随时可能改策略）。消息里只放引用（runId），
     * 真正执行时再拉一次快照，能保证"跑的就是当时生效的那一版"。
     */
    public record ExecConfigResponse(Long runId, String mode,
                                     /**
                                      * 这一份配置是给哪一轮用的：{@code GUARD_ON} 或 {@code GUARD_OFF}。
                                      *
                                      * <p>为什么必须显式给出来：{@code mode=BOTH} 时同一个任务要跑两轮，
                                      * 而 {@code mode} 字段只能表达"这个任务有两个轮次"，
                                      * 无法表达"我这次要哪一轮"。Worker 靠这个字段决定把结果标成
                                      * {@code guardEnabled=true} 还是 {@code false}，标错就会污染对照组。
                                      */
                                     String round,
                                     Long appId, String appCode,
                                     String systemPrompt, List<String> allowedTools,
                                     Map<String, String> toolSchemas, PolicyPayload policy,
                                     RunLimits runConfig) {
    }

    /** 与 Python 侧 {@code app.schemas.ToolPolicy} 一一对应（camelCase）。 */
    public record PolicyPayload(boolean guardEnabled, int maxToolCalls, int maxInputLength,
                                int taskTimeoutMs, List<String> domainWhitelist,
                                List<String> deniedParamRules, boolean confirmSideEffects,
                                boolean injectionDetectionEnabled, boolean piiMaskingEnabled,
                                boolean outputGuardEnabled) {
    }

    public record RunLimits(int maxCasesPerDataset, int jobTimeoutSeconds, int maxDeliveries) {
    }
}
