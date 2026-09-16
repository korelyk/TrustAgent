package io.trustagent.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.app.dto.AppDtos;
import io.trustagent.app.mapper.AgentAppMapper;
import io.trustagent.app.mapper.AgentAppQueryMapper;
import io.trustagent.app.mapper.AgentAppRow;
import io.trustagent.app.mapper.AgentToolMapper;
import io.trustagent.app.mapper.SecurityPolicyMapper;
import io.trustagent.audit.AuditService;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAppService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^\\*?\\.?([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$");
    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private final AgentAppMapper appMapper;
    private final AgentToolMapper toolMapper;
    private final SecurityPolicyMapper policyMapper;
    private final AgentAppQueryMapper queryMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 查询

    public List<AppDtos.AgentAppView> listApps() {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean seeAll = principal.isAdmin() || principal.getRoles().contains("AUDITOR");
        List<AgentAppRow> rows = queryMapper.selectAppRows(principal.getId(), seeAll ? 1 : 0);
        return rows.stream().map(this::toView).toList();
    }

    public AgentApp getByIdOrThrow(Long id) {
        AgentApp app = appMapper.selectById(id);
        if (app == null) {
            throw BizException.notFound("Agent 应用不存在: " + id);
        }
        return app;
    }

    /** 读取应用并校验“可见/可改”权限。 */
    public AgentApp requireAccessible(Long id) {
        AgentApp app = getByIdOrThrow(id);
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        boolean readOnlyAllowed = principal.getRoles().contains("AUDITOR");
        if (!principal.isAdmin() && !readOnlyAllowed && !principal.getId().equals(app.getOwnerId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该 Agent 应用（非属主）");
        }
        return app;
    }

    /** 写操作：只有属主或 ADMIN 可以，AUDITOR 一律拒绝。 */
    public AgentApp requireWritable(Long id) {
        AgentApp app = getByIdOrThrow(id);
        SecurityUtils.requireOwnerOrAdmin(app.getOwnerId(), "Agent 应用");
        return app;
    }

    public AppDtos.AgentAppView detail(Long id) {
        AgentApp app = requireAccessible(id);
        return new AppDtos.AgentAppView(app.getId(), app.getCode(), app.getName(), app.getDescription(),
                app.getOwnerId(), null, app.getSystemPrompt(), app.getStatus(),
                toolMapper.selectEnabledByAppId(id).size(), app.getCreatedAt(), app.getUpdatedAt());
    }

    public List<AppDtos.ToolView> listTools(Long appId) {
        requireAccessible(appId);
        return toolMapper.selectByAppId(appId).stream()
                .map(t -> new AppDtos.ToolView(t.getToolName(), t.getEnabled(), t.getParamSchema(),
                        t.getDescription(), ToolCatalog.hasSideEffect(t.getToolName())))
                .toList();
    }

    /** 执行面真正拿到的“允许调用工具”清单。 */
    public List<String> enabledToolNames(Long appId) {
        return toolMapper.selectEnabledByAppId(appId).stream().map(AgentTool::getToolName).toList();
    }

    public Map<String, String> toolSchemas(Long appId) {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (AgentTool tool : toolMapper.selectEnabledByAppId(appId)) {
            if (tool.getParamSchema() != null && !tool.getParamSchema().isBlank()) {
                schemas.put(tool.getToolName(), tool.getParamSchema());
            }
        }
        return schemas;
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public AppDtos.AgentAppView create(AppDtos.AgentAppCreateRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        if (appMapper.selectByCode(request.code()) != null) {
            throw new BizException(ErrorCode.DUPLICATE_RESOURCE, "应用编码已存在: " + request.code());
        }
        AgentApp app = new AgentApp();
        app.setCode(request.code());
        app.setName(request.name());
        app.setDescription(request.description());
        app.setOwnerId(principal.getId());
        app.setSystemPrompt(request.systemPrompt());
        app.setStatus(STATUS_ACTIVE);
        appMapper.insert(app);

        seedDefaultTools(app.getId());
        createDefaultPolicy(app.getId());

        auditService.record("AGENT_APP_CREATED", "agent_app", String.valueOf(app.getId()),
                Map.of("code", app.getCode(), "name", app.getName()));
        log.info("创建 Agent 应用 id={} code={} owner={}", app.getId(), app.getCode(), principal.getUsername());
        return detail(app.getId());
    }

    @Transactional
    public AppDtos.AgentAppView update(Long id, AppDtos.AgentAppUpdateRequest request) {
        AgentApp app = requireWritable(id);
        app.setName(request.name());
        app.setDescription(request.description());
        app.setSystemPrompt(request.systemPrompt());
        app.setStatus(request.status());
        appMapper.updateById(app);
        auditService.record("AGENT_APP_UPDATED", "agent_app", String.valueOf(id), Map.of(
                "name", app.getName(), "status", app.getStatus()));
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        AgentApp app = requireWritable(id);
        toolMapper.delete(Wrappers.<AgentTool>lambdaQuery().eq(AgentTool::getAppId, id));
        // 不级联删除 Trace / 评测结果：审计与评测留痕必须保留，否则会掩盖历史事实。
        appMapper.deleteById(id);
        auditService.record("AGENT_APP_DELETED", "agent_app", String.valueOf(id),
                Map.of("code", app.getCode(), "name", app.getName()));
    }

    /** 覆盖式保存工具配置。 */
    @Transactional
    public List<AppDtos.ToolView> saveTools(Long appId, List<AppDtos.ToolConfigRequest> configs) {
        requireWritable(appId);
        List<String> invalid = configs.stream()
                .map(AppDtos.ToolConfigRequest::toolName)
                .filter(name -> !ToolCatalog.exists(name))
                .toList();
        if (!invalid.isEmpty()) {
            throw BizException.badRequest("未知工具: " + invalid + "，可选: " + ToolCatalog.all());
        }

        toolMapper.delete(Wrappers.<AgentTool>lambdaQuery().eq(AgentTool::getAppId, appId));
        for (AppDtos.ToolConfigRequest config : configs) {
            String schema = normalizeSchema(config.toolName(), config.paramSchema());
            AgentTool tool = new AgentTool();
            tool.setAppId(appId);
            tool.setToolName(config.toolName());
            tool.setEnabled(Boolean.TRUE.equals(config.enabled()));
            tool.setParamSchema(schema);
            tool.setDescription(config.description() == null
                    ? ToolCatalog.descriptionOf(config.toolName()) : config.description());
            toolMapper.insert(tool);
        }
        auditService.record("AGENT_TOOLS_UPDATED", "agent_app", String.valueOf(appId),
                Map.of("tools", configs.stream()
                        .filter(c -> Boolean.TRUE.equals(c.enabled()))
                        .map(AppDtos.ToolConfigRequest::toolName).toList()));
        return listTools(appId);
    }

    // ------------------------------------------------------------------ 策略

    public SecurityPolicy getPolicyOrThrow(Long appId) {
        SecurityPolicy policy = policyMapper.selectByAppId(appId);
        if (policy == null) {
            throw BizException.notFound("安全策略不存在（应用未初始化）: " + appId);
        }
        return policy;
    }

    public AppDtos.PolicyView getPolicy(Long appId) {
        requireAccessible(appId);
        return toPolicyView(getPolicyOrThrow(appId));
    }

    @Transactional
    public AppDtos.PolicyView savePolicy(Long appId, AppDtos.PolicyRequest request) {
        SecurityPolicy policy = getPolicyOrThrow(appId);
        requireWritable(appId);

        if (request.version() != null && !request.version().equals(policy.getVersion())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "安全策略已被他人修改（当前版本 " + policy.getVersion() + "，你提交的是 "
                            + request.version() + "），请刷新后重试");
        }

        List<String> domains = normalizeDomains(request.domainWhitelist());
        List<String> deniedParams = normalizeDeniedParams(request.deniedParamRules());

        Map<String, Object> before = policySnapshot(policy);

        policy.setGuardEnabled(request.guardEnabled());
        policy.setMaxToolCalls(request.maxToolCalls());
        policy.setMaxInputLength(request.maxInputLength());
        policy.setTaskTimeoutMs(request.taskTimeoutMs());
        policy.setDomainWhitelist(String.join(",", domains));
        policy.setDeniedParamRules(String.join(",", deniedParams));
        policy.setConfirmSideEffects(request.confirmSideEffects());
        policy.setInjectionDetectionEnabled(request.injectionDetectionEnabled());
        policy.setPiiMaskingEnabled(request.piiMaskingEnabled());
        policy.setOutputGuardEnabled(request.outputGuardEnabled());
        policy.setVersion(policy.getVersion() + 1);
        policyMapper.updateById(policy);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("version", policy.getVersion());
        auditDetail.put("before", before);
        auditDetail.put("after", policySnapshot(policy));
        auditService.record("SECURITY_POLICY_UPDATED", "security_policy", String.valueOf(appId), auditDetail);

        return toPolicyView(policy);
    }

    // ------------------------------------------------------------------ 内部

    private void seedDefaultTools(Long appId) {
        for (String toolName : ToolCatalog.all()) {
            AgentTool tool = new AgentTool();
            tool.setAppId(appId);
            tool.setToolName(toolName);
            // 有副作用的工具默认关闭：默认安全（secure by default）
            tool.setEnabled(ToolCatalog.defaultEnabledForNewApp().contains(toolName));
            tool.setParamSchema(null);
            tool.setDescription(ToolCatalog.descriptionOf(toolName));
            toolMapper.insert(tool);
        }
    }

    private void createDefaultPolicy(Long appId) {
        SecurityPolicy policy = new SecurityPolicy();
        policy.setAppId(appId);
        policy.setVersion(1);
        policy.setGuardEnabled(true);
        policy.setMaxToolCalls(3);
        policy.setMaxInputLength(4000);
        policy.setTaskTimeoutMs(15000);
        policy.setDomainWhitelist("docs.trustagent.local");
        policy.setDeniedParamRules("password,token,secret,api_key");
        policy.setConfirmSideEffects(true);
        policy.setInjectionDetectionEnabled(true);
        policy.setPiiMaskingEnabled(true);
        policy.setOutputGuardEnabled(true);
        policyMapper.insert(policy);
    }

    private List<String> normalizeDomains(List<String> domains) {
        if (domains == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : domains) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String domain = raw.trim().toLowerCase();
            if (!DOMAIN_PATTERN.matcher(domain).matches()) {
                throw BizException.badRequest("域名白名单格式非法: " + raw);
            }
            if (!cleaned.contains(domain)) {
                cleaned.add(domain);
            }
        }
        return cleaned;
    }

    private List<String> normalizeDeniedParams(List<String> params) {
        if (params == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : params) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            if (!PARAM_NAME_PATTERN.matcher(name).matches()) {
                throw BizException.badRequest("敏感参数名格式非法（只允许字母数字下划线）: " + raw);
            }
            if (!cleaned.contains(name)) {
                cleaned.add(name);
            }
        }
        return cleaned;
    }

    /** param_schema 允许为空：为空表示“继承执行面的默认 Schema”，避免两处 Schema 定义漂移。 */
    private String normalizeSchema(String toolName, String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(schema);
        } catch (Exception e) {
            throw BizException.badRequest("工具 " + toolName + " 的参数 Schema 不是合法 JSON");
        }
        return schema;
    }

    private AppDtos.AgentAppView toView(AgentAppRow row) {
        return new AppDtos.AgentAppView(row.getId(), row.getCode(), row.getName(), row.getDescription(),
                row.getOwnerId(), row.getOwnerName(), null, row.getStatus(),
                row.getEnabledToolCount(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private AppDtos.PolicyView toPolicyView(SecurityPolicy policy) {
        return new AppDtos.PolicyView(policy.getAppId(), policy.getVersion(), policy.getGuardEnabled(),
                policy.getMaxToolCalls(), policy.getMaxInputLength(), policy.getTaskTimeoutMs(),
                splitCsv(policy.getDomainWhitelist()), splitCsv(policy.getDeniedParamRules()),
                policy.getConfirmSideEffects(), policy.getInjectionDetectionEnabled(),
                policy.getPiiMaskingEnabled(), policy.getOutputGuardEnabled(), policy.getUpdatedAt());
    }

    private Map<String, Object> policySnapshot(SecurityPolicy policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("guardEnabled", policy.getGuardEnabled());
        snapshot.put("maxToolCalls", policy.getMaxToolCalls());
        snapshot.put("maxInputLength", policy.getMaxInputLength());
        snapshot.put("taskTimeoutMs", policy.getTaskTimeoutMs());
        snapshot.put("domainWhitelist", policy.getDomainWhitelist());
        snapshot.put("deniedParamRules", policy.getDeniedParamRules());
        snapshot.put("confirmSideEffects", policy.getConfirmSideEffects());
        snapshot.put("injectionDetectionEnabled", policy.getInjectionDetectionEnabled());
        snapshot.put("piiMaskingEnabled", policy.getPiiMaskingEnabled());
        snapshot.put("outputGuardEnabled", policy.getOutputGuardEnabled());
        return snapshot;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
