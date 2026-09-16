package io.trustagent.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.audit.AuditVerificationService.Reason;
import io.trustagent.audit.AuditVerificationService.VerifyResult;
import io.trustagent.audit.mapper.AuditLogMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用**真实审计记录**验证哈希链校验器（场景 F）。
 *
 * <h3>与 {@link AuditVerificationServiceTest} 的分工</h3>
 * 那个测试用人工构造的记录，覆盖的是"逻辑分支对不对"；
 * 本测试用从运行环境中导出的**真实记录**（{@code fixtures/audit-chain-real-v1.json}，
 * 8 条，seq 1..8），覆盖的是"校验器能不能处理真实数据的形状"——
 * 真实行里有 {@code actorId} 为 null、{@code detailJson} 含转义引号、
 * 时间戳为 epoch 毫秒等，这些是人工夹具容易理想化掉的部分。
 *
 * <h3>为什么不直接改线上的表来制造篡改</h3>
 * 那会破坏唯一的可信审计副本，而且"用被污染的数据证明校验器有效"本身没有意义。
 * 正确做法是把真实记录**复制**到隔离的数据集上再篡改。
 *
 * <h3>为什么还要测"检测不到"的情况</h3>
 * 后两个用例是**故意断言失败检测不可能发生**。把能力边界写成可执行的测试，
 * 比写在文档里更不容易被后来者误读成"这个链防得住一切"。见
 * {@code docs/SECURITY_LIMITATIONS.md}。
 */
class AuditChainRealDataTest {

    private static final String FIXTURE = "/fixtures/audit-chain-real-v1.json";

    /** 从真实环境导出的记录条数，用于断言"确实全部检查了"。 */
    private static final int REAL_RECORD_COUNT = 8;

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditVerificationService service = new AuditVerificationService(mapper);

    // ------------------------------------------------------------------ 夹具

