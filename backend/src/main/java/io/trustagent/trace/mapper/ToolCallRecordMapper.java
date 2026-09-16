package io.trustagent.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.trace.ToolCallRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ToolCallRecordMapper extends BaseMapper<ToolCallRecord> {

    @Select("select * from tool_call_record where trace_id = #{traceId} order by id")
    List<ToolCallRecord> selectByTraceId(@Param("traceId") String traceId);
}
