package io.trustagent.eval;

import io.trustagent.eval.dto.EvalDtos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 评测报告导出（JSON / CSV）。
 *
 * <h3>为什么 CSV 里要做"公式注入"防护</h3>
 * 导出的报告会被人在 Excel 里打开。如果某个字段以 {@code = + - @} 开头，
 * Excel 会把它当**公式**执行（历史上被用于 DDE 命令注入）。
 * 用例文本来自数据集、可能包含攻击者可控内容（评测集本来就是攻击样本集），
 * 所以这里对这类字段加前缀 {@code '} 强制按文本处理。
 * 这是"导出功能"里最容易被忽略的一类漏洞，代价只有一行代码。
 *
 * <h3>为什么加 UTF-8 BOM</h3>
 * Excel 在 Windows 上打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码，
 * 用户会以为"系统导出的数据是坏的"。一个 BOM 就解决。
 */
@Component
public class EvalReportExporter {

    /** 列顺序固定：报告是给人和脚本都看的东西，列顺序抖动会破坏下游解析。 */
    private static final List<String> CSV_COLUMNS = List.of(
            "runId", "caseId", "category", "guardEnabled", "expectedAction", "actualAction",
            "expectedTool", "actualTool", "blocked", "detectedCategories", "piiLeaked", "passed",
            "latencyMs", "errorMessage");

    private static final String UTF8_BOM = "\uFEFF";
    private static final String LINE_SEPARATOR = "\r\n";

    /** 生成 CSV 文本（含 BOM）。 */
    public String toCsv(Long runId, List<EvalCaseResult> results) {
        StringBuilder sb = new StringBuilder(UTF8_BOM);
        sb.append(String.join(",", CSV_COLUMNS)).append(LINE_SEPARATOR);
        for (EvalCaseResult row : results) {
            List<String> values = new ArrayList<>(CSV_COLUMNS.size());
            values.add(String.valueOf(runId));
            values.add(row.getCaseId());
            values.add(row.getCategory());
            values.add(String.valueOf(Boolean.TRUE.equals(row.getGuardEnabled())));
            values.add(row.getExpectedAction());
            values.add(row.getActualAction());
            values.add(row.getExpectedTool());
            values.add(row.getActualTool());
            values.add(String.valueOf(Boolean.TRUE.equals(row.getBlocked())));
            values.add(row.getDetectedCategories());
            values.add(String.valueOf(Boolean.TRUE.equals(row.getPiiLeaked())));
            values.add(String.valueOf(Boolean.TRUE.equals(row.getPassed())));
            values.add(String.valueOf(row.getLatencyMs() == null ? 0 : row.getLatencyMs()));
            values.add(row.getErrorMessage());

            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escapeCsv(values.get(i)));
            }
            sb.append(LINE_SEPARATOR);
        }
        return sb.toString();
    }

    /**
     * 组装 JSON 报告。
     *
     * <p>结构：{@code run}（任务与指标快照）+ {@code summary}（按类别的通过情况）+
     * {@code results}（逐条明细）。既有人看的汇总，也有脚本用的明细。
     */
    public Map<String, Object> toJsonReport(EvalDtos.RunView runView, List<EvalCaseResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run", runView);
        report.put("summary", summarize(results));
        report.put("categoryBreakdown", breakdownByCategory(results));
        report.put("results", results.stream().map(this::toRowMap).toList());
        return report;
    }

    private Map<String, Object> summarize(List<EvalCaseResult> results) {
        int passed = 0;
        int failed = 0;
        int errored = 0;
        for (EvalCaseResult row : results) {
            if (Boolean.TRUE.equals(row.getPassed())) {
                passed++;
            } else {
                failed++;
            }
            if (row.getErrorMessage() != null && !row.getErrorMessage().isBlank()) {
                errored++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalResults", results.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("executionErrors", errored);
        return summary;
    }

    /** 按类别聚合：能一眼看出"哪一类攻击漏得最多"，这比一个总准确率有用得多。 */
    private Map<String, Object> breakdownByCategory(List<EvalCaseResult> results) {
        Map<String, int[]> accumulator = new LinkedHashMap<>();
        for (EvalCaseResult row : results) {
            String key = row.getCategory() + "|" + Boolean.TRUE.equals(row.getGuardEnabled());
            int[] counters = accumulator.computeIfAbsent(key, k -> new int[2]);
            counters[0]++;
            if (Boolean.TRUE.equals(row.getPassed())) {
                counters[1]++;
            }
        }
        Map<String, Object> breakdown = new LinkedHashMap<>();
        accumulator.forEach((key, counters) -> {
            String[] parts = key.split("\\|", 2);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", parts[0]);
            item.put("categoryDescription", EvalVocabulary.describe(parts[0]));
            item.put("guardEnabled", Boolean.parseBoolean(parts[1]));
            item.put("total", counters[0]);
            item.put("passed", counters[1]);
            item.put("passRate", EvalMetricsCalculator.round(
                    counters[0] == 0 ? 0.0 : (double) counters[1] / counters[0]));
            breakdown.put(key, item);
        });
        return breakdown;
    }

    private Map<String, Object> toRowMap(EvalCaseResult row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caseId", row.getCaseId());
        map.put("category", row.getCategory());
        map.put("guardEnabled", Boolean.TRUE.equals(row.getGuardEnabled()));
        map.put("expectedAction", row.getExpectedAction());
        map.put("actualAction", row.getActualAction());
        map.put("expectedTool", row.getExpectedTool());
        map.put("actualTool", row.getActualTool());
        map.put("blocked", Boolean.TRUE.equals(row.getBlocked()));
        map.put("detectedCategories", splitCsv(row.getDetectedCategories()));
        map.put("piiLeaked", Boolean.TRUE.equals(row.getPiiLeaked()));
        map.put("passed", Boolean.TRUE.equals(row.getPassed()));
        map.put("latencyMs", row.getLatencyMs() == null ? 0 : row.getLatencyMs());
        map.put("errorMessage", row.getErrorMessage());
        return map;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 转义 + 公式注入防护。顺序很重要：先防注入（改内容），再做引号转义（改表示）。 */
    private static String escapeCsv(String raw) {
        String value = neutralizeFormula(raw == null ? "" : raw);
        boolean needsQuoting = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}
