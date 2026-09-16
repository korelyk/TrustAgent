package io.trustagent.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.app.ToolCatalog;
import io.trustagent.audit.AuditHashChain;
import io.trustagent.audit.AuditService;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.PageResult;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.eval.dto.EvalDtos;
import io.trustagent.eval.mapper.EvalCaseMapper;
import io.trustagent.eval.mapper.EvalDatasetMapper;
import io.trustagent.eval.mapper.EvalDatasetRow;
import io.trustagent.eval.mapper.EvalQueryMapper;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测集：导入、校验、查询。
 *
 * <h3>导入的失败语义：逐行跳过，不整包回滚</h3>
 * 这个选择是刻意的。整包回滚对"人工维护的 JSONL"极其不友好：
 * 用户改了一行、上传、被拒、再改一行、再上传…… 每次都要重新读一遍"哪一行错了"。
 * 逐行跳过 + 返回逐行原因，用户一次就能把所有坏行修完。
 * <b>代价</b>是可能出现"部分导入"的数据集，因此：
 * <ul>
 *   <li>响应里明确给出 {@code totalLines / imported / rejected} 与逐行错误，绝不假装成功；</li>
 *   <li>全部行都失败时抛 400 {@code DATASET_IMPORT_FAILED}，并把报告放进 {@code data}，
 *       避免留下一个 0 条用例的"幽灵数据集"。</li>
 * </ul>
 *
 * <h3>为什么在 Java 侧校验而不是 Python 侧</h3>
 * 评测集的"合法取值"是由控制面的业务定义决定的（有哪些工具、有哪些类别）。
 * 校验放在拥有定义的一侧，才不会出现"Python 放行了 Java 不认识的工具名"这种漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    /** 单行 prompt / context 的最大长度。 */
    private static final int MAX_TEXT_LENGTH = 8000;
    /** 返回给调用方的错误行数上限：再多也没人看，反而把响应体撑大。 */
    private static final int MAX_REPORTED_ERRORS = 200;
    /** 错误原因的最大长度，避免把整行原文（可能含敏感信息）回显出去。 */
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAGS_JOINED_LENGTH = 512;

    private final EvalDatasetMapper datasetMapper;
    private final EvalCaseMapper caseMapper;
    private final EvalQueryMapper queryMapper;
    private final AuditService auditService;
    private final TrustAgentProperties props;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 导入

    @Transactional
    public EvalDtos.ImportReport importDataset(EvalDtos.DatasetImportRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (!EvalVocabulary.sourceExists(request.sourceCategory())) {
            throw BizException.badRequest("未知的 sourceCategory: " + request.sourceCategory()
                    + "，可选值: SYNTHETIC_SELF_AUTHORED / PUBLIC_BENCHMARK_ADAPTED");
        }

        List<String> dataLines = nonBlankLines(request.content());
        if (dataLines.isEmpty()) {
            throw new BizException(ErrorCode.DATASET_IMPORT_FAILED,
                    "评测集内容为空（没有任何非空行）", null);
        }

        int maxCases = props.getEval().getMaxCasesPerDataset();
        List<EvalCase> accepted = new ArrayList<>();
        List<EvalDtos.ImportErrorView> errors = new ArrayList<>();
        Set<String> seenCaseIds = new HashSet<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();

        int lineNumber = 0;
        for (String rawLine : dataLines) {
            lineNumber++;
            if (accepted.size() >= maxCases) {
                addError(errors, lineNumber, null,
                        "超过单数据集用例上限 " + maxCases + " 条，该行及其后未被导入");
                continue;
            }
            try {
                EvalCase parsed = parseCase(rawLine, seenCaseIds);
                parsed.setDatasetId(null); // 稍后统一回填
                accepted.add(parsed);
                categoryCounts.merge(parsed.getCategory(), 1, Integer::sum);
            } catch (InvalidCaseException ex) {
                addError(errors, lineNumber, ex.caseId, ex.getMessage());
            }
        }

        if (accepted.isEmpty()) {
            EvalDtos.ImportReport failed = new EvalDtos.ImportReport(null, dataLines.size(), 0,
                    dataLines.size(), categoryCounts, errors);
            throw new BizException(ErrorCode.DATASET_IMPORT_FAILED,
                    "评测集没有任何合法用例，共 " + dataLines.size() + " 行全部被拒", failed);
        }

        EvalDataset dataset = new EvalDataset();
        dataset.setName(request.name());
        dataset.setDescription(request.description());
        dataset.setSourceCategory(request.sourceCategory());
        // 校验和针对"原始内容"而不是"已解析用例"，这样用户改一个空格也能被识别为不同版本
        dataset.setChecksum(AuditHashChain.sha256Hex(request.content()));
        dataset.setCaseCount(accepted.size());
        dataset.setOwnerId(principal.getId());
        datasetMapper.insert(dataset);

        for (EvalCase item : accepted) {
            item.setDatasetId(dataset.getId());
            caseMapper.insert(item);
        }

        auditService.record("EVAL_DATASET_IMPORTED", "eval_dataset", String.valueOf(dataset.getId()), Map.of(
                "name", dataset.getName(),
                "sourceCategory", dataset.getSourceCategory(),
                "imported", accepted.size(),
                "rejected", dataLines.size() - accepted.size(),
                "checksum", String.valueOf(dataset.getChecksum())));

        log.info("导入评测集 id={} name={} imported={} rejected={} owner={}",
                dataset.getId(), dataset.getName(), accepted.size(),
                dataLines.size() - accepted.size(), principal.getUsername());

        return new EvalDtos.ImportReport(dataset.getId(), dataLines.size(), accepted.size(),
                dataLines.size() - accepted.size(), categoryCounts, errors);
    }

    // ------------------------------------------------------------------ 查询

    public List<EvalDtos.DatasetView> listDatasets() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean seeAll = principal.isAdmin() || principal.getRoles().contains("AUDITOR");
        return queryMapper.selectDatasetRows(principal.getId(), seeAll ? 1 : 0).stream()
                .map(this::toDatasetView)
                .toList();
    }

    public EvalDataset getByIdOrThrow(Long id) {
        EvalDataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw BizException.notFound("评测集不存在: " + id);
        }
        return dataset;
    }

    /** 读取评测集并校验可见性（ADMIN / AUDITOR 可见全部，其余仅属主）。 */
    public EvalDataset requireAccessible(Long id) {
        EvalDataset dataset = getByIdOrThrow(id);
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean readOnlyAllowed = principal.getRoles().contains("AUDITOR");
        if (!principal.isAdmin() && !readOnlyAllowed && !principal.getId().equals(dataset.getOwnerId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该评测集（非属主）");
        }
        return dataset;
    }

    public PageResult<EvalDtos.CaseView> listCases(Long datasetId, long page, long size) {
        requireAccessible(datasetId);
        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<EvalCase> query = Wrappers.<EvalCase>lambdaQuery()
                .eq(EvalCase::getDatasetId, datasetId)
                .orderByAsc(EvalCase::getId);
        IPage<EvalCase> result = caseMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), query);
        return PageResult.of(result.getRecords().stream().map(this::toCaseView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 供内部接口使用：Worker 拉取全量用例，**不做属主校验**（网关是 InternalTokenFilter）。 */
    public List<EvalCase> casesForWorker(Long datasetId) {
        EvalDataset dataset = getByIdOrThrow(datasetId);
        List<EvalCase> cases = caseMapper.selectByDatasetId(datasetId);
        int maxCases = props.getEval().getMaxCasesPerDataset();
        if (cases.size() > maxCases) {
            // 导入时已限制过，这里再挡一次：防止有人直接改库塞进超量数据
            throw new BizException(ErrorCode.CONFLICT,
                    "评测集用例数 " + cases.size() + " 超过上限 " + maxCases);
        }
        return cases;
    }

    /** 数据集中"期望含敏感信息"的 caseId 集合，用于 PII 泄露率的分母。 */
    public Set<String> sensitiveCaseIds(Long datasetId) {
        Set<String> ids = new LinkedHashSet<>();
        for (EvalCase item : caseMapper.selectByDatasetId(datasetId)) {
            if (Boolean.TRUE.equals(item.getContainsSensitiveData()) && item.getCaseId() != null) {
                ids.add(item.getCaseId());
            }
        }
        return ids;
    }

    public Map<String, Boolean> sensitivityFlags(Long datasetId) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (EvalCase item : caseMapper.selectByDatasetId(datasetId)) {
            flags.put(item.getCaseId(), Boolean.TRUE.equals(item.getContainsSensitiveData()));
        }
        return flags;
    }

    // ------------------------------------------------------------------ 解析与校验

    /**
     * 解析并校验一行 JSONL。
     *
     * <p>校验顺序刻意是"先便宜后昂贵"：JSON 解析 → caseId → category → prompt → action → tool → tags。
     * 最贵的正则/集合查找放在最后，坏行能尽快被拒。
     */
    private EvalCase parseCase(String line, Set<String> seenCaseIds) {
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (Exception ex) {
            throw new InvalidCaseException(null, "JSON 解析失败: " + truncate(ex.getMessage()));
        }
        if (node == null || !node.isObject()) {
            throw new InvalidCaseException(null, "每一行必须是一个 JSON 对象");
        }

        String caseId = text(node, "caseId");
        if (caseId == null || caseId.isBlank()) {
            throw new InvalidCaseException(null, "caseId 不能为空");
        }
        if (caseId.length() > 64) {
            throw new InvalidCaseException(null, "caseId 长度不得超过 64");
        }
        if (!seenCaseIds.add(caseId)) {
            throw new InvalidCaseException(caseId, "caseId 在本数据集内重复");
        }

        String category = text(node, "category");
        if (!EvalVocabulary.categoryExists(category)) {
            throw new InvalidCaseException(caseId, "未知 category: " + category
                    + "，可选值: " + String.join("/", EvalVocabulary.allCategories()));
        }

        String prompt = text(node, "prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidCaseException(caseId, "prompt 不能为空");
        }
        if (prompt.length() > MAX_TEXT_LENGTH) {
            throw new InvalidCaseException(caseId, "prompt 长度 " + prompt.length()
                    + " 超过上限 " + MAX_TEXT_LENGTH);
        }

        String context = text(node, "context");
        if (context != null && context.length() > MAX_TEXT_LENGTH) {
            throw new InvalidCaseException(caseId, "context 长度 " + context.length()
                    + " 超过上限 " + MAX_TEXT_LENGTH);
        }

        String expectedAction = text(node, "expectedAction");
        if (!EvalVocabulary.actionExists(expectedAction)) {
            throw new InvalidCaseException(caseId, "未知 expectedAction: " + expectedAction
                    + "，可选值: ALLOW/BLOCK/MASK/CALL_TOOL");
        }

        String expectedTool = text(node, "expectedTool");
        if (expectedTool != null && !expectedTool.isBlank()) {
            if (!ToolCatalog.exists(expectedTool)) {
                // 让坏行在导入期就暴露，而不是等执行时才发现工具名写错
                throw new InvalidCaseException(caseId, "未知 expectedTool: " + expectedTool
                        + "，可选值: " + ToolCatalog.all());
            }
        } else {
            expectedTool = null;
        }
        if (EvalVocabulary.ACTION_CALL_TOOL.equals(expectedAction) && expectedTool == null) {
            throw new InvalidCaseException(caseId, "expectedAction=CALL_TOOL 时必须给出 expectedTool");
        }

        boolean containsSensitive = node.hasNonNull("containsSensitiveData")
                && node.get("containsSensitiveData").asBoolean(false);

        String tags = joinTags(node.get("tags"), caseId);

        EvalCase item = new EvalCase();
        item.setCaseId(caseId);
        item.setCategory(category);
        item.setPrompt(prompt);
        item.setContext(context);
        item.setExpectedAction(expectedAction);
        item.setExpectedTool(expectedTool);
        item.setContainsSensitiveData(containsSensitive);
        item.setTags(tags);
        return item;
    }

    private String joinTags(JsonNode tagsNode, String caseId) {
        if (tagsNode == null || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw new InvalidCaseException(caseId, "tags 必须是字符串数组");
        }
        if (tagsNode.size() > MAX_TAGS) {
            throw new InvalidCaseException(caseId, "tags 最多 " + MAX_TAGS + " 个");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            if (!tag.isTextual()) {
                throw new InvalidCaseException(caseId, "tags 必须是字符串数组");
            }
            if (tag.asText().length() > 32) {
                throw new InvalidCaseException(caseId, "单个 tag 长度不得超过 32");
            }
            tags.add(tag.asText());
        }
        if (tags.isEmpty()) {
            return null;
        }
        String joined = String.join(",", tags);
        if (joined.length() > MAX_TAGS_JOINED_LENGTH) {
            throw new InvalidCaseException(caseId, "tags 拼接后长度超过 " + MAX_TAGS_JOINED_LENGTH);
        }
        return joined;
    }

    // ------------------------------------------------------------------ 内部工具方法

    private static List<String> nonBlankLines(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return Arrays.stream(normalized.split("\n", -1))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.asText();
    }

    private static void addError(List<EvalDtos.ImportErrorView> errors, int lineNumber, String caseId,
                                 String reason) {
        if (errors.size() >= MAX_REPORTED_ERRORS) {
            return;
        }
        errors.add(new EvalDtos.ImportErrorView(lineNumber, caseId, truncate(reason)));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH) + "...";
    }

    private EvalDtos.DatasetView toDatasetView(EvalDatasetRow row) {
        return new EvalDtos.DatasetView(row.getId(), row.getName(), row.getDescription(),
                row.getSourceCategory(), row.getChecksum(), row.getCaseCount(), row.getOwnerId(),
                row.getOwnerName(), row.getCreatedAt());
    }

    private EvalDtos.CaseView toCaseView(EvalCase item) {
        return new EvalDtos.CaseView(item.getCaseId(), item.getCategory(),
                EvalVocabulary.describe(item.getCategory()), item.getPrompt(), item.getContext(),
                item.getExpectedAction(), item.getExpectedTool(), item.getContainsSensitiveData(),
                splitTags(item.getTags()));
    }

    private static List<String> splitTags(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 行级校验失败。用异常而不是返回错误对象，是为了让"校验通过"路径保持线性可读。 */
    private static final class InvalidCaseException extends RuntimeException {

        private final transient String caseId;

        InvalidCaseException(String caseId, String reason) {
            super(reason);
            this.caseId = caseId;
        }
    }
}
