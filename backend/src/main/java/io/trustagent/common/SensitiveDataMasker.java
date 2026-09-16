package io.trustagent.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端兜底脱敏。
 *
 * <p>主脱敏发生在 Python 执行面（检测器返回的就已经是脱敏值）。这里是**第二道防线**：
 * 即使执行面被替换、配置错误或被绕过，控制面入库前仍会再脱敏一次，保证“数据库里不存在原始敏感值”。
 *
 * <p>规则与 Python 端 {@code app/guardrails/pii.py} 保持一致，但控制面只做“替换”，不做事件上报
 * ——事件上报是执行面的职责，避免同一件事被记两次。
 *
 * <h3>为什么不是“按规则逐条迭代替换”</h3>
 * 早期实现把五条规则按固定顺序轮流套在**同一串已被改写的文本**上（{@code result = mask(result)}），
 * 这会同时产生两类错误：
 * <ol>
 *   <li><b>漏检</b>：前一条规则改写过的片段会让后面的规则永远匹配不到。例如串里同时含 18 位数字
 *       与 API Key 时，先命中的 ID_CARD 把数字段替换成星号，BANK_CARD / API_KEY 对整串失配——
 *       本该被整体掩码的 Key 只被掩掉中间一段，剩下的部分仍然可读。</li>
 *   <li><b>类别取决于顺序</b>：{@code detect()} 各规则独立在原串上匹配，重叠片段会被报成多个类别；
 *       而 Python 侧（{@code pii.py} 的 {@code _drop_overlaps}）只保留互不重叠的最长匹配。
 *       两侧口径不一致，跨语言比对与测试断言都会失真。</li>
 * </ol>
 *
 * <p>现在的做法与 Python 对齐：**先一次性收集全部候选命中，去重叠（按起点升序、同起点取更长者）
 * 后自后向前统一替换**。自后向前替换保证前面的匹配区间不受后面替换的影响——即便将来某条规则的
 * 掩码结果长度与原值不等（当前 {@link #maskValue} 是等长替换），也不会出现区间错位。
 */
public final class SensitiveDataMasker {

    /** 中国大陆手机号 */
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 邮箱 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 18 位身份证样式串（含校验位 X） */
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?!\\d)");
    /** 银行卡样式串：16~19 位连续数字 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    /**
     * API Key 样式：sk-xxx / api_key=xxx / token: xxx。
     *
     * <p>{@code sk-} 前缀后的最小长度是 <b>12</b>，与 Python 侧
     * {@code ai-service/app/guardrails/pii.py} 的 {@code [A-Za-z0-9_\-]{12,}} 严格一致。
     * 这里曾经写成 16：同一个串在执行面被判为敏感、在控制面兜底这一层却被判为不敏感，
     * 属于典型的“两侧口径漂移”——兜底防线只要有一侧放过，脱敏就等于没做。
     */
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(?:sk|ak|pk)-[A-Za-z0-9_\\-]{12,}|\\b(?:api[_-]?key|access[_-]?token|secret)\\b\\s*[:=]\\s*[A-Za-z0-9_\\-]{8,}");

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("ID_CARD", ID_CARD);
        RULES.put("BANK_CARD", BANK_CARD);
        RULES.put("API_KEY", API_KEY);
        RULES.put("EMAIL", EMAIL);
        RULES.put("PHONE_CN", PHONE_CN);
    }

    private SensitiveDataMasker() {
    }

    /**
     * 已命中的规则名。
     *
     * <p>返回顺序是**命中位置顺序**（同一位置时长者优先），与 Python 侧 {@code detect_pii()} 一致。
     * 重叠的命中已在此丢弃，因此不会出现“一段 18 位数字同时被报成身份证与银行卡”——
     * 那正是按规则独立匹配时最容易出错的点（一条真实串会被报成两类，指标与告警都跟着失真）。
     */
    public static List<String> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return collect(text).stream().map(Match::rule).toList();
    }

    public static boolean hasSensitive(String text) {
        return !detect(text).isEmpty();
    }

    /**
     * 对文本中所有已知敏感模式做掩码替换，保持原始长度之外的可读性。
     *
     * <p>命中来自 {@link #collect(String)}（在原文上一次性收集并去重叠），
     * 因此不存在“前一条规则改写导致后一条失配”的漏检，也不会因为替换而挪动后面的区间。
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<Match> matches = collect(text);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        // 自后向前替换：前面的 start/end 不会因为后面的替换而失效
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            sb.replace(match.start(), match.end(), maskValue(text.substring(match.start(), match.end())));
        }
        return sb.toString();
    }

    /**
     * 一次性收集所有规则在**原文**上的命中，并做去重叠。
     *
     * <p>与 Python 侧 {@code _drop_overlaps} 同口径：按起点升序、同起点取更长者；
     * 等长时按 {@link #RULES} 的声明顺序（ID_CARD 先于 BANK_CARD），
     * 这样 18 位身份证只会被报一次，而不会同时被算成“身份证 + 银行卡”。
     *
     * <p>为什么是“先收集后替换”而不是“边匹配边替换”：替换会改变文本，
     * 后续规则的匹配对象就不再是原文了。收集阶段完全不改文本，
     * 因此每条规则看到的都是同一份输入，测出来的结果才是可解释、可复现的。
     */
    private static List<Match> collect(String text) {
        List<Match> candidates = new ArrayList<>();
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            Matcher matcher = rule.getValue().matcher(text);
            while (matcher.find()) {
                candidates.add(new Match(rule.getKey(), matcher.start(), matcher.end()));
            }
        }
        // 起点升序；同起点时长的排前面（负号 = 按长度降序），保证重叠取舍时“取更长者”
        candidates.sort(Comparator.comparingInt(Match::start)
                .thenComparingInt(match -> -(match.end() - match.start())));

        List<Match> accepted = new ArrayList<>();
        for (Match candidate : candidates) {
            boolean overlaps = false;
            for (Match kept : accepted) {
                if (candidate.start() < kept.end() && kept.start() < candidate.end()) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                accepted.add(candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(Match::start));
        return accepted;
    }

    /** 一条命中：规则名 + 原文区间（半开区间 [start, end)）。 */
    private record Match(String rule, int start, int end) {
    }

    /**
     * 单值掩码：保留前 3 位与后 2 位，中间用 {@code *} 替代。
     * 长度不足 7 位时全部替换为 {@code *}，避免“保留了太多原值”。
     */
    public static String maskValue(String value) {
        if (value == null) {
            return null;
        }
        int len = value.length();
        if (len <= 6) {
            return "*".repeat(len);
        }
        return value.substring(0, 3) + "*".repeat(len - 5) + value.substring(len - 2);
    }

    /** 截断 + 脱敏，用于落库的预览字段。 */
    public static String preview(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String masked = mask(text);
        if (masked.length() <= maxLength) {
            return masked;
        }
        return masked.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
