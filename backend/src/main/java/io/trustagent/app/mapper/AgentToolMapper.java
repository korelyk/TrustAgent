package io.trustagent.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.app.AgentTool;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentToolMapper extends BaseMapper<AgentTool> {

    @Select("select * from agent_tool where app_id = #{appId} order by id")
    List<AgentTool> selectByAppId(@Param("appId") Long appId);

    @Select("select * from agent_tool where app_id = #{appId} and enabled = 1 order by id")
    List<AgentTool> selectEnabledByAppId(@Param("appId") Long appId);
}
