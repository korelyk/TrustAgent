package io.trustagent.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.trustagent.audit.AuditLog;
import io.trustagent.audit.mapper.AuditLogMapper;
import io.trustagent.common.BizException;
import io.trustagent.common.PageResult;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import io.trustagent.securityevent.SecurityEvent;
import io.trustagent.securityevent.SecurityEventService;
import io.trustagent.trace.mapper.AgentTraceMapper;
import io.trustagent.trace.mapper.ToolCallRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trace 的写入与查询。
 *
 * <h3>事务边界：Trace 的生命周期必须独立于业务事务</h3>
 * {@link #createRunning} 与 {@link #markFailed} 都用 {@code REQUIRES_NEW} 独立提交，
 * 原因是这两个动作表达的是**事实**（"这次请求开始了" / "这次请求失败了"），
 * 而事实不应当因为业务事务回滚而消失：
 * <ul>
 *   <li>{@code ChatService.chat} 在 catch 里标记失败后会把业务异常继续抛出，整个业务事务随即回滚。
 *       如果 Trace 跟着回滚，用户收到 502 的同时数据库里**一条记录都没有**，
 *       {@code GET /api/traces/{traceId}} 只会返回 404——"执行面挂了"这件事在系统里查不到。</li>
 *   <li>两个方法必须**一起**独立：{@code markFailed} 是在另一个事务里读 Trace 行的，
 *       如果 {@code createRunning} 还留在业务事务里未提交，{@code markFailed} 会读到 null，
 *       随后 {@code return} 静默什么都不做——看起来加了独立事务，实际仍然丢记录。</li>
 * </ul>
 *
 * <p>代价：一次请求最多会同时占用两个数据库连接（业务事务 + 独立事务），
 * 连接池大小需要能容纳"并发请求数 × 2"；这是为了换取失败可追溯而明确接受的取舍。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final AgentTraceMapper traceMapper;
    private final ToolCallRecordMapper toolCallMapper;
    private final SecurityEventService securityEventService;
    private final AuditLogMapper auditLogMapper;

    /**
     * 落一条 RUNNING 的 Trace。**独立事务提交**：请求一开始就要在库里留下痕迹，
     * 这样后续无论成功还是失败都能找到它，也让 {@link #markFailed} 能在另一个事务里读到这一行。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTrace createRunning(Long appId, Long userId, String traceId, String inputPreview) {
        AgentTrace trace = new AgentTrace();
        trace.setTraceId(traceId);
        trace.setAppId(appId);
        trace.setUserId(userId);
        trace.setRequestId(traceId);
        trace.setInputPreview(inputPreview);
        trace.setStatus(AgentTrace.STATUS_RUNNING);
        trace.setToolCallCount(0);
        trace.setTotalLatencyMs(0);
        trace.setCreatedAt(LocalDateTime.now());
        traceMapper.insert(trace);
        return trace;
    }

    /**
     * 把 Trace 标记为 FAILED。**独立事务提交**，业务事务随后回滚也不会把它带走。
     *
     * <p>查不到对应的 Trace 时直接返回（不抛异常）：这个方法在失败路径上被调用，
     * 此时"记不上"本身不该再制造一个新的错误。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String traceId, String errorMessage, long latencyMs) {
        AgentTrace trace = traceMapper.selectByTraceId(traceId);
        if (trace == null) {
            return;
        }
        trace.setStatus(AgentTrace.STATUS_FAILED);
        trace.setErrorMessage(truncate(errorMessage, 500));
        trace.setTotalLatencyMs((int) latencyMs);
        traceMapper.updateById(trace);
    }

    public record TraceDetail(AgentTrace trace, List<ToolCallRecord> toolCalls, List<SecurityEvent> securityEvents,
                              List<AuditLog> auditLogs) {
    }

    public TraceDetail detail(String traceId) {
        AgentTrace trace = traceMapper.selectByTraceId(traceId);
        if (trace == null) {
            throw BizException.notFound("Trace 不存在: " + traceId);
        }
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean privileged = principal.isAdmin() || principal.getRoles().contains("AUDITOR");
        if (!privileged && !principal.getId().equals(trace.getUserId())) {
            throw BizException.forbidden("无权查看他人的 Trace");
        }
        List<AuditLog> auditLogs = auditLogMapper.selectList(
                Wrappers.<AuditLog>lambdaQuery().eq(AuditLog::getTraceId, traceId).orderByAsc(AuditLog::getSeq));
        return new TraceDetail(trace, toolCallMapper.selectByTraceId(traceId),
                securityEventService.byTraceId(traceId), auditLogs);
    }

    /**
     * Trace 列表。ADMIN / AUDITOR 可看全部，DEVELOPER 只能看自己的。
     *
     * <p>注意过滤条件是由**服务端**根据当前主体注入的，不接受前端传入的 {@code userId} 参数，
     * 否则越权者只要改一个 query 参数就能读到别人的记录。
     */
    public PageResult<AgentTrace> list(Long appId, long page, long size) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean privileged = principal.isAdmin() || principal.getRoles().contains("AUDITOR");
        long safeSize = Math.min(Math.max(size, 1), 200);

        LambdaQueryWrapper<AgentTrace> query = Wrappers.<AgentTrace>lambdaQuery()
                .eq(appId != null, AgentTrace::getAppId, appId)
                .eq(!privileged, AgentTrace::getUserId, principal.getId())
                .orderByDesc(AgentTrace::getId);
        IPage<AgentTrace> result = traceMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), query);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
