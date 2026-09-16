package io.trustagent.audit;

import io.trustagent.audit.mapper.AuditLogMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 审计链验证。
 *
 * <p>按 {@code seq} 升序逐条检查三件事，任何一项失败即记录**首个**异常位置：
 * <ol>
 *   <li>{@code SEQ_GAP}：seq 不连续（说明中间记录被删除）。</li>
 *   <li>{@code PREV_HASH_MISMATCH}：本条 prev_hash 与上一条 record_hash 不一致
 *       （说明上一条被改后重算，或被替换/插入）。</li>
 *   <li>{@code RECORD_HASH_MISMATCH}：用本条字段复算的哈希与存储的 record_hash 不一致
 *       （说明本条内容在写入后被修改）。</li>
 * </ol>
 * 检查顺序很重要：先查“缺口”，再查“链接”，最后查“自身内容”，这样定位到的位置最贴近真实篡改点。
 */
@Service
@RequiredArgsConstructor
public class AuditVerificationService {

    private final AuditLogMapper auditLogMapper;

    public enum Reason {
        NONE, SEQ_GAP, PREV_HASH_MISMATCH, RECORD_HASH_MISMATCH
    }

    public record VerifyResult(boolean valid, long checkedRecords, Long firstBrokenSeq, Reason reason,
                               String detail, Instant verifiedAt) {
    }

    public VerifyResult verify() {
        List<AuditLog> logs = auditLogMapper.selectAllOrderedBySeq();
        long checked = 0;
        Long expectedSeq = null;
        String previousRecordHash = null;

        for (AuditLog entry : logs) {
            checked++;
            if (expectedSeq == null) {
                if (entry.getSeq() == null || entry.getSeq() != 1L) {
                    return broken(checked, entry.getSeq(), Reason.SEQ_GAP,
                            "链首 seq 应为 1，实际为 " + entry.getSeq() + "（前面的审计记录已被删除）");
                }
            } else if (entry.getSeq() == null || entry.getSeq() != expectedSeq) {
                return broken(checked, entry.getSeq(), Reason.SEQ_GAP,
                        "seq 不连续：期望 " + expectedSeq + "，实际 " + entry.getSeq() + "（中间记录被删除）");
            }
            expectedSeq = entry.getSeq() + 1;

            String expectedPrev = previousRecordHash == null ? AuditHashChain.GENESIS_HASH : previousRecordHash;
            if (!expectedPrev.equals(entry.getPrevHash())) {
                return broken(checked, entry.getSeq(), Reason.PREV_HASH_MISMATCH,
                        "seq=" + entry.getSeq() + " 的 prev_hash=" + shortHash(entry.getPrevHash())
                                + " 与期望 " + shortHash(expectedPrev) + " 不一致");
            }

            String recomputed = AuditHashChain.recompute(entry);
            if (!recomputed.equals(entry.getRecordHash())) {
                return broken(checked, entry.getSeq(), Reason.RECORD_HASH_MISMATCH,
                        "seq=" + entry.getSeq() + " 复算哈希=" + shortHash(recomputed)
                                + " 与存储 record_hash=" + shortHash(entry.getRecordHash())
                                + " 不一致（该记录内容在写入后被修改）");
            }

            previousRecordHash = entry.getRecordHash();
        }

        return new VerifyResult(true, checked, null, Reason.NONE, null, Instant.now());
    }

    private static VerifyResult broken(long checked, Long seq, Reason reason, String detail) {
        return new VerifyResult(false, checked, seq, reason, detail, Instant.now());
    }

    private static String shortHash(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
    }
}
