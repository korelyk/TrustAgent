package io.trustagent.eval;

import io.trustagent.common.ApiResponse;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.PageResult;
import io.trustagent.eval.dto.EvalDtos;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评测接口（数据集导入、任务创建与查询、报告导出）。
 *
 * <p>职责边界：Controller 只做“协议层”的事——权限注解、请求校验、HTTP 语义（202/附件头），
 * 业务规则全部在 {@link EvalDatasetService} / {@link EvalRunService} 里。
 * 这样做的直接好处是：评测的关键逻辑（幂等、指标复算、逐行校验）可以脱离 HTTP 被测试。
 *
 * <h3>两个容易被忽略的协议细节</h3>
 * <ul>
 *   <li>创建任务成功返回 <b>202</b> 而不是 200：任务只是“已受理并进入队列”，
 *       并没有完成。用 200 会让调用方误以为可以立刻读结果。</li>
 *   <li>幂等重放返回 <b>200</b>：这不是新任务，把它和“新受理”区分开，
 *       前端才能判断要不要提示“该请求已提交过”。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    /** Idempotency-Key 的长度上限：够放 UUID / 自定义串，又不至于被拿来打库。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;
    private final EvalReportExporter reportExporter;

    // ------------------------------------------------------------------ 数据集

    /**
     * 导入 JSONL 评测集（JSON body）。
     *
     * <p>返回的是“部分成功也要如实汇报”的报告：{@code totalLines / imported / rejected + errors}。
     * 只有全部行都失败时才抛 400（并把报告放在 {@code error.details} 里），
     * 避免留下一个 0 条用例的幽灵数据集。
     */
    @PostMapping(value = "/datasets/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('eval:dataset:write')")
    public ApiResponse<EvalDtos.ImportReport> importDataset(
            @Valid @RequestBody EvalDtos.DatasetImportRequest request) {
        return ApiResponse.ok(datasetService.importDataset(request));
    }

    /**
     * 导入 JSONL 评测集（multipart/form-data）。
     *
     * <p>与 JSON 版本并存而不是二选一：浏览器里粘贴几十行用 JSON 更顺手，
     * 而 {@code curl -F file=@dataset.jsonl} 传几百 KB 的文件用 multipart 更自然。
     * 两条入口最终都汇到同一个 {@link EvalDatasetService#importDataset}，
     * 校验规则只有一份，不存在“两条路跑出两种结果”。
     */
    @PostMapping(value = "/datasets/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('eval:dataset:write')")
    public ApiResponse<EvalDtos.ImportReport> importDatasetMultipart(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("sourceCategory") String sourceCategory,
            @RequestParam("file") MultipartFile file) {
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("评测集名称不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String content;
        try {
            // 评测集是纯文本 JSONL，统一按 UTF-8 读；带 BOM 的文件在 Service 里会被当成普通字符拒绝，
            // 因此这里先剥掉 BOM，避免用户用 Excel/记事本另存后整份导入失败。
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw BizException.badRequest("读取上传文件失败: " + ex.getMessage());
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        return ApiResponse.ok(datasetService.importDataset(
                new EvalDtos.DatasetImportRequest(name, description, sourceCategory, content)));
    }

    @GetMapping("/datasets")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<List<EvalDtos.DatasetView>> listDatasets() {
        return ApiResponse.ok(datasetService.listDatasets());
    }

    @GetMapping("/datasets/{id}/cases")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<PageResult<EvalDtos.CaseView>> listCases(@PathVariable Long id,
                                                               @RequestParam(defaultValue = "1") long page,
                                                               @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(datasetService.listCases(id, page, size));
    }

    // ------------------------------------------------------------------ 任务

    /**
     * 创建评测任务。必须带 {@code Idempotency-Key}。
     *
     * <p>为什么把“缺 Key”做成 400 而不是“没有 Key 就退化成非幂等”：
     * 静默降级会让调用方以为自己是幂等的（例如网络重试），
     * 结果一次超时重试就凭空多跑一轮评测。<b>宁可报错，也不要给一个语义错误的成功。</b>
     */
    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('eval:run')")
    public ResponseEntity<ApiResponse<EvalDtos.RunCreateResponse>> createRun(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EvalDtos.RunCreateRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "缺少 Idempotency-Key 请求头（建议使用 UUID）");
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BizException.badRequest("Idempotency-Key 长度不得超过 " + MAX_IDEMPOTENCY_KEY_LENGTH);
        }

        EvalDtos.RunCreateResponse response = runService.createRun(request, key);
        HttpStatus status = response.idempotentReplay() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<List<EvalDtos.RunView>> listRuns(@RequestParam(required = false) String status) {
        return ApiResponse.ok(runService.listRuns(status));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<EvalDtos.RunView> runDetail(@PathVariable Long id) {
        return ApiResponse.ok(runService.runDetail(id));
    }

    @GetMapping("/runs/{id}/results")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<PageResult<EvalDtos.CaseResultView>> results(@PathVariable Long id,
                                                                    @RequestParam(required = false) String category,
                                                                    @RequestParam(required = false) Boolean passed,
                                                                    @RequestParam(defaultValue = "1") long page,
                                                                    @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(runService.listResults(id, category, passed, page, size));
    }

    /**
     * 导出报告。
     *
     * <p>刻意<b>不套</b>统一响应包：这个接口是给“下载”用的，响应体直接就是文件内容，
     * 带上 {@code Content-Disposition} 让浏览器落到本地。把文件内容再包一层 JSON
     * 会让 {@code curl -o report.csv} 得到一个坏掉的 CSV。
     */
    @GetMapping("/runs/{id}/report")
    @PreAuthorize("hasAuthority('eval:read')")
    public ResponseEntity<?> report(@PathVariable Long id,
                                    @RequestParam(defaultValue = "json") String format) {
        String normalized = format == null ? "json" : format.trim().toLowerCase();
        List<EvalCaseResult> results = runService.allResultsForReport(id);
        return switch (normalized) {
            case "csv" -> ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment("eval-run-" + id + ".csv"))
                    .body(reportExporter.toCsv(id, results));
            case "json" -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment("eval-run-" + id + ".json"))
                    .body(reportExporter.toJsonReport(runService.runDetail(id), results));
            default -> throw BizException.badRequest("format 只能是 json 或 csv");
        };
    }

    /** 用引号包住文件名，避免 id 里意外出现分隔符时破坏附件头。 */
    private static String attachment(String filename) {
        return "attachment; filename=\"" + filename + "\"";
    }

    /** 供前端判断“可用的报告格式”，避免把合法取值写死在前端。 */
    @GetMapping("/report-formats")
    @PreAuthorize("hasAuthority('eval:read')")
    public ApiResponse<Map<String, Object>> reportFormats() {
        return ApiResponse.ok(Map.of("formats", List.of("json", "csv"), "default", "json"));
    }
}
