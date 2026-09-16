package io.trustagent.securityevent;

import io.trustagent.common.SensitiveDataMasker;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.securityevent.mapper.SecurityEventMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 安全事件落库。
 *
 * <p>所有入口都强制对 {@code evidence} 做脱敏 + 截断，即使调用方已经把原文传进来也不会泄露。
 * 这是"把安全要求做成 API 约束"而不是"依赖调用方自觉"的做法。
 */
@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private static final int EVIDENCE_MAX_LENGTH = 1000;

    /**
     * 各字段的最大长度，与 {@code V1__baseline.sql} 里 {@code security_event} 的列宽严格对应。
     *
     * <p>为什么要显式裁剪：MySQL 严格模式下，超长值是**错误**而不是自动截断。
     * 而调用方 {@code ChatService.chat} 是 {@code @Transactional} 的——
     * 一条超宽的 {@code ruleId} 会让整个请求事务回滚，表现为
     * "执行面报了个字段过长的安全事件，用户连对话都发不出去"，
     * 而且错误信息指向 SQL 约束，与真正的原因（事件字段超长）隔了一层。
     * 把长度约束收敛到这一个方法里，调用方就不必各自小心。
     */
    private static final int MAX_EVENT_TYPE = 48;
    private static final int MAX_SEVERITY = 16;
    private static final int MAX_DETECTOR = 48;
    private static final int MAX_RULE_ID = 64;
    private static final int MAX_ACTION = 32;

    private static final Logger log = LoggerFactory.getLogger(SecurityEventService.class);

    private final SecurityEventMapper mapper;
    private final TrustAgentProperties props;

    public void record(String traceId, Long appId, String eventType, String detector, String ruleId,
                       String evidence, String actionTaken) {
        record(traceId, appId, eventType, SecurityEventType.severityOf(eventType), detector, ruleId,
                evidence, actionTaken);
    }

    public void record(String traceId, Long appId, String eventType, String severity, String detector,
                       String ruleId, String evidence, String actionTaken) {
        SecurityEvent event = new SecurityEvent();
        event.setTraceId(clip(traceId, 64));
        event.setAppId(appId);
        // eventType / severity 是 NOT NULL 列，调用方已保证非空；这里只负责宽度
        event.setEventType(clip(eventType, MAX_EVENT_TYPE));
        event.setSeverity(clip(severity, MAX_SEVERITY));
        event.setDetector(clip(detector, MAX_DETECTOR));
        event.setRuleId(clip(ruleId, MAX_RULE_ID));
        event.setEvidenceMasked(SensitiveDataMasker.preview(evidence, EVIDENCE_MAX_LENGTH));
        event.setActionTaken(clip(actionTaken, MAX_ACTION));
        event.setCreatedAt(LocalDateTime.now());
        mapper.insert(event);
        log.info("安全事件 traceId={} appId={} type={} severity={} rule={} action={}",
                traceId, appId, eventType, severity, ruleId, actionTaken);
    }

    public List<SecurityEvent> byTraceId(String traceId) {
        return mapper.selectByTraceId(traceId);
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("byType", toPairs(mapper.countByEventType()));
        stats.put("bySeverity", toPairs(mapper.countBySeverity()));
        stats.put("byRule", toPairs(mapper.countByRuleId()));
        return stats;
    }

    /** 事件证据字段的最大长度，供测试断言使用。 */
    public int evidenceMaxLength() {
        return EVIDENCE_MAX_LENGTH;
    }

    public int previewMaxLength() {
        return props.getMasking().getPreviewMaxLength();
    }

    /** 按列宽裁剪；null 原样返回（可空列） */
    private static String clip(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static List<Map<String, Object>> toPairs(List<SecurityEventMapper.CountRow> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row.getLabel());
            item.put("total", row.getTotal());
            return item;
        }).toList();
    }
}
