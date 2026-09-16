package io.trustagent.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测领域的"词汇表"：类别、期望动作、运行模式、状态。
 *
 * <h3>为什么单独抽一个词汇表</h3>
 * 评测是**数据驱动**的功能：数据集 JSONL 里写的是字符串，导入校验要靠这份表，
 * 指标计算要靠这份表分组，报告导出要靠这份表给出可读列名。
 * 如果把这些字符串散落在 Service / Controller / 测试里，加一类攻击就要改五处，
 * 而且极易出现"导入放行了、指标算不出"的漂移。
 *
 * <h3>与执行面事件类型的关系</h3>
 * 类别是**评测用的攻击面分类**（人写数据集时选），
 * {@code SecurityEventType} 是**运行期检测器产生的事件**。
 * 两者不是同一层概念：一条 {@code PROMPT_INJECTION_DIRECT} 用例
 * 可能同时触发 {@code PROMPT_INJECTION_DIRECT} 与 {@code ENCODED_INSTRUCTION} 两个事件。
 * 因此这里刻意不复用事件类型常量。
 */
public final class EvalVocabulary {

    // ------------------------------------------------------------------ 类别

    /** 正常任务：应当放行，并按预期调用/不调用工具。 */
    public static final String NORMAL = "NORMAL";
    /** 直接提示注入：用户输入里就带着越权指令。 */
    public static final String PROMPT_INJECTION_DIRECT = "PROMPT_INJECTION_DIRECT";
    /** 间接提示注入：指令藏在检索到的文档/工具返回值里。 */
    public static final String PROMPT_INJECTION_INDIRECT = "PROMPT_INJECTION_INDIRECT";
    /** 编码混淆指令：base64 / hex / 零宽字符等，要求解码后执行。 */
    public static final String ENCODED_INSTRUCTION = "ENCODED_INSTRUCTION";
    /** 诱导泄露系统提示词。 */
    public static final String SYSTEM_PROMPT_LEAK = "SYSTEM_PROMPT_LEAK";
    /** 工具滥用：未授权工具、有副作用工具、敏感参数、超调用次数。 */
    public static final String TOOL_ABUSE = "TOOL_ABUSE";
    /** 敏感信息暴露：输入/上下文里含 PII，期望被脱敏而不是原样流出。 */
    public static final String PII_EXPOSURE = "PII_EXPOSURE";
    /** 输出不安全：危险 HTML、未在白名单内的外链。 */
    public static final String OUTPUT_UNSAFE = "OUTPUT_UNSAFE";

    /** 用 LinkedHashMap 而不是 List：既要"有序"（报表列顺序稳定），又要带描述。 */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        DESCRIPTIONS.put(NORMAL, "正常任务：应当放行，并按预期调用或不调用工具");
        DESCRIPTIONS.put(PROMPT_INJECTION_DIRECT, "直接提示注入：输入里就带越权指令");
        DESCRIPTIONS.put(PROMPT_INJECTION_INDIRECT, "间接提示注入：指令藏在文档或工具返回值里");
        DESCRIPTIONS.put(ENCODED_INSTRUCTION, "编码混淆指令：base64/hex/零宽字符，解码后执行");
        DESCRIPTIONS.put(SYSTEM_PROMPT_LEAK, "诱导泄露系统提示词");
        DESCRIPTIONS.put(TOOL_ABUSE, "工具滥用：越权工具、副作用工具、敏感参数、超调用上限");
        DESCRIPTIONS.put(PII_EXPOSURE, "敏感信息暴露：期望被脱敏而不是原样流出");
        DESCRIPTIONS.put(OUTPUT_UNSAFE, "输出不安全：危险 HTML 或白名单外的外链");
    }

    private static final List<String> ALL_CATEGORIES = List.copyOf(DESCRIPTIONS.keySet());

    /** 攻击类用例：期望动作是"拦住"。NORMAL 之外的都算。 */
    private static final Set<String> ATTACK_CATEGORIES = Set.of(
            PROMPT_INJECTION_DIRECT, PROMPT_INJECTION_INDIRECT, ENCODED_INSTRUCTION,
            SYSTEM_PROMPT_LEAK, TOOL_ABUSE, PII_EXPOSURE, OUTPUT_UNSAFE);

    // ------------------------------------------------------------------ 期望/实际动作

    public static final String ACTION_ALLOW = "ALLOW";
    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_MASK = "MASK";
    public static final String ACTION_CALL_TOOL = "CALL_TOOL";

    private static final Set<String> ALL_ACTIONS =
            Set.of(ACTION_ALLOW, ACTION_BLOCK, ACTION_MASK, ACTION_CALL_TOOL);

    // ------------------------------------------------------------------ 运行模式

    /** 只跑开启防护的一轮。 */
    public static final String MODE_GUARD_ON = "GUARD_ON";
    /** 只跑关闭防护的基线轮。 */
    public static final String MODE_GUARD_OFF = "GUARD_OFF";
    /** 同样用例跑两轮，用于"防护开/关"对比。 */
    public static final String MODE_BOTH = "BOTH";

    private static final Set<String> ALL_MODES = Set.of(MODE_GUARD_ON, MODE_GUARD_OFF, MODE_BOTH);

    // ------------------------------------------------------------------ 任务状态

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_SUCCEEDED, STATUS_FAILED);

    // ------------------------------------------------------------------ 数据集来源

    /**
     * 数据集来源类别。第一版只有自建合成集，但**必须显式标注**：
     * 用真实数据或公开基准跑出来的指标不可与合成集合混为一谈，报告的结论强度完全不同。
     */
    public static final String SOURCE_SYNTHETIC_SELF_AUTHORED = "SYNTHETIC_SELF_AUTHORED";
    public static final String SOURCE_PUBLIC_BENCHMARK_ADAPTED = "PUBLIC_BENCHMARK_ADAPTED";

    private static final Set<String> ALL_SOURCES =
            Set.of(SOURCE_SYNTHETIC_SELF_AUTHORED, SOURCE_PUBLIC_BENCHMARK_ADAPTED);

    private EvalVocabulary() {
    }

    public static List<String> allCategories() {
        return ALL_CATEGORIES;
    }

    public static String describe(String category) {
        return DESCRIPTIONS.getOrDefault(category, "");
    }

    public static boolean categoryExists(String category) {
        return category != null && DESCRIPTIONS.containsKey(category);
    }

    public static boolean isAttackCategory(String category) {
        return category != null && ATTACK_CATEGORIES.contains(category);
    }

    public static boolean actionExists(String action) {
        return action != null && ALL_ACTIONS.contains(action);
    }

    public static boolean modeExists(String mode) {
        return mode != null && ALL_MODES.contains(mode);
    }

    public static boolean sourceExists(String source) {
        return source != null && ALL_SOURCES.contains(source);
    }

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    /**
     * 一次任务需要跑几轮：{@code BOTH} 要跑"防护开 + 防护关"两轮，
     * 其余模式只跑一轮。{@code total_cases} 用"用例数 × 轮数"表示，前端进度条才有意义。
     */
    public static int roundCount(String mode) {
        return MODE_BOTH.equals(mode) ? 2 : 1;
    }

    /** 该模式下是否需要跑"防护关闭"的那一轮。 */
    public static boolean needsGuardOffRound(String mode) {
        return MODE_GUARD_OFF.equals(mode) || MODE_BOTH.equals(mode);
    }

    /** 该模式下是否需要跑"防护开启"的那一轮。 */
    public static boolean needsGuardOnRound(String mode) {
        return MODE_GUARD_ON.equals(mode) || MODE_BOTH.equals(mode);
    }
}
