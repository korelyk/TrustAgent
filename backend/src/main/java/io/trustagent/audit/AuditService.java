package io.trustagent.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.audit.mapper.AuditLogMapper;
import io.trustagent.common.TraceContext;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入。
 *
 * <p>并发安全：{@code seq} 的分配是“先加进程内锁，再用唯一索引兜底”。
 * 单实例部署时锁能避免无谓重试；多实例部署时唯一索引 {@code uk_audit_log_seq} 保证不会出现重复 seq，
 * 冲突时重试重新取号（最多 3 次）。这是“乐观重试 + 数据库唯一约束”的典型组合，不依赖分布式锁。
 *
 * <p>事务边界：默认 {@link #record} 与业务写操作在同一个事务里提交。这样做的好处是
 * “业务成功才可能留下审计”，不会出现审计说成功、业务其实回滚了的假象；
 * 代价是业务回滚时审计也一起回滚。
 *
 * <p>但**失败路径恰恰需要相反的行为**：请求失败了，"失败"这件事本身就是最该被记住的事实。
 * 因此在同一个事务里写失败审计等于自相矛盾——业务一回滚，审计跟着消失，
 * 事后去查只会看到"什么都没发生过"。为此新增 {@link #recordIndependent}：
 * 它用 {@code REQUIRES_NEW} 开一个独立事务，业务事务随后回滚也不影响它已经提交的记录。
 * 成功路径保持 {@link #record} 不变（原设计：业务成功才留下 AGENT_CHAT_EXECUTED）。
 *
 * <p>更彻底的方案是本地消息表 / outbox（审计写入与投递解耦，进程崩溃也能补写），
 * 本项目不做——先让"失败留痕"这条路真正通，再谈可靠投递。
 */
@Slf4j
@Service
public class AuditService {

    private static final int MAX_SEQ_RETRY = 3;
    private static final String SYSTEM_ACTOR = "system";

    private final AuditLogMapper auditLogMapper;
    private final TrustAgentProperties props;
    private final ObjectMapper objectMapper;
    private final Object seqLock = new Object();

    public AuditService(AuditLogMapper auditLogMapper, TrustAgentProperties props, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入一条审计记录，**复用调用方的事务**。
     *
     * <p>用于成功路径：业务提交了，审计才可能出现。业务回滚时这条记录也会一起消失，
     * 这正是"审计说成功、业务其实回滚了"要避免的假象。
     */
    public void record(String action, String resourceType, String resourceId, Object detail) {
        doRecord(action, resourceType, resourceId, detail);
    }

    /**
     * 写入一条审计记录，**开启独立事务**（{@code REQUIRES_NEW}），供失败路径使用。
     *
     * <p>为什么需要它：{@code ChatService.chat} 是 {@code @Transactional} 的，
     * 它在 catch 里记完 {@code AGENT_CHAT_FAILED} 之后会 {@code throw} 让事务回滚。
     * 如果审计与业务同事务，这条"失败留痕"会和业务一起被回滚掉——
     * 结果就是：执行面挂了、用户拿到 502，而数据库里查不到任何痕迹。
     * 用独立事务写入，业务回滚不会再波及它。
     *
     * <p><b>seq 锁语义</b>：方法体里仍然走 {@link #doRecord}，
     * 也就是 {@code synchronized (seqLock)} 覆盖“取号 → 插入”全程，进程内不会有两个线程同时取号。
     * 与旧路径相比，独立事务把"插入到提交"之间的窗口从"外层业务事务提交时"（可能是秒级，
     * 中间还夹着 LLM 调用）压缩到"方法返回即提交"（微秒级），竞争窗口只会更小；
     * 跨进程/极端并发下仍由 {@code uk_audit_log_seq} 唯一索引 + 重试兜底。
     *
     * <p>注意：这里**刻意不吞异常**。审计不可用属于严重问题，
     * 调用方应当让它暴露出来（{@code ChatService} 只在失败路径调用本方法，
     * 那时业务本来就要以异常收场，不存在"审计把好请求搞坏"的情况）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String action, String resourceType, String resourceId, Object detail) {
        doRecord(action, resourceType, resourceId, detail);
    }

    /** 两条写入路径的公共实现：长度校验、取号、哈希链、唯一索引重试都在这里。 */
    private void doRecord(String action, String resourceType, String resourceId, Object detail) {
        if (!props.getAudit().isEnabled()) {
            return;
        }
        Actor actor = resolveActor();
        String detailJson = serialize(detail);
        long epochMs = Instant.now().toEpochMilli();
        LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);

        // 进程内锁：把“取号 → 插入”串起来，避免同进程的并发请求反复撞上唯一索引后重试。
        //
        // 这里原本**声明了 seqLock 却从未使用**（类注释承诺了“先加进程内锁”，代码里没有对应实现）。
        // 现在补上：文档说了什么，代码就应当做什么，否则下一个人会基于错误的假设去改并发逻辑。
        //
        // 必须说清它的边界——锁**不能**保证 seq 一定不冲突：
        // 事务提交发生在方法返回、锁释放之后（REQUIRES_NEW 由代理提交，REQUIRED 由外层业务提交），
        // 而 SELECT 读不到未提交的行，所以并发下仍可能取到同一个号。
        // 真正的正确性保证始终是唯一索引 uk_audit_log_seq + 下面的重试：锁只是降低重试概率。
        synchronized (seqLock) {
            for (int attempt = 1; attempt <= MAX_SEQ_RETRY; attempt++) {
                AuditLog last = auditLogMapper.selectLast();
                long seq = (last == null ? 0L : last.getSeq()) + 1L;
                String prevHash = (last == null ? AuditHashChain.GENESIS_HASH : last.getRecordHash());

                AuditLog entry = new AuditLog();
                entry.setSeq(seq);
                entry.setActorId(actor.id());
                entry.setActorUsername(actor.username());
                entry.setAction(action);
                entry.setResourceType(resourceType);
                entry.setResourceId(resourceId);
                entry.setDetailJson(detailJson);
                entry.setTraceId(TraceContext.currentTraceId());
                entry.setPrevHash(prevHash);
                entry.setCreatedAt(createdAt);
                entry.setCreatedAtEpochMs(epochMs);
                entry.setRecordHash(AuditHashChain.hash(seq, actor.id(), actor.username(), action,
                        resourceType, resourceId, detailJson, entry.getTraceId(), epochMs, prevHash));
                try {
                    auditLogMapper.insert(entry);
                    return;
                } catch (DuplicateKeyException ex) {
                    log.warn("审计 seq 冲突，重试 attempt={} seq={}", attempt, seq);
                }
            }
        }
        // 审计不可用属于严重问题：宁可让业务失败，也不能“静默丢掉留痕”
        throw new IllegalStateException("审计记录写入失败：连续 " + MAX_SEQ_RETRY + " 次 seq 冲突");
    }

    private Actor resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new Actor(null, SYSTEM_ACTOR);
        }
        if (auth.getPrincipal() instanceof UserPrincipal principal) {
            return new Actor(principal.getId(), principal.getUsername());
        }
        if (SecurityUtils.currentPrincipal().isEmpty()) {
            // 内部服务调用：principal 是字符串（internal-service），没有用户 id
            return new Actor(null, auth.getName());
        }
        return new Actor(null, SYSTEM_ACTOR);
    }

    private String serialize(Object detail) {
        if (detail == null) {
            return "{}";
        }
        if (detail instanceof String text) {
            return truncate(text);
        }
        try {
            return truncate(objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException e) {
            log.warn("审计明细序列化失败，退化为类型名: {}", e.getMessage());
            return "{\"serializationError\":\"" + detail.getClass().getSimpleName() + "\"}";
        }
    }

    private String truncate(String text) {
        int max = props.getAudit().getDetailMaxLength();
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private record Actor(Long id, String username) {
    }
}
