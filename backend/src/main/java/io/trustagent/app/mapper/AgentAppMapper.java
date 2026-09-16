package io.trustagent.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.app.AgentApp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentAppMapper extends BaseMapper<AgentApp> {

    @Select("select * from agent_app where code = #{code}")
    AgentApp selectByCode(@Param("code") String code);
}
