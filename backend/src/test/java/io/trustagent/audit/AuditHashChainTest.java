package io.trustagent.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计哈希链的纯单元测试（不启动 Spring，秒级反馈）。
 *
 * <p>把链算法与数据库分开测的理由：这是整个"篡改可检测"结论的基础，
 * 如果它本身依赖数据库读回的精度，就会变成"测的是数据库而不是算法"。
 */
class AuditHashChainTest {

    private static final long EPOCH_MS = 1_760_000_000_000L;

    @Test
    @DisplayName("链首哈希固定为 64 个 0，且长度与 SHA-256 hex 一致")
    void genesisHashIsSixtyFourZeros() {
        assertEquals(64, AuditHashChain.GENESIS_HASH.length());
        assertEquals("0".repeat(64), AuditHashChain.GENESIS_HASH);
    }

    @Test
    @DisplayName("sha256Hex 符合公开测试向量，证明没有用错算法/编码")
    void sha256HexMatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AuditHashChain.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                AuditHashChain.sha256Hex(""));
    }

    @Test
    @DisplayName("同输入哈希可复现；任一字段变化都会改变哈希")
    void hashIsDeterministicAndSensitiveToEveryField() {
        String baseline = AuditHashChain.hash(1L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":1}", "trace-1", EPOCH_MS, AuditHashChain.GENESIS_HASH);

        assertEquals(baseline, AuditHashChain.hash(1L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":1}", "trace-1", EPOCH_MS, AuditHashChain.GENESIS_HASH));

        assertNotEquals(baseline, AuditHashChain.hash(2L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":1}", "trace-1", EPOCH_MS, AuditHashChain.GENESIS_HASH), "seq 变化必须改变哈希");
        assertNotEquals(baseline, AuditHashChain.hash(1L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":2}", "trace-1", EPOCH_MS, AuditHashChain.GENESIS_HASH), "明细变化必须改变哈希");
        assertNotEquals(baseline, AuditHashChain.hash(1L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":1}", "trace-1", EPOCH_MS, "f".repeat(64)), "前序哈希变化必须改变哈希");
        assertNotEquals(baseline, AuditHashChain.hash(1L, 7L, "dev01", "AUTH_LOGIN_SUCCESS", "user", "7",
                "{\"a\":1}", "trace-2", EPOCH_MS, AuditHashChain.GENESIS_HASH), "traceId 变化必须改变哈希");
    }

    @Test
    @DisplayName("recompute 只用持久化字段，落地后能自证未被动过")
    void recomputeUsesOnlyPersistedFields() {
        AuditLog entry = new AuditLog();
        entry.setSeq(3L);
        entry.setActorId(7L);
        entry.setActorUsername("dev01");
        entry.setAction("SECURITY_POLICY_UPDATED");
        entry.setResourceType("security_policy");
        entry.setResourceId("5");
        entry.setDetailJson("{\"version\":2}");
        entry.setTraceId("trace-3");
        entry.setCreatedAtEpochMs(EPOCH_MS);
        entry.setPrevHash("a".repeat(64));
        entry.setRecordHash(AuditHashChain.hash(3L, 7L, "dev01", "SECURITY_POLICY_UPDATED",
                "security_policy", "5", "{\"version\":2}", "trace-3", EPOCH_MS, "a".repeat(64)));

        assertEquals(entry.getRecordHash(), AuditHashChain.recompute(entry));

        // 模拟"有人直接改了库里的明细"：复算值不再等于存储值，验证逻辑据此报 RECORD_HASH_MISMATCH
        entry.setDetailJson("{\"version\":999}");
        assertNotEquals(entry.getRecordHash(), AuditHashChain.recompute(entry));
    }

    @Test
    @DisplayName("含换行的字段被转义，规范化后仍然只有 10 段，不会与相邻字段拼出歧义")
    void canonicalEscapesNewlinesSoFieldBoundariesStayUnambiguous() {
        // actorUsername 故意塞入换行：如果不转义，split 出来的段数会变成 11，字段边界就被"注掉了"
        String canonical = AuditHashChain.canonical(1L, null, "a\nb", "ACT", null, null, null, null,
                null, "p");
        String[] parts = canonical.split("\n", -1);
        assertEquals(10, parts.length, "规范化串必须恰好 10 段（字段换行已转义）");
        assertEquals("a\\nb", parts[2], "换行被转义成字面量 \\n");

        // 一个"真的含有反斜杠 + n 两个字符"的值，转义结果与"含换行"不同，二者不会互相冒充
        String literal = AuditHashChain.canonical(1L, null, "a\\nb", "ACT", null, null, null, null,
                null, "p");
        assertNotEquals(canonical, literal);
    }

    @Test
    @DisplayName("null 与空串在规范化中被归一化为同一种表示（已记录在案的取舍）")
    void nullAndEmptyAreNormalizedIdentically() {
        String withNull = AuditHashChain.canonical(1L, null, null, "ACT", null, null, null, null, null, "p");
        String withEmpty = AuditHashChain.canonical(1L, null, "", "ACT", null, null, null, null, null, "p");
        // 这不是缺陷：actorUsername 实际取值只有用户名或 "system"，不会出现空串，
        // 因此不必为"区分 null 与空串"增加一整套转义规则。这里把该取舍固化成测试，防止以后被误改。
        assertEquals(withNull, withEmpty);
    }
}
