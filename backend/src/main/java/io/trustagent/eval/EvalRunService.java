package io.trustagent.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.app.AgentApp;
import io.trustagent.app.AgentAppService;
import io.trustagent.app.SecurityPolicy;
import io.trustagent.app.ToolCatalog;
import io.trustagent.audit.AuditService;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.PageResult;
import io.trustagent.common.SensitiveDataMasker;
import io.trustagent.common.TraceContext;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.eval.dto.EvalDtos;
import io.trustagent.eval.internal.InternalEvalDtos;
import io.trustagent.eval.mapper.EvalCaseMapper;
import io.trustagent.eval.mapper.EvalCaseResultMapper;
import io.trustagent.eval.mapper.EvalQueryMapper;
import io.trustagent.eval.mapper.EvalRunMapper;
import io.trustagent.eval.mapper.EvalRunRow;
import io.trustagent.eval.queue.EvalJobMessage;
import io.trustagent.eval.queue.EvalJobPublisher;
import io.trustagent.idempotency.IdempotencyService;
import io.trustagent.ratelimit.RateLimitService;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import io.trustagent.securityevent.SecurityEventService;
import io.trustagent.securityevent.SecurityEventType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 评测任务的创建与回调处理。控制面在评测链路里的**全部职责**都在这里。
 *
 * <h3>控制面不执行评测</h3>
 * 执行面（Python）才有 LLM 与工具。控制面做的是：下发任务、收结果、独立重算指标、留痕。
 * 这个分工让"评测结果"具备可审计性——控制面的指标不是执行面报什么就存什么，
 * 而是从逐条结果自己算一遍再比对。
 *
 * <h3>事务边界（这里有一处容易写错的地方）</h3>
 * {@code createRun} 刻意<b>不加 {@code @Transactional}</b>，而是显式用 {@link TransactionTemplate}
 * 把"建任务"圈起来，然后把"入队"放在事务**之外**。原因是：
 * <ol>
 *   <li>如果在事务内入队，事务回滚时消息已经在 Redis 里了，Worker 会去跑一个不存在的任务；</li>
 *   <li>依赖 {@code @Transactional} 的同类自调用（{@code this.xxx()}）不经过代理，
 *       事务根本不会生效——这是个很隐蔽的坑，显式写 TransactionTemplate 反而更不容易错。</li>
 * </ol>
 * 入队失败时会把任务标成 {@code FAILED} 并<b>释放幂等键</b>，让客户端能用同一个 Key 重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

    public static final String IDEMPOTENCY_SCOPE = "eval:run";

    /** 单条结果明细落库的最大长度（与 tool_call_record 保持一致）。 */
    private static final int DETAIL_JSON_MAX_LENGTH = 2000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;
    /** detected_categories 列宽 512。 */
    private static final int DETECTED_CATEGORIES_MAX_LENGTH = 512;

    private final EvalRunMapper runMapper;
    private final EvalCaseResultMapper resultMapper;
    private final EvalCaseMapper caseMapper;
    private final EvalQueryMapper queryMapper;
    private final EvalDatasetService datasetService;
    private final AgentAppService agentAppService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final EvalJobPublisher jobPublisher;
    private final SecurityEventService securityEventService;
    private final TrustAgentProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------ 创建任务

    /**
     * 创建评测任务。需要 {@code Idempotency-Key}。
     *
     * <p>流程：幂等占用 → （事务）建任务 + 回填幂等 + 审计 → 入队 → 返回。
     * 详见类注释里对事务边界的说明。
     */
    public EvalDtos.RunCreateResponse createRun(EvalDtos.RunCreateRequest request, String idempotencyKey) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();

        // 1) 幂等：先占坑再干活，避免两个并发请求各建一个任务
        IdempotencyService.Outcome outcome =
                idempotencyService.acquire(IDEMPOTENCY_SCOPE, idempotencyKey, fingerprint(request));
        if (outcome.replay()) {
            return replayResponse(outcome);
        }

        // 2) 校验资源与权限（放在幂等之后：重复请求不应该再触发一次昂贵的权限查询）
        AgentApp app = agentAppService.requireAccessible(request.appId());
        EvalDataset dataset = datasetService.requireAccessible(request.datasetId());
        rateLimitService.enforce("eval-run", String.valueOf(principal.getId()),
                rateLimitService.evalRunWindow());

        long caseCount = caseMapper.countByDatasetId(dataset.getId());
        if (caseCount <= 0) {
            idempotencyService.release(IDEMPOTENCY_SCOPE, idempotencyKey);
            throw BizException.badRequest("评测集 " + dataset.getId() + " 没有任何用例，无法发起任务");
        }
        int maxCases = props.getEval().getMaxCasesPerDataset();
        if (caseCount > maxCases) {
            idempotencyService.release(IDEMPOTENCY_SCOPE, idempotencyKey);
            throw BizException.badRequest("评测集用例数 " + caseCount + " 超过上限 " + maxCases
                    + "，请拆分数据集后重试");
        }

        int rounds = EvalVocabulary.roundCount(request.mode());
        int totalCases = Math.toIntExact(caseCount * rounds);
        String traceId = TraceContext.currentTraceId();

        // 3) 建任务（独立事务，必须提交后才允许入队）
        EvalRun run = transactionTemplate.execute(status -> {
            EvalRun created = new EvalRun();
            created.setDatasetId(dataset.getId());
            created.setAppId(app.getId());
            created.setOwnerId(principal.getId());
            created.setMode(request.mode());
            created.setStatus(EvalVocabulary.STATUS_PENDING);
            created.setTotalCases(totalCases);
            created.setCompletedCases(0);
            created.setFailedCases(0);
            created.setAttempts(0);
            created.setIdempotencyKey(idempotencyKey);
            created.setTraceId(traceId);
            runMapper.insert(created);

            idempotencyService.complete(IDEMPOTENCY_SCOPE, idempotencyKey, "eval_run", created.getId());
            auditService.record("EVAL_RUN_CREATED", "eval_run", String.valueOf(created.getId()), Map.of(
                    "datasetId", dataset.getId(),
                    "appId", app.getId(),
                    "mode", request.mode(),
                    "totalCases", totalCases));
            return created;
        });
        if (run == null) {
            // 理论上不可达：TransactionTemplate 返回 null 说明回调没被执行
            throw new BizException(ErrorCode.INTERNAL_ERROR, "创建评测任务失败（事务未返回结果）");
        }

        // 4) 入队（事务外）。失败则作废这次尝试，允许客户端用同一 Key 重试。
        try {
            jobPublisher.publish(new EvalJobMessage(run.getId(), dataset.getId(), app.getId(),
                    request.mode(), 1, traceId, Instant.now().toEpochMilli()));
        } catch (RuntimeException ex) {
            markFailedQuietly(run.getId(), "评测任务入队失败: " + ex.getMessage());
            idempotencyService.release(IDEMPOTENCY_SCOPE, idempotencyKey);
            throw new BizException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "评测队列不可用，任务已作废（请检查 Redis 或改用默认部署形态）");
        }

        log.info("评测任务已创建 runId={} datasetId={} appId={} mode={} totalCases={} owner={}",
                run.getId(), dataset.getId(), app.getId(), request.mode(), totalCases,
                principal.getUsername());
        return new EvalDtos.RunCreateResponse(run.getId(), run.getStatus(), totalCases, false);
    }

    private EvalDtos.RunCreateResponse replayResponse(IdempotencyService.Outcome outcome) {
        if (outcome.resourceId() == null) {
            // 上一次请求还在处理中（IN_PROGRESS 且未回填）：不能返回一个空 id 让前端去轮询
            throw new BizException(ErrorCode.CONFLICT,
                    "该 Idempotency-Key 的请求仍在处理中，请稍后重试或更换 Key");
        }
        EvalRun existing = runMapper.selectById(outcome.resourceId());
        if (existing == null) {
            throw new BizException(ErrorCode.CONFLICT,
                    "幂等记录指向的任务已不存在（runId=" + outcome.resourceId() + "）");
        }
        log.info("评测任务幂等重放 runId={} status={}", existing.getId(), existing.getStatus());
        return new EvalDtos.RunCreateResponse(existing.getId(), existing.getStatus(),
                nz(existing.getTotalCases()), true);
    }

    private String fingerprint(EvalDtos.RunCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            // 序列化失败时退化为字段拼接：仍然能区分不同请求，只是可读性差一点
            return request.datasetId() + "|" + request.appId() + "|" + request.mode();
        }
    }

    // ------------------------------------------------------------------ Worker 回调

    /**
     * 处理 Worker 回调（{@code CASE_RESULTS} / {@code COMPLETED} / {@code FAILED}）。
     *
     * <h3>幂等策略</h3>
     * 靠唯一索引 {@code (run_id, case_id, guard_enabled)} 兜底：先查已存在的键做批量跳过，
     * 插入时再捕获 {@link DuplicateKeyException} 作为并发兜底。两层都要有——
     * 只靠查询挡不住"两个 Worker 同时提交同一批"，只靠唯一键则会把"整批重复"报成异常。
     */
    public InternalEvalDtos.CallbackResponse handleCallback(InternalEvalDtos.CallbackRequest request) {
        EvalRun run = runMapper.selectById(request.runId());
        if (run == null) {
            throw BizException.notFound("评测任务不存在: " + request.runId());
        }
        String type = request.type() == null ? "" : request.type().toUpperCase();

        // 状态守卫（第一道）：任务已到终态时，任何回调都不能再改写它。
        //
        // 为什么必须在入口就拦：CASE_RESULTS 分支会把 status 置回 RUNNING，
        // 而 Redis Streams 是 at-least-once——一条早先发出、网络里滞留的回调完全可能在
        // COMPLETED 之后才到达。若不拦，已 SUCCEEDED 的任务会被改回 RUNNING、
        // 计数被继续累加，任务从此永不终态（前端进度条永远转圈，报告也导不出来）。
        //
        // 这里**不抛异常**：抛 4xx/5xx 会让 Worker 认为回调失败并重投，
        // 形成"被拒 → 重投 → 又被拒"的循环。返回 200 + 明确的 accepted/skipped 语义，
        // 才是让调用方停下来的正确方式（COMPLETED 幂等成功，CASE_RESULTS 明确跳过）。
        if (EvalVocabulary.isTerminal(run.getStatus())) {
            return terminalResponse(run, type, request);
        }

        // 投递次数守卫：attempt 单调不回退。
        //
        // attempt 来自 Redis Streams 的 delivery count。XAUTOCLAIM 重投、Worker 重启后重放，
        // 都可能让一个**较小**的 attempt 在较晚的时间到达。若照单全收，计数会来回跳动
        // （3 → 1 → 2），"投递了几次"这个用于判断是否该进死信的依据就失效了。
        // 因此低于当前值的回调只记 skipped，不写库。
        if (isStaleAttempt(run, request.attempt())) {
            log.warn("回调 attempt 回退，已忽略 runId={} currentAttempts={} incomingAttempt={} type={}",
                    run.getId(), run.getAttempts(), request.attempt(), type);
            return new InternalEvalDtos.CallbackResponse(0, 0, run.getStatus());
        }

        return switch (type) {
            case "CASE_RESULTS" -> handleCaseResults(run, request);
            case "COMPLETED" -> handleCompleted(run, request);
            case "FAILED" -> handleFailed(run, request);
            default -> throw BizException.badRequest(
                    "未知的 type: " + request.type() + "，可选值: CASE_RESULTS/COMPLETED/FAILED");
        };
    }

    /**
     * 终态任务收到回调时的幂等应答。
     *
     * <p>三类回调用同一个出口，但语义各有侧重，注释写清楚是为了避免后来者"顺手统一"成抛异常：
     * <ul>
     *   <li>{@code COMPLETED}：**幂等成功**（返回当前状态），Worker 侧视为已完成，不再重投；</li>
     *   <li>{@code CASE_RESULTS}：**已结束，整批跳过**（accepted=0、skipped=本批条数）；</li>
     *   <li>{@code FAILED}：同样幂等成功——任务已经以别的理由结束后，迟到的失败回调不该再改动它。</li>
     * </ul>
     * <p>重复到达的 COMPLETED 也走这里，所以指标不会被重算覆盖：指标快照是"当时那一版"，
     * 重算会让报告里的数字与任务详情里的数字对不上。
     */
    private InternalEvalDtos.CallbackResponse terminalResponse(EvalRun run, String type,
                                                               InternalEvalDtos.CallbackRequest request) {
        int skipped = "CASE_RESULTS".equals(type) && request.results() != null
                ? request.results().size() : 0;
        log.info("任务已处于终态，回调被忽略（幂等返回）runId={} status={} type={} skipped={}",
                run.getId(), run.getStatus(), type, skipped);
        return new InternalEvalDtos.CallbackResponse(0, skipped, run.getStatus());
    }

    /** attempt 是否低于已记录值（低于即为过期回调）。attempt 为空时视为"未提供"，不做判断。 */
    private static boolean isStaleAttempt(EvalRun run, Integer incomingAttempt) {
        return incomingAttempt != null && incomingAttempt < nz(run.getAttempts());
    }

    private InternalEvalDtos.CallbackResponse handleCaseResults(EvalRun run,
                                                                InternalEvalDtos.CallbackRequest request) {
        List<InternalEvalDtos.CaseResultPayload> payloads = request.results() == null
                ? List.of() : request.results();

        if (EvalVocabulary.isTerminal(run.getStatus())) {
            // 任务已经结束（例如超时后被判定失败），迟到的结果不再写入，避免"终态又被改回 RUNNING"
            log.warn("任务已处于终态，忽略迟到的用例结果 runId={} status={} incoming={}",
                    run.getId(), run.getStatus(), payloads.size());
            return new InternalEvalDtos.CallbackResponse(0, payloads.size(), run.getStatus());
        }

        // 先校验载荷，再抢状态，最后才写结果行。
        //
        // 顺序是有讲究的：**守卫必须排在写入之前**。若先插结果行再检查状态，
        // 一个迟到的批次会把行写进已经结束的任务里，随后守卫拦下状态推进——
        // 结果是"任务已 SUCCEEDED，却又多出一批结果行"，两边说的不是同一件事。
        // 先抢状态则最坏情况只是"结果行没写进去"，而这种情况会在响应里如实体现为 skipped。
        for (InternalEvalDtos.CaseResultPayload payload : payloads) {
            validatePayload(payload);
        }

        // 状态推进与 attempt 更新：**单条原子更新**，把"判断 + 写入"交给数据库一次完成。
        //
        // 旧写法（run.setStatus(RUNNING) + run.setCompletedCases(旧值+accepted) + updateById）
        // 有三个问题：
        //   1) 并发回调各自基于同一份旧快照写回 → 丢失更新，进度少算；
        //   2) 完全没有状态守卫 → 已 SUCCEEDED 的任务会被改回 RUNNING，从此永不终态；
        //   3) 先查后写 → 两个 Worker 同时进来时都会认为自己该写。
        // 现在 completed_cases 由 SQL 按 eval_case_result 的实际行数结算（结果行数才是进度的
        // 唯一事实来源，进程在"插入行"与"推进进度"之间被 kill 也能在重投后自愈），
        // attempts 用 SQL 表达"只增不减"，终态由 where 子句守卫。
        int affected = runMapper.markRunning(run.getId(), request.attempt(), now());
        if (affected == 0) {
            // 守卫拦下了：任务在我们读到它之后、写之前变成了终态。
            // 返回 200 + accepted=0 + 整批 skipped，而不是抛异常——
            // 抛异常会让 Worker 把"任务已结束"当成可重试故障，反复重投一批永远不会被接受的批次。
            EvalRun latest = runMapper.selectById(run.getId());
            String latestStatus = latest == null ? run.getStatus() : latest.getStatus();
            log.warn("任务已进入终态，用例结果未被接收 runId={} status={} attempted={}",
                    run.getId(), latestStatus, payloads.size());
            return new InternalEvalDtos.CallbackResponse(0, payloads.size(), latestStatus);
        }

        Set<String> existing = existingKeys(run.getId());
        int accepted = 0;
        int skipped = 0;

        for (InternalEvalDtos.CaseResultPayload payload : payloads) {
            String key = resultKey(payload.caseId(), Boolean.TRUE.equals(payload.guardEnabled()));
            if (existing.contains(key)) {
                skipped++;
                continue;
            }
            EvalCaseResult row = toRow(run.getId(), payload);
            try {
                resultMapper.insert(row);
                existing.add(key);
                accepted++;
            } catch (DuplicateKeyException ex) {
                // 并发兜底：另一个 Worker 抢先写了同一条。
                // 只靠"先查后插"挡不住并发：两个 Worker 可能同时查到"不存在"，随后一先一后插入，
                // 唯一索引 uk_eval_result_run_case_guard 是最后一道防线。
                skipped++;
            }
        }

        // 进度结算：按 eval_case_result 的实际行数写回 completed_cases。
        // 放在写结果行之后做，这样响应里的进度是实时的；
        // 即便这里失败，重投时也会由同一条语句补齐（行数才是事实来源）。
        if (accepted > 0) {
            runMapper.syncCompletedCases(run.getId());
        }

        EvalRun updated = runMapper.selectById(run.getId());
        String status = updated == null ? EvalVocabulary.STATUS_RUNNING : updated.getStatus();
        int completedCases = updated == null ? nz(run.getCompletedCases()) + accepted
                : nz(updated.getCompletedCases());
        log.info("评测结果批次已接收 runId={} accepted={} skipped={} completedCases={}/{}",
                run.getId(), accepted, skipped, completedCases, run.getTotalCases());
        return new InternalEvalDtos.CallbackResponse(accepted, skipped, status);
    }

    private InternalEvalDtos.CallbackResponse handleCompleted(EvalRun run,
                                                              InternalEvalDtos.CallbackRequest request) {
        if (EvalVocabulary.isTerminal(run.getStatus())) {
            // 重复到达的 COMPLETED：直接认为成功，不再重算指标（重算会覆盖掉已快照的数值）
            log.info("任务已完成，忽略重复的 COMPLETED 回调 runId={} status={}", run.getId(), run.getStatus());
            return new InternalEvalDtos.CallbackResponse(0, 0, run.getStatus());
        }

        List<EvalCaseResult> results = resultMapper.selectByRunId(run.getId());
        if (results.isEmpty()) {
            markFailedQuietly(run.getId(), "Worker 宣告完成，但没有任何用例结果回传");
            return new InternalEvalDtos.CallbackResponse(0, 0, EvalVocabulary.STATUS_FAILED);
        }

        // 1) 控制面独立重算
        Set<String> sensitiveCaseIds = datasetService.sensitiveCaseIds(run.getDatasetId());
        EvalMetricsCalculator.Metrics guardOnMetrics =
                EvalMetricsCalculator.compute(outcomesOf(results, true), sensitiveCaseIds);
        EvalMetricsCalculator.Metrics guardOffMetrics =
                EvalMetricsCalculator.compute(outcomesOf(results, false), sensitiveCaseIds);

        // 2) 与执行面上报值比对（只比"这一轮实际跑过的模式"）
        List<String> mismatches = new ArrayList<>();
        if (EvalVocabulary.needsGuardOnRound(run.getMode())) {
            Map<String, Object> reported =
                    EvalMetricsCalculator.extractModeMetrics(request.metrics(), "guardOn");
            mismatches.addAll(prefix("guardOn",
                    EvalMetricsCalculator.mismatchedKeys(reported, guardOnMetrics)));
        }
        if (EvalVocabulary.needsGuardOffRound(run.getMode())) {
            Map<String, Object> reported =
                    EvalMetricsCalculator.extractModeMetrics(request.metrics(), "guardOff");
            mismatches.addAll(prefix("guardOff",
                    EvalMetricsCalculator.mismatchedKeys(reported, guardOffMetrics)));
        }
        if (!mismatches.isEmpty()) {
            recordMetricsMismatch(run, mismatches);
        }

        // 3) 落库（以控制面重算值为准）——单条原子更新 + 终态守卫
        //
        // 与 CASE_RESULTS 同样的理由：不能"读-改-写"，否则一个并发的用例结果批次
        // 可能在此之前把 status 改成 RUNNING，而这里凭旧快照写回的内容会把它的进度抹掉。
        int total = nz(run.getTotalCases());
        String partialError = results.size() < total
                // 不谎报成功：数字全在，但"少了用例"这件事必须写在可见字段里
                ? truncateError("任务提前结束：已回传 " + results.size() + "/" + total
                        + " 条结果，可能有用例未被处理（请检查 Worker 日志与死信队列）")
                : null;
        int failedCases = countExecutionFailures(results);
        int affected = runMapper.complete(run.getId(),
                toJson(EvalVocabulary.needsGuardOnRound(run.getMode()) ? guardOnMetrics : null),
                toJson(EvalVocabulary.needsGuardOffRound(run.getMode()) ? guardOffMetrics : null),
                results.size(), failedCases, request.attempt(), partialError, now());
        if (affected == 0) {
            // 重复或迟到的 COMPLETED：任务已经不是"未结束"状态了。
            // 直接返回当前状态（幂等成功），**不重算、不覆盖**已快照的指标。
            EvalRun latest = runMapper.selectById(run.getId());
            String latestStatus = latest == null ? run.getStatus() : latest.getStatus();
            log.info("任务已处于终态，忽略重复的 COMPLETED 回调 runId={} status={}",
                    run.getId(), latestStatus);
            return new InternalEvalDtos.CallbackResponse(0, 0, latestStatus);
        }

        auditService.record("EVAL_RUN_COMPLETED", "eval_run", String.valueOf(run.getId()), Map.of(
                "mode", run.getMode(),
                "results", results.size(),
                "failedCases", failedCases,
                "metricsMismatch", mismatches.isEmpty() ? "none" : String.join("; ", mismatches)));

        log.info("评测任务完成 runId={} results={} failedCases={} metricsMismatch={}",
                run.getId(), results.size(), failedCases,
                mismatches.isEmpty() ? "none" : mismatches.size() + " keys");
        return new InternalEvalDtos.CallbackResponse(0, 0, EvalVocabulary.STATUS_SUCCEEDED);
    }

    private InternalEvalDtos.CallbackResponse handleFailed(EvalRun run,
                                                           InternalEvalDtos.CallbackRequest request) {
        if (EvalVocabulary.isTerminal(run.getStatus())) {
            return new InternalEvalDtos.CallbackResponse(0, 0, run.getStatus());
        }
        // 条件更新：FAILED 也是终态，同样带守卫。
        // 迟到的 FAILED（例如 Worker 在回调成功后才超时判定失败）不会把已 SUCCEEDED 的任务改回 FAILED——
        // 先到的那种终态就是事实，后到的只记日志。
        String reason = truncateError(request.errorMessage() == null
                ? "Worker 报告任务失败（未提供原因）" : request.errorMessage());
        int affected = runMapper.fail(run.getId(), reason, request.attempt(), now());
        if (affected == 0) {
            EvalRun latest = runMapper.selectById(run.getId());
            String latestStatus = latest == null ? run.getStatus() : latest.getStatus();
            log.warn("任务已处于终态，忽略 FAILED 回调 runId={} status={} reason={}",
                    run.getId(), latestStatus, reason);
            return new InternalEvalDtos.CallbackResponse(0, 0, latestStatus);
        }

        auditService.record("EVAL_RUN_FAILED", "eval_run", String.valueOf(run.getId()),
                Map.of("reason", String.valueOf(reason)));
        log.warn("评测任务失败 runId={} reason={}", run.getId(), reason);
        return new InternalEvalDtos.CallbackResponse(0, 0, EvalVocabulary.STATUS_FAILED);
    }

    // ------------------------------------------------------------------ Worker 拉取

    /**
     * 内部接口：Worker 拉取"这一次任务该怎么跑"。
     *
     * <p>两种模式下发不同的执行参数，这个差异是**方法论的核心**，必须写清楚：
     * <ul>
     *   <li>{@code GUARD_ON}：完全用应用中配置的策略与工具白名单；</li>
     *   <li>{@code GUARD_OFF}（基线）：{@code guardEnabled=false}、取消工具白名单
     *       （{@link ToolCatalog#all()}）、关闭副作用确认与敏感参数规则。
     *       基线要回答的问题是"同一个模型、同样的输入，在没有安全控制时会发生什么"，
     *       因此不能同时把工具拿走——那样测出来的只是"没有工具所以没出事"。</li>
     * </ul>
     */
    public InternalEvalDtos.ExecConfigResponse execConfig(Long runId, String round) {
        EvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("评测任务不存在: " + runId);
        }
        AgentApp app = agentAppService.getByIdOrThrow(run.getAppId());
        SecurityPolicy policy = agentAppService.getPolicyOrThrow(run.getAppId());
        boolean guardOn = resolveGuardOn(run, round);

        List<String> allowedTools;
        Map<String, String> toolSchemas;
        InternalEvalDtos.PolicyPayload policyPayload;

        if (guardOn) {
            allowedTools = agentAppService.enabledToolNames(run.getAppId());
            toolSchemas = agentAppService.toolSchemas(run.getAppId());
            policyPayload = new InternalEvalDtos.PolicyPayload(
                    Boolean.TRUE.equals(policy.getGuardEnabled()),
                    nz(policy.getMaxToolCalls(), 3),
                    nz(policy.getMaxInputLength(), 4000),
                    nz(policy.getTaskTimeoutMs(), 15000),
                    splitCsv(policy.getDomainWhitelist()),
                    splitCsv(policy.getDeniedParamRules()),
                    Boolean.TRUE.equals(policy.getConfirmSideEffects()),
                    Boolean.TRUE.equals(policy.getInjectionDetectionEnabled()),
                    Boolean.TRUE.equals(policy.getPiiMaskingEnabled()),
                    Boolean.TRUE.equals(policy.getOutputGuardEnabled()));
        } else {
            allowedTools = ToolCatalog.all();
            toolSchemas = Map.of();
            policyPayload = new InternalEvalDtos.PolicyPayload(
                    false,
                    nz(policy.getMaxToolCalls(), 3),
                    nz(policy.getMaxInputLength(), 4000),
                    nz(policy.getTaskTimeoutMs(), 15000),
                    List.of(), List.of(), false, false, false, false);
        }

        return new InternalEvalDtos.ExecConfigResponse(run.getId(), run.getMode(),
                guardOn ? EvalVocabulary.MODE_GUARD_ON : EvalVocabulary.MODE_GUARD_OFF,
                app.getId(), app.getCode(), app.getSystemPrompt(), allowedTools, toolSchemas,
                policyPayload,
                new InternalEvalDtos.RunLimits(props.getEval().getMaxCasesPerDataset(),
                        props.getEval().getJobTimeoutSeconds(), props.getEval().getMaxDeliveries()));
    }

    /**
     * 决定这一份配置下发"防护开"还是"防护关"的参数。
     *
     * <p>不传 {@code round} 时保持原来的推导（兼容旧调用方）；传了就以传入为准，
     * 因为 {@code BOTH} 模式必须能分别拉到两轮的配置。
     *
     * <p>同时校验"要的轮次必须在该任务的模式里"：如果对一个只有 {@code GUARD_OFF} 的任务要
     * {@code GUARD_ON} 的配置，那是 Worker 的 bug，直接 400 比返回一份语义不对的配置更好——
     * 后者会让结果被错误地标成 {@code guardEnabled=true}，静默污染整个对照组。
     */
    private static boolean resolveGuardOn(EvalRun run, String round) {
        if (round == null || round.isBlank()) {
            return EvalVocabulary.needsGuardOnRound(run.getMode())
                    || !EvalVocabulary.needsGuardOffRound(run.getMode());
        }
        String normalized = round.trim().toUpperCase();
        if (EvalVocabulary.MODE_GUARD_OFF.equals(normalized)) {
            if (!EvalVocabulary.needsGuardOffRound(run.getMode())) {
                throw BizException.badRequest("任务 " + run.getId() + " 的模式 " + run.getMode()
                        + " 不包含 GUARD_OFF 轮次");
            }
            return false;
        }
        if (EvalVocabulary.MODE_GUARD_ON.equals(normalized)) {
            if (!EvalVocabulary.needsGuardOnRound(run.getMode())) {
                throw BizException.badRequest("任务 " + run.getId() + " 的模式 " + run.getMode()
                        + " 不包含 GUARD_ON 轮次");
            }
            return true;
        }
        throw BizException.badRequest("round 只能是 GUARD_ON 或 GUARD_OFF，收到: " + round);
    }

    // ------------------------------------------------------------------ 查询

    public List<EvalDtos.RunView> listRuns(String status) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean seeAll = principal.isAdmin() || principal.getRoles().contains("AUDITOR");
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return queryMapper.selectRunRows(principal.getId(), seeAll ? 1 : 0, normalized).stream()
                .map(this::toRunView)
                .toList();
    }

    public EvalDtos.RunView runDetail(Long runId) {
        EvalRun run = requireAccessibleRun(runId);
        return toRunView(run);
    }

    /** 读取任务并校验可见性（ADMIN / AUDITOR 可见全部，其余仅属主）。 */
    public EvalRun requireAccessibleRun(Long runId) {
        EvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.notFound("评测任务不存在: " + runId);
        }
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean readOnlyAllowed = principal.getRoles().contains("AUDITOR");
        if (!principal.isAdmin() && !readOnlyAllowed && !principal.getId().equals(run.getOwnerId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该评测任务（非属主）");
        }
        return run;
    }

    public PageResult<EvalDtos.CaseResultView> listResults(Long runId, String category, Boolean passed,
                                                           long page, long size) {
        requireAccessibleRun(runId);
        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<EvalCaseResult> query = Wrappers.<EvalCaseResult>lambdaQuery()
                .eq(EvalCaseResult::getRunId, runId)
                .eq(category != null && !category.isBlank(), EvalCaseResult::getCategory, category)
                .eq(passed != null, EvalCaseResult::getPassed, passed)
                .orderByAsc(EvalCaseResult::getId);
        IPage<EvalCaseResult> result = resultMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), query);
        return PageResult.of(result.getRecords().stream().map(this::toCaseResultView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 供报告导出：一次取全量结果（报告必须完整，分页导出的报告没有意义）。 */
    public List<EvalCaseResult> allResultsForReport(Long runId) {
        requireAccessibleRun(runId);
        return resultMapper.selectByRunId(runId);
    }

    // ------------------------------------------------------------------ 内部工具方法

    private Set<String> existingKeys(Long runId) {
        Set<String> keys = new HashSet<>();
        for (EvalCaseResult row : resultMapper.selectByRunId(runId)) {
            keys.add(resultKey(row.getCaseId(), Boolean.TRUE.equals(row.getGuardEnabled())));
        }
        return keys;
    }

    private static String resultKey(String caseId, boolean guardEnabled) {
        return caseId + "#" + guardEnabled;
    }

    /**
     * 校验回调里的枚举字段。
     *
     * <p>这里**故意选择抛 400 而不是跳过**：Worker 的类别与动作都来自控制面下发的数据，
     * 出现未知值说明两侧定义已经漂移。跳过只会让漂移被埋在一批"成功"里，
     * 抛出来则会立刻暴露——宁可任务失败，也不要一份口径可疑的指标。
     */
    private void validatePayload(InternalEvalDtos.CaseResultPayload payload) {
        if (!EvalVocabulary.categoryExists(payload.category())) {
            throw BizException.badRequest("未知 category: " + payload.category()
                    + "（执行面与控制面的类别定义可能已漂移）");
        }
        if (payload.expectedAction() != null && !EvalVocabulary.actionExists(payload.expectedAction())) {
            throw BizException.badRequest("未知 expectedAction: " + payload.expectedAction());
        }
        if (payload.actualAction() != null && !EvalVocabulary.actionExists(payload.actualAction())) {
            throw BizException.badRequest("未知 actualAction: " + payload.actualAction());
        }
    }

    private EvalCaseResult toRow(Long runId, InternalEvalDtos.CaseResultPayload payload) {
        EvalCaseResult row = new EvalCaseResult();
        row.setRunId(runId);
        row.setCaseId(payload.caseId());
        row.setCategory(payload.category());
        row.setGuardEnabled(Boolean.TRUE.equals(payload.guardEnabled()));
        row.setExpectedAction(payload.expectedAction());
        row.setActualAction(payload.actualAction());
        row.setExpectedTool(payload.expectedTool());
        row.setActualTool(payload.actualTool());
        row.setBlocked(Boolean.TRUE.equals(payload.blocked()));
        row.setDetectedCategories(joinDetected(payload.detectedCategories()));
        row.setPiiLeaked(Boolean.TRUE.equals(payload.piiLeaked()));
        row.setPassed(Boolean.TRUE.equals(payload.passed()));
        row.setLatencyMs(payload.latencyMs() == null ? 0 : payload.latencyMs());
        // 明细里可能含输出预览，统一走"落库即脱敏 + 截断"，与工具调用记录同一条规则
        row.setDetailJson(payload.detail() == null ? null
                : SensitiveDataMasker.preview(toJson(payload.detail()), DETAIL_JSON_MAX_LENGTH));
        row.setErrorMessage(truncateError(payload.errorMessage()));
        return row;
    }

    private static String joinDetected(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        String joined = String.join(",", categories);
        return joined.length() <= DETECTED_CATEGORIES_MAX_LENGTH ? joined
                : joined.substring(0, DETECTED_CATEGORIES_MAX_LENGTH);
    }

    /**
     * 把逐条结果转成指标计算输入。
     *
     * <p>{@code actualAction} 缺失时归一化为 {@code "UNKNOWN"} 而不是 {@code "ALLOW"}：
     * 前者在"本该拦住"的用例上会被算作穿透（asr 上升），是**保守**的；
     * 后者则等于假装"这条被安全放行"，会低估风险。安全指标宁可偏保守。
     */
    private static List<EvalMetricsCalculator.CaseOutcome> outcomesOf(List<EvalCaseResult> results,
                                                                     boolean guardEnabled) {
        List<EvalMetricsCalculator.CaseOutcome> list = new ArrayList<>();
        for (EvalCaseResult row : results) {
            if (Boolean.TRUE.equals(row.getGuardEnabled()) != guardEnabled) {
                continue;
            }
            list.add(new EvalMetricsCalculator.CaseOutcome(
                    row.getCaseId(),
                    row.getCategory(),
                    row.getExpectedAction(),
                    row.getActualAction() == null ? "UNKNOWN" : row.getActualAction(),
                    row.getExpectedTool(),
                    row.getActualTool(),
                    Boolean.TRUE.equals(row.getBlocked()),
                    Boolean.TRUE.equals(row.getPiiLeaked()),
                    Boolean.TRUE.equals(row.getPassed()),
                    row.getLatencyMs() == null ? 0 : row.getLatencyMs()));
        }
        return list;
    }

    /** 执行失败数 = 带 errorMessage 的结果行数（不是"没通过断言"的行数）。 */
    private static int countExecutionFailures(List<EvalCaseResult> results) {
        int failed = 0;
        for (EvalCaseResult row : results) {
            if (row.getErrorMessage() != null && !row.getErrorMessage().isBlank()) {
                failed++;
            }
        }
        return failed;
    }

    private static List<String> prefix(String prefix, List<String> values) {
        List<String> prefixed = new ArrayList<>(values.size());
        for (String value : values) {
            prefixed.add(prefix + "." + value);
        }
        return prefixed;
    }

    private void recordMetricsMismatch(EvalRun run, List<String> mismatches) {
        String detail = String.join("; ", mismatches);
        securityEventService.record(run.getTraceId(), run.getAppId(),
                SecurityEventType.METRICS_MISMATCH, "eval_metrics_recompute",
                "METRICS_MISMATCH", detail, "BLOCK");
        log.warn("指标一致性校验不通过 runId={} 差异={}", run.getId(), detail);
    }

    /**
     * 兜底地把任务标记为失败。
     *
     * <p>调用点都在**收尾路径**上（入队失败、Worker 宣告完成却没有结果），因此这里：
     * 不抛异常（否则会掩盖掉真正的原始错误）、用条件更新（不覆盖已经到达的终态）。
     */
    private void markFailedQuietly(Long runId, String reason) {
        try {
            // 单条原子更新：affected == 0 说明任务不存在或已处于终态，两种情况都不需要再做什么
            runMapper.fail(runId, truncateError(reason), null, now());
        } catch (RuntimeException ex) {
            // 兜底路径本身不能再抛异常，否则会掩盖掉原始错误
            log.error("标记评测任务失败时又出错 runId={} err={}", runId, ex.getMessage());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("序列化指标/明细失败: {}", ex.getMessage());
            return null;
        }
    }

    private static String truncateError(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MESSAGE_MAX_LENGTH ? text
                : text.substring(0, ERROR_MESSAGE_MAX_LENGTH - 3) + "...";
    }

    private static int nz(Integer value) {
        return nz(value, 0);
    }

    private static int nz(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private EvalDtos.RunView toRunView(EvalRun run) {
        EvalDataset dataset = null;
        String datasetName = null;
        String appName = null;
        try {
            dataset = datasetService.getByIdOrThrow(run.getDatasetId());
            datasetName = dataset.getName();
        } catch (BizException ex) {
            // 数据集被删（删除时不级联删任务，保留历史事实），列表页仍然要能显示
            log.debug("评测任务引用的数据集已不存在 runId={}", run.getId());
        }
        try {
            appName = agentAppService.getByIdOrThrow(run.getAppId()).getName();
        } catch (BizException ex) {
            log.debug("评测任务引用的应用已不存在 runId={}", run.getId());
        }
        return new EvalDtos.RunView(run.getId(), run.getDatasetId(), datasetName, run.getAppId(), appName,
                run.getOwnerId(), run.getMode(), run.getStatus(), nz(run.getTotalCases()),
                nz(run.getCompletedCases()), nz(run.getFailedCases()), nz(run.getAttempts()),
                run.getErrorMessage(), run.getTraceId(), buildMetrics(run), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt());
    }

    private EvalDtos.RunView toRunView(EvalRunRow row) {
        return new EvalDtos.RunView(row.getId(), row.getDatasetId(), row.getDatasetName(), row.getAppId(),
                row.getAppName(), row.getOwnerId(), row.getMode(), row.getStatus(),
                nz(row.getTotalCases()), nz(row.getCompletedCases()), nz(row.getFailedCases()),
                nz(row.getAttempts()), row.getErrorMessage(), row.getTraceId(),
                null /* 列表页不返回指标，避免响应体膨胀 */, row.getCreatedAt(), row.getStartedAt(),
                row.getFinishedAt());
    }

    /**
     * 组装前端要的指标结构：{@code { "PAIRED": { "guardOn": {...}, "guardOff": {...} } }}。
     *
     * <p>没跑的那一轮不出现在结果里（全局 Jackson 配置会丢掉 null 值），
     * 这样前端可以用"有没有这个键"来判断该轮是否执行过，而不是去猜 0 值代表什么。
     */
    private Map<String, Object> buildMetrics(EvalRun run) {
        Map<String, Object> paired = new LinkedHashMap<>();
        if (EvalVocabulary.needsGuardOnRound(run.getMode())) {
            paired.put("guardOn", parseJsonMap(run.getGuardMetricsJson()));
        }
        if (EvalVocabulary.needsGuardOffRound(run.getMode())) {
            paired.put("guardOff", parseJsonMap(run.getBaselineMetricsJson()));
        }
        if (paired.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("PAIRED", paired);
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            log.warn("指标快照解析失败: {}", ex.getMessage());
            return null;
        }
    }

    private EvalDtos.CaseResultView toCaseResultView(EvalCaseResult row) {
        return new EvalDtos.CaseResultView(row.getCaseId(), row.getCategory(),
                Boolean.TRUE.equals(row.getGuardEnabled()), row.getExpectedAction(),
                row.getActualAction(), row.getExpectedTool(), row.getActualTool(),
                Boolean.TRUE.equals(row.getBlocked()), splitCsv(row.getDetectedCategories()),
                Boolean.TRUE.equals(row.getPiiLeaked()), Boolean.TRUE.equals(row.getPassed()),
                nz(row.getLatencyMs()), row.getDetailJson(), row.getErrorMessage());
    }
}
