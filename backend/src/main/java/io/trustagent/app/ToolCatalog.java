package io.trustagent.app;

import java.util.List;
import java.util.Set;

/**
 * 内置模拟工具的“字典表”（Java 侧）。
 *
 * <p>为什么 Java 也要维护一份工具清单：控制面需要在校验阶段就拒绝非法工具名
 * （例如创建应用时写了 {@code shell_exec}），不能等执行面报错。真正的实现与参数 Schema
 * 在 Python 执行面（{@code ai-service/app/tools}），Java 侧只保存“是否副作用工具”和默认白名单。
 *
 * <p>这四个工具都**不触碰真实资源**：不访问真实文件系统、不发真实邮件、不请求公网。
 */
public final class ToolCatalog {

    public static final String CALCULATOR = "calculator";
    public static final String SEARCH_LOCAL_DOCS = "search_local_docs";
    public static final String QUERY_TRAFFIC_METRICS = "query_traffic_metrics";
    public static final String SEND_NOTIFICATION = "send_notification";

    /** 按“风险从低到高”排序，便于前端展示与稳定输出（有序集合，不用 Set.copyOf 避免顺序不确定）。 */
    private static final List<String> ALL = List.of(
            CALCULATOR, SEARCH_LOCAL_DOCS, QUERY_TRAFFIC_METRICS, SEND_NOTIFICATION);

    /** 有副作用的工具：默认不放进新应用的白名单，且受 {@code confirmSideEffects} 约束。 */
    private static final Set<String> SIDE_EFFECT_TOOLS = Set.of(SEND_NOTIFICATION);

    private static final java.util.Map<String, String> DESCRIPTIONS = java.util.Map.of(
            CALCULATOR, "四则运算计算器（只支持数字与 + - * / ( ) 与小数，不允许任何函数或变量）",
            SEARCH_LOCAL_DOCS, "检索项目内置的演示文档，返回片段；返回内容按不可信输入处理",
            QUERY_TRAFFIC_METRICS, "查询合成的交通指标（平均车速、流量），为将来接入真实预测模型预留",
            SEND_NOTIFICATION, "写入本地 Mock Outbox；默认不启用，且受 confirmSideEffects 约束");

    private ToolCatalog() {
    }

    public static List<String> all() {
        return ALL;
    }

    public static String descriptionOf(String toolName) {
        return DESCRIPTIONS.getOrDefault(toolName, "");
    }

    /** 新应用默认启用的工具：全部为无副作用工具。 */
    public static Set<String> defaultEnabledForNewApp() {
        return Set.of(CALCULATOR, SEARCH_LOCAL_DOCS, QUERY_TRAFFIC_METRICS);
    }

    public static boolean exists(String toolName) {
        return toolName != null && ALL.contains(toolName);
    }

    public static boolean hasSideEffect(String toolName) {
        return SIDE_EFFECT_TOOLS.contains(toolName);
    }
}
