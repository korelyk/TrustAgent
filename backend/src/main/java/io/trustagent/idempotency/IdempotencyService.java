package io.trustagent.idempotency;

import io.trustagent.audit.AuditHashChain;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.idempotency.mapper.IdempotencyRecordMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等控制（Stripe 风格 Idempotency-Key）。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>客户端带 {@code Idempotency-Key} 提交。</li>
 *   <li>{@link #acquire} 尝试插入 {@code IN_PROGRESS} 记录：
 *       插入成功 = 首次请求，继续执行业务；
 *       唯一键冲突 = 重复请求，读回已有记录并返回 {@code replay=true}。</li>
 *   <li>业务成功后 {@link #complete} 回填业务资源 ID，之后重复请求可直接返回该 ID。</li>
 * </ol>
 *
 * <h3>为什么不做“先查再插”</h3>
 * “先 select 判断存在与否，再 insert”在并发下有检查-使用竞态（TOCTOU）：
 * 两个请求可以同时查到“不存在”，然后都去插入，结果创建两个任务。
 * 因此这里直接插、用唯一索引兜底，把竞态交给数据库。
 *
 * <h3>边界情况</h3>
 * <ul>
 *   <li><b>同一个 Key 提交了不同内容</b>：返回 409，而不是复用旧结果。
 *       否则客户端改完参数重试会拿到一个语义不符的旧任务，问题比报错更难查。</li>
 *   <li><b>前一次请求失败（业务回滚）</b>：{@code IN_PROGRESS} 记录会随事务一起回滚，
 *       客户端可以用同一个 Key 重试，不会被永久卡死。</li>
 *   <li><b>Key 格式</b>：限制长度 8~128 且只允许可见 ASCII，防止把超长/控制字符写进索引列。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:\\-]{8,128}$");
    private static final int RETENTION_HOURS = 24;

    private final IdempotencyRecordMapper mapper;

    public record Outcome(boolean replay, Long resourceId, String status, String requestHash) {

        public static Outcome fresh() {
            return new Outcome(false, null, "IN_PROGRESS", null);
        }
    }

    /**
     * 获取幂等令牌。
     *
     * @param scope       业务范围，如 {@code eval:run}
     * @param key         客户端提供的 Idempotency-Key
     * @param requestBody 用于生成请求指纹的对象（通常是请求 DTO 的 JSON 串）
     */
    @Transactional
    public Outcome acquire(String scope, String key, String requestBody) {
        validateKey(key);
        String requestHash = AuditHashChain.sha256Hex(requestBody == null ? "" : requestBody);

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdemKey(key);
        record.setScope(scope);
        record.setRequestHash(requestHash);
        record.setStatus("IN_PROGRESS");
        record.setCreatedAt(LocalDateTime.now());
        record.setExpiresAt(LocalDateTime.now().plusHours(RETENTION_HOURS));
        try {
            mapper.insert(record);
            return Outcome.fresh();
        } catch (DuplicateKeyException ex) {
            IdempotencyRecord existing = mapper.selectByKeyAndScope(key, scope);
            if (existing == null) {
                // 理论上不可达：唯一键冲突却查不到记录，说明事务隔离级别或索引有问题，必须暴露出来
                throw new IllegalStateException("幂等记录冲突但查询不到: scope=" + scope + " key=" + key, ex);
            }
            if (!requestHash.equals(existing.getRequestHash())) {
                log.warn("幂等键复用了不同请求体 scope={} key={}", scope, key);
                throw new BizException(ErrorCode.CONFLICT,
                        "该 Idempotency-Key 已用于内容不同的请求，请更换 Key 后重试");
            }
            log.info("幂等重放 scope={} key={} resourceId={}", scope, key, existing.getResourceId());
            Long resourceId = existing.getResourceId() == null ? null : Long.valueOf(existing.getResourceId());
            return new Outcome(true, resourceId, existing.getStatus(), existing.getRequestHash());
        }
    }

    /**
     * 释放幂等键，使客户端可以用同一个 Key 重试。
     *
     * <p>使用场景很窄：业务资源已经创建、但进程在后半程失败（例如把任务投进队列时 Redis 不可用）。
     * 此时无论返回"重放旧任务"还是"409"都会把客户端卡死，唯一正确的做法是把这次尝试整体作废。
     */
    @Transactional
    public void release(String scope, String key) {
        int removed = mapper.deleteByKeyAndScope(key, scope);
        log.warn("幂等键已释放 scope={} key={} removed={}（上一次请求已作废，允许用同一 Key 重试）",
                scope, key, removed);
    }

    /** 业务成功后回填资源 ID。 */
    @Transactional
    public void complete(String scope, String key, String resourceType, Long resourceId) {
        IdempotencyRecord existing = mapper.selectByKeyAndScope(key, scope);
        if (existing == null) {
            log.warn("回填幂等记录时未找到记录 scope={} key={}", scope, key);
            return;
        }
        existing.setStatus("COMPLETED");
        existing.setResourceType(resourceType);
        existing.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        mapper.updateById(existing);
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key 必须为 8~128 位可见字符（字母、数字、_ . : -）");
        }
        // 明确记录编码检查，避免有人把非 ASCII Key 塞进来后在索引里产生歧义
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(key)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必须是 ASCII 字符");
        }
    }

    public int purgeExpired() {
        return mapper.deleteExpired(LocalDateTime.now());
    }
}
