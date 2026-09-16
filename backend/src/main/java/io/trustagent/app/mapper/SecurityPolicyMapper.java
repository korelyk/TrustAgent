package io.trustagent.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.app.SecurityPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SecurityPolicyMapper extends BaseMapper<SecurityPolicy> {

    @Select("select * from security_policy where app_id = #{appId}")
    SecurityPolicy selectByAppId(@Param("appId") Long appId);
}