    /**
     * 载入真实记录。每条都临时复制一份，避免用例间互相污染
     * （篡改用例会改字段，共享同一份会串味）。
     */
    private static List<AuditLog> realRecords() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream in = AuditChainRealDataTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("夹具缺失: " + FIXTURE);
            }
            JsonNode root = objectMapper.readTree(in);
            List<AuditLog> logs = new ArrayList<>();
            for (JsonNode n : root) {
                AuditLog log = new AuditLog();
                log.setSeq(n.get("seq").asLong());
                log.setActorId(n.get("actorId").isNull() ? null : n.get("actorId").asLong());
                log.setActorUsername(n.get("actorUsername").isNull() ? null : n.get("actorUsername").asText());
                log.setAction(n.get("action").asText());
                log.setResourceType(n.get("resourceType").isNull() ? null : n.get("resourceType").asText());
                log.setResourceId(n.get("resourceId").isNull() ? null : n.get("resourceId").asText());
                log.setDetailJson(n.get("detailJson").isNull() ? null : n.get("detailJson").asText());
                log.setTraceId(n.get("traceId").isNull() ? null : n.get("traceId").asText());
                log.setCreatedAtEpochMs(n.get("createdAtEpochMs").asLong());
                log.setPrevHash(n.get("prevHash").asText());
                log.setRecordHash(n.get("recordHash").asText());
                logs.add(log);
            }
            return logs;
        }
    }

    private VerifyResult verify(List<AuditLog> logs) {
        when(mapper.selectAllOrderedBySeq()).thenReturn(logs);
        return service.verify();
    }

    // ------------------------------------------------------------------ 基线

    @Test
    @DisplayName("真实记录的原始副本：校验通过，且检查了全部记录")
    void realRecordsFormAValidChain() throws Exception {
        List<AuditLog> logs = realRecords();
        assertEquals(REAL_RECORD_COUNT, logs.size(), "夹具条数应与导出时一致");

        VerifyResult result = verify(logs);

        assertTrue(result.valid(), "真实链应当有效，若失败说明导出或复算逻辑有问题: " + result.detail());
        assertEquals(REAL_RECORD_COUNT, result.checkedRecords(), "应逐条检查，不能提前退出");
        assertEquals(Reason.NONE, result.reason());
        assertNull(result.firstBrokenSeq());
        assertEquals(AuditHashChain.GENESIS_HASH, logs.get(0).getPrevHash(), "链首 prev_hash 应为创世哈希");
    }

    // ------------------------------------------------------------------ 三种篡改

    @Test
    @DisplayName("篡改中间一条记录的受保护字段并保留旧哈希 -> RECORD_HASH_MISMATCH，定位到该 seq")
    void tamperedProtectedFieldIsDetected() throws Exception {
        List<AuditLog> logs = realRecords();
        int target = 3;                       // 数组下标 3 = seq 4
        AuditLog victim = logs.get(target);
        String originalHash = victim.getRecordHash();

        // 只改内容，不动 record_hash —— 这正是"改库但不知道怎么重算哈希"的攻击者形态
        victim.setDetailJson("{\"tampered\":true,\"by\":\"audit-chain-real-data-test\"}");

        VerifyResult result = verify(logs);

        assertEquals(false, result.valid());
        assertEquals(Reason.RECORD_HASH_MISMATCH, result.reason());
        assertEquals(victim.getSeq(), result.firstBrokenSeq(), "应定位到被改的那一条");
        assertEquals(originalHash, victim.getRecordHash(), "本用例刻意保留旧哈希");
        assertTrue(result.detail().contains("复算哈希"), "detail 应说明是复算不一致: " + result.detail());
    }

    @Test
    @DisplayName("删除中间一条记录 -> SEQ_GAP，定位到断层处")
    void deletedMiddleRecordIsDetected() throws Exception {
        List<AuditLog> logs = realRecords();
        AuditLog removed = logs.remove(3);    // 删掉 seq 4

        VerifyResult result = verify(logs);

        assertEquals(false, result.valid());
        assertEquals(Reason.SEQ_GAP, result.reason());
        // 删掉 seq=4 之后，紧接着的那条是 seq=5，定位应指向它
        assertEquals(removed.getSeq() + 1, result.firstBrokenSeq());
        assertTrue(result.detail().contains("不连续"), "detail 应说明是 seq 不连续: " + result.detail());
    }

    @Test
    @DisplayName("改写某条的 prev_hash -> PREV_HASH_MISMATCH，定位到该 seq")
    void rewrittenPrevHashIsDetected() throws Exception {
        List<AuditLog> logs = realRecords();
        AuditLog victim = logs.get(3);
        victim.setPrevHash("f".repeat(64));   // 指向一个不存在的"前一条"

        VerifyResult result = verify(logs);

        assertEquals(false, result.valid());
        assertEquals(Reason.PREV_HASH_MISMATCH, result.reason());
        assertEquals(victim.getSeq(), result.firstBrokenSeq());
        assertTrue(result.detail().contains("prev_hash"), "detail 应包含 prev_hash: " + result.detail());
    }

    // ------------------------------------------------------------------ 能力边界（必须断言"检测不到"）

    @Test
    @DisplayName("边界：删除【链尾】不会被发现 —— 没有外部锚点时链尾可被静默截断")
    void tailTruncationIsNotDetected() throws Exception {
        List<AuditLog> logs = realRecords();
        logs.remove(logs.size() - 1);          // 删掉最后一条

        VerifyResult result = verify(logs);

        // 这不是缺陷，是"链式哈希 + 无外部锚点"的固有性质：
        // 前 7 条自身完整、链接完整，校验器没有任何依据知道"曾经有过第 8 条"。
        // 断言 valid=true 是为了把这条边界固定下来，防止有人误以为它能防截断。
        assertTrue(result.valid(),
                "删除链尾后剩余部分仍然自洽，因此校验通过 —— 这是已知边界，不是 bug");
        assertEquals(REAL_RECORD_COUNT - 1, result.checkedRecords());

        // 真正能发现截断的前提是"存在可信的记录总数或链尾哈希锚点"，本项目没有实现。
    }

    @Test
    @DisplayName("边界：攻击者若能重算整条链，校验会被绕过 —— 无外部存证时哈希链不提供防伪")
    void fullyRecomputedChainPassesVerification() throws Exception {
        List<AuditLog> logs = realRecords();

        // 模拟"拿到了写库权限、并且照着算法重算"的攻击者：
        // 改掉 seq=4 的内容，然后把从该条开始的 prev_hash/record_hash 全部重算一遍。
        logs.get(3).setDetailJson("{\"forged\":true}");
        String previous = logs.get(2).getRecordHash();
        for (int i = 3; i < logs.size(); i++) {
            AuditLog log = logs.get(i);
            log.setPrevHash(previous);
            log.setRecordHash(AuditHashChain.recompute(log));
            previous = log.getRecordHash();
        }

        VerifyResult result = verify(logs);

        // 同样不是缺陷，是这类"本地可重算哈希链"的固有上限。
        // 结论：本项目能发现的是**不完整/不一致的篡改**（改一条但没重算），
        // 不能发现**完整重算**。要防后者必须有外部锚点（WORM 存储、外部签名、只追加日志服务）。
        assertTrue(result.valid(),
                "整链重算后自洽，校验通过 —— 这是已知边界，不是 bug");
        assertEquals(Reason.NONE, result.reason());
    }
}
