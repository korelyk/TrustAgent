package io.trustagent.eval.internal;

import io.trustagent.common.ApiResponse;
import io.trustagent.eval.EvalCase;
import io.trustagent.eval.EvalDataset;
import io.trustagent.eval.EvalDatasetService;
import io.trustagent.eval.EvalRunService;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部评测接口：只由 Python 评测 Worker 调用。
 *
 * <h3>为什么这里一个 {@code @PreAuthorize} 都没有</h3>
 * 鉴权不在方法上，而在路径上：{@link io.trustagent.config.SecurityConfig} 把
 * {@code /internal/**} 统一约束为“必须持有 {@code INTERNAL_SERVICE} 权限”，
 * 而该权限只可能由 {@link io.trustagent.security.InternalTokenFilter} 在
 * {@code X-Internal-Token} 校验通过后授予。
 *
 * <p>这不是“忘了加注解”，而是**故意的单点收口**：如果每个内部方法各写一条注解，
 * 新增方法时漏写就会静默变成一个匿名接口；写在路径上则“默认拒绝”，漏写不会漏权。
 * 这也是为什么本类不接收任何用户 JWT——用户身份在这里完全无意义。
 *
 * <h3>为什么 Worker 要主动“拉”而不是从消息里读</h3>
 * Redis Stream 的消息体只放 {@code runId} 这类引用。用例文本、systemPrompt、工具 Schema
 * 加起来可达几十 KB，塞进消息里既浪费内存，也做不到“回放时按当时的策略执行”。
 */
@RestController
@RequestMapping("/internal/eval")
@RequiredArgsConstructor
public class InternalEvalController {

    /** 单条用例的 tags 在库里是逗号分隔字符串，这里还原成数组给 Worker。 */
    private static final String TAG_SEPARATOR = ",";

    private final EvalRunService runService;
    private final EvalDatasetService datasetService;

    /**
     * Worker 回传结果 / 宣告完成 / 宣告失败。
     *
     * <p>三种 {@code type} 的语义与幂等保证见 {@link EvalRunService#handleCallback}。
     * 返回 {@code accepted/skipped} 而不是简单的 ok：重复批次必须能让调用方
     * 从数字上看出“这批其实一条都没写进去”，否则 Worker 会误以为补偿成功。
     */
    @PostMapping("/callback")
    public ApiResponse<InternalEvalDtos.CallbackResponse> callback(
            @Valid @RequestBody InternalEvalDtos.CallbackRequest request) {
        return ApiResponse.ok(runService.handleCallback(request));
    }

    /**
     * Worker 拉取评测集用例。
     *
     * <p>注意这里只做“数据集是否存在”的检查，不做属主校验——调用方不是用户，
     * 属主概念在这里没有意义。数据集不存在时返回 404，Worker 会把它当成可重试的失败。
     */
    @GetMapping("/datasets/{id}/cases")
    public ApiResponse<InternalEvalDtos.CasesPullResponse> cases(@PathVariable Long id) {
        EvalDataset dataset = datasetService.getByIdOrThrow(id);
        List<InternalEvalDtos.CasePayload> payloads = datasetService.casesForWorker(id).stream()
                .map(InternalEvalController::toPayload)
                .toList();
        return ApiResponse.ok(new InternalEvalDtos.CasesPullResponse(dataset.getId(), dataset.getName(),
                dataset.getSourceCategory(), payloads.size(), payloads));
    }

    /**
     * Worker 拉取“这一次任务该怎么跑”。
     *
     * <p>用 {@code runId} 而不是 {@code datasetId}：执行参数由**任务的模式**决定
     * （{@code GUARD_ON} 用应用策略，{@code GUARD_OFF} 用放宽后的基线参数），
     * 同一个数据集在不同模式下跑出来的配置是不一样的。
     */
    @GetMapping("/config")
    public ApiResponse<InternalEvalDtos.ExecConfigResponse> config(
            @RequestParam Long runId,
            /** 可选：{@code GUARD_ON} / {@code GUARD_OFF}。{@code BOTH} 模式必须显式指定要哪一轮。 */
            @RequestParam(required = false) String round) {
        return ApiResponse.ok(runService.execConfig(runId, round));
    }

    private static InternalEvalDtos.CasePayload toPayload(EvalCase item) {
        return new InternalEvalDtos.CasePayload(item.getCaseId(), item.getCategory(), item.getPrompt(),
                item.getContext(), item.getExpectedAction(), item.getExpectedTool(),
                Boolean.TRUE.equals(item.getContainsSensitiveData()), splitTags(item.getTags()));
    }

    private static List<String> splitTags(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(TAG_SEPARATOR)).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
