package io.trustagent.securityevent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.securityevent.SecurityEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SecurityEventMapper extends BaseMapper<SecurityEvent> {

    @Select("select * from security_event where trace_id = #{traceId} order by id")
    List<SecurityEvent> selectByTraceId(@Param("traceId") String traceId);

    /**
     * 按事件类型统计。用数据库聚合而不是把全表拉到内存里 group：
     * 事件表是只追加的大表，全表扫描会随运行时间线性变慢。
     */
    @Select("""
            select event_type as label, count(1) as total
            from security_event
            group by event_type
            order by total desc, label asc
            """)
    List<CountRow> countByEventType();

    @Select("""
            select severity as label, count(1) as total
            from security_event
            group by severity
            order by total desc, label asc
            """)
    List<CountRow> countBySeverity();

    @Select("""
            select rule_id as label, count(1) as total
            from security_event
            where rule_id is not null
            group by rule_id
            order by total desc, rule_id asc
            """)
    List<CountRow> countByRuleId();

    /** 通用聚合行：标签 + 计数。 */
    class CountRow {
        private String label;
        private Long total;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }
    }
}
