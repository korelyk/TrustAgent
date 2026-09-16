package io.trustagent.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.audit.AuditLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 链上最后一条记录（seq 最大者）。为空表示链尚未开始。 */
    @Select("select * from audit_log order by seq desc limit 1")
    AuditLog selectLast();

    /** 验证时按 seq 升序全量遍历。演示数据量很小，直接全表顺序读即可。 */
    @Select("select * from audit_log order by seq asc")
    List<AuditLog> selectAllOrderedBySeq();
}
