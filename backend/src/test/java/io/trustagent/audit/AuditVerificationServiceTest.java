package io.trustagent.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.trustagent.audit.AuditVerificationService.Reason;
import io.trustagent.audit.AuditVerificationService.VerifyResult;
import io.trustagent.audit.mapper.AuditLogMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计链验证逻辑的单元测试：用假数据构造"被删/被改/被替换"三种损坏形态。
 *
 * <p>为什么用 mock 而不是往 H2 里造数据：这三种损坏都很"局部"，
 * 而造库数据要花精力处理列名大小写、时间戳类型等与验证逻辑无关的细节。
 * 用 mock 可以把每个场景写成 5 行，且失败时的语义一眼可见。
 */
class AuditVerificationServiceTest {

    private static final long BASE_EPOCH_MS = 1_760_000_000_000L;

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditVerificationService service = new AuditVerificationService(mapper);

    /** 构造一条"写入时计算正确"的审计记录。 */
    private static AuditLog entry(long seq, String prevHash, String detailJson) {
        AuditLog log = new AuditLog();
        log.setSeq(seq);
        log.setActorId(1L);
        log.setActorUsername("tester");
        log.setAction("ACT");
        log.setResourceType("res");
        log.setResourceId("1");
        log.setDetailJson(detailJson);
        log.setTraceId("trace-1");
        log.setCreatedAtEpochMs(BASE_EPOCH_MS + seq);
        log.setPrevHash(prevHash);
        log.setRecordHash(AuditHashChain.hash(seq, 1L, "tester", "ACT", "res", "1", detailJson, "trace-1",
                log.getCreatedAtEpochMs(), prevHash));
        return log;
    }

    private VerifyResult verify(List<AuditLog> logs) {
        when(mapper.selectAllOrderedBySeq()).thenReturn(logs);
        return service.verify();
    }

    @Test
    @DisplayName("空链视为完整（checkedRecords = 0）")
    void emptyChainIsValid() {
        VerifyResult result = verify(List.of());
        assertTrue(result.valid());
        assertEquals(0, result.checkedRecords());
        assertEquals(Reason.NONE, result.reason());
        assertNull(result.firstBrokenSeq());
    }

    @Test
    @DisplayName("正确链接的两条记录验证通过")
    void wellFormedChainIsValid() {
        AuditLog first = entry(1, AuditHashChain.GENESIS_HASH, "{}");
        AuditLog second = entry(2, first.getRecordHash(), "{\"b\":2}");

        VerifyResult result = verify(List.of(first, second));
        assertTrue(result.valid());
        assertEquals(2, result.checkedRecords());
        assertEquals(Reason.NONE, result.reason());
    }

    @Test
    @DisplayName("链首不是 seq=1 -> SEQ_GAP（前面的记录被删）")
    void chainMustStartAtSeqOne() {
        AuditLog orphan = entry(2, AuditHashChain.GENESIS_HASH, "{}");

        VerifyResult result = verify(List.of(orphan));
        assertEquals(Reason.SEQ_GAP, result.reason());
        assertEquals(2L, result.firstBrokenSeq());
    }

    @Test
    @DisplayName("seq 不连续 -> SEQ_GAP，定位到断层处的那一条")
    void gapIsDetectedAtDiscontinuity() {
        AuditLog first = entry(1, AuditHashChain.GENESIS_HASH, "{}");
        AuditLog third = entry(3, first.getRecordHash(), "{}");

        VerifyResult result = verify(List.of(first, third));
        assertEquals(Reason.SEQ_GAP, result.reason());
        assertEquals(3L, result.firstBrokenSeq());
    }

    @Test
    @DisplayName("后继记录的 prev_hash 与上一条 record_hash 不符 -> PREV_HASH_MISMATCH")
    void prevHashMismatchIsDetected() {
        AuditLog first = entry(1, AuditHashChain.GENESIS_HASH, "{}");
        AuditLog second = entry(2, "f".repeat(64), "{}");

        VerifyResult result = verify(List.of(first, second));
        assertEquals(Reason.PREV_HASH_MISMATCH, result.reason());
        assertEquals(2L, result.firstBrokenSeq());
    }

    @Test
    @DisplayName("记录内容被改（record_hash 没跟着变）-> RECORD_HASH_MISMATCH")
    void contentTamperingIsDetected() {
        AuditLog tampered = entry(1, AuditHashChain.GENESIS_HASH, "{}");
        tampered.setDetailJson("{\"tampered\":true}");

        VerifyResult result = verify(List.of(tampered));
        assertEquals(Reason.RECORD_HASH_MISMATCH, result.reason());
        assertEquals(1L, result.firstBrokenSeq());
    }

    @Test
    @DisplayName("检查顺序：先查缺口、再查链接、最后查自身内容，因此定位到的是最贴近真实篡改点的位置")
    void seqGapTakesPrecedenceOverHashMismatch() {
        // 第二条既 seq 不连续、又内容被改：应当报 SEQ_GAP，因为"记录被删"是更前置的事实
        AuditLog first = entry(1, AuditHashChain.GENESIS_HASH, "{}");
        AuditLog broken = entry(3, first.getRecordHash(), "{}");
        broken.setDetailJson("{\"alsoTampered\":true}");

        VerifyResult result = verify(List.of(first, broken));
        assertEquals(Reason.SEQ_GAP, result.reason());
        assertEquals(3L, result.firstBrokenSeq());
    }
}
