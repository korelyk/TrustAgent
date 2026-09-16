package io.trustagent.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 审计哈希链的核心算法（纯函数，便于单元测试）。
 *
 * <pre>
 * record_hash(n) = SHA256( canonical(n) )
 * canonical(n)   = join('\n', seq, actorId, actorUsername, action, resourceType, resourceId,
 *                        detailJson, traceId, createdAtEpochMs, prev_hash(n))
 * prev_hash(n)   = record_hash(n-1)，首条记录为 GENESIS_HASH
 * </pre>
 *
 * <h3>两个容易被忽略的工程点</h3>
 * <ol>
 *   <li><b>时间参与哈希必须用整型毫秒</b>：如果用 {@code timestamp} 列的 {@code LocalDateTime}，
 *       读回时的精度取决于数据库（MySQL 默认到秒，H2 到微秒），会导致“内容没改但验证失败”的误报。
 *       所以额外存 {@code created_at_epoch_ms} 并只用它参与哈希，{@code created_at} 仅供人看。</li>
 *   <li><b>规范化必须转义分隔符</b>：字段值里若含换行，会把 10 个字段拼成更多段，理论上可构造出
 *       “不同内容相同拼接串”的碰撞。这里把 {@code \r\n} 转义后再拼接，消除该歧义。</li>
 * </ol>
 *
 * <h3>它不是区块链</h3>
 * 这只是单条链的顺序哈希：能**发现**被修改，但拥有数据库写权限的攻击者可以重算整条链。
 * 详见 docs/SECURITY_LIMITATIONS.md。
 */
public final class AuditHashChain {

    /** 链首的“上一条哈希”，用 64 个 0 表示。 */
    public static final String GENESIS_HASH = "0".repeat(64);

    private AuditHashChain() {
    }

    public static String canonical(Long seq, Long actorId, String actorUsername, String action,
                                   String resourceType, String resourceId, String detailJson,
                                   String traceId, Long createdAtEpochMs, String prevHash) {
        return String.join("\n",
                String.valueOf(seq),
                String.valueOf(actorId),
                escape(actorUsername),
                escape(action),
                escape(resourceType),
                escape(resourceId),
                escape(detailJson),
                escape(traceId),
                String.valueOf(createdAtEpochMs),
                prevHash);
    }

    public static String hash(Long seq, Long actorId, String actorUsername, String action,
                              String resourceType, String resourceId, String detailJson,
                              String traceId, Long createdAtEpochMs, String prevHash) {
        String canonical = canonical(seq, actorId, actorUsername, action, resourceType, resourceId,
                detailJson, traceId, createdAtEpochMs, prevHash);
        return sha256Hex(canonical);
    }

    /** 用数据库中已持久化的字段复算哈希，供验证接口使用。 */
    public static String recompute(AuditLog log) {
        return hash(log.getSeq(), log.getActorId(), log.getActorUsername(), log.getAction(),
                log.getResourceType(), log.getResourceId(), log.getDetailJson(), log.getTraceId(),
                log.getCreatedAtEpochMs(), log.getPrevHash());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
