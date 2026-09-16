package io.trustagent.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 兜底脱敏的规则与匹配顺序。
 *
 * <h3>为什么“顺序”值得单独测</h3>
 * 旧实现按固定顺序把五条规则轮流套在同一串**已被改写的文本**上。
 * 于是同一段文本会得到什么结果，取决于它先命中哪一条：
 * {@code ID_CARD} 先把 18 位数字改成星号后，{@code BANK_CARD} 与 {@code API_KEY}
 * 在同一串上就再也匹配不到了。这类缺陷不抛异常、不报错，只是**默默漏掉该脱敏的内容**——
 * 所以必须有测试钉住“谁跟谁重叠时算谁”，而不是只测单条规则各命中一次。
 */
class SensitiveDataMaskerTest {

    /** 校验位合法的合成串（取自项目内的合成用例，非真实证件/卡号）。 */
    private static final String ID_CARD = "11010519491231002X";
    private static final String BANK_CARD = "4539578763621486";

    @Test
    @DisplayName("mask() 掩码后保留可读首尾，且长度不变")
    void maskKeepsShape() {
        String text = "手机 13800138000 请联系我";
        String masked = SensitiveDataMasker.mask(text);
        assertFalse(masked.contains("13800138000"), "原值不得残留: " + masked);
        assertEquals("手机 138******00 请联系我", masked);
        assertEquals(text.length(), masked.length(), "等长替换，避免挪动后续区间");
    }

    @Test
    @DisplayName("mask() 不因前一条规则的改写而漏掉后面的规则（旧实现必错）")
    void laterRulesAreNotShadowedByEarlierMasking() {
        // 关键构造：卡号与 API Key 都命中，且 Key 里夹着数字。
        // 旧实现先跑 BANK_CARD / ID_CARD，把数字段换成星号后，
        // sk- 规则再匹配时那一段已经不是合法字符集，于是整条 Key 漏掉。
        String text = "卡号 " + BANK_CARD + "，key = sk-abcdefghijklmnop1234 结束";
        String masked = SensitiveDataMasker.mask(text);
        assertFalse(masked.contains(BANK_CARD), "银行卡号必须被掩码: " + masked);
        assertFalse(masked.contains("abcdefghijklmnop1234"), "API Key 主体必须被掩码: " + masked);
        assertTrue(masked.contains("sk-"), "前缀可保留，用于让人看出这里原本是什么: " + masked);
    }

    @Test
    @DisplayName("重叠命中只算一次：18 位身份证不会同时被报成银行卡")
    void overlappingHitsAreDeduplicated() {
        List<String> rules = SensitiveDataMasker.detect("证件 " + ID_CARD);
        assertEquals(List.of("ID_CARD"), rules,
                "身份证与银行卡规则重叠时必须只保留更长的那条，实际: " + rules);
    }

    @Test
    @DisplayName("detect() 按命中位置返回，而不是按固定的规则表顺序")
    void detectReportsHitsInPositionOrder() {
        // 故意让手机号排在身份证前面：若返回的是“固定规则顺序”（身份证在前），这里会红。
        List<String> rules = SensitiveDataMasker.detect("电话 13800138000，证件 " + ID_CARD);
        assertEquals(List.of("PHONE_CN", "ID_CARD"), rules);
    }

    @ParameterizedTest
    @DisplayName("sk- 前缀的 API Key 从 12 个字符起就被识别（与 Python 侧口径对齐）")
    @ValueSource(strings = {
            "sk-abcdefghijkl",      // 恰好 12
            "sk-abcdefghijklm",     // 13
            "sk-abcdefghijklmnop",  // 16，旧实现的下限
    })
    void apiKeyMinimumLengthMatchesPythonSide(String key) {
        assertTrue(SensitiveDataMasker.hasSensitive("token: " + key),
                "12 字符以上的 Key 必须被识别（控制面兜底不能比执行面更宽松）: " + key);
        assertFalse(SensitiveDataMasker.mask("token: " + key).contains(key));
    }

    @Test
    @DisplayName("少于 12 个字符的 sk- 串不算 Key：不能把正常文本误伤成敏感数据")
    void shortKeyIsNotFlagged() {
        assertFalse(SensitiveDataMasker.hasSensitive("token: sk-abcdefghijk"),
                "少于 12 个字符不属于 Key 形态，两侧规则都不应命中");
    }

    @Test
    @DisplayName("preview() 先脱敏再截断：截断本身不会把敏感值暴露出来")
    void previewMasksBeforeTruncating() {
        String text = "13800138000 是一串很长的前缀填充" + "x".repeat(100);
        String preview = SensitiveDataMasker.preview(text, 30);
        assertFalse(preview.contains("13800138000"), "截断不得让原值露出来: " + preview);
        assertTrue(preview.endsWith("..."), "超长时应带省略标记: " + preview);
        assertEquals(30, preview.length());
    }

    @Test
    @DisplayName("无敏感内容时原样返回：不要为了“看起来在工作”而改写文本")
    void benignTextIsUntouched() {
        String text = "今天天气不错，顺便算一下 1+1";
        assertEquals(text, SensitiveDataMasker.mask(text));
        assertTrue(SensitiveDataMasker.detect(text).isEmpty());
    }
}
