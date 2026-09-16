package io.trustagent.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.trace.AgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    @Select("select * from agent_trace where trace_id = #{traceId}")
    AgentTrace selectByTraceId(@Param("traceId") String traceId);
}
