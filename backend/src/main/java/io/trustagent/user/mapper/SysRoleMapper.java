package io.trustagent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.user.SysRole;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 用户的角色。这里手写 SQL 而不是用 Wrapper 串联，是因为多表联接的可读性和可控性更好，
     * 也便于在面试里解释“为什么不用全表扫描 + 内存过滤”。
     */
    @Select("""
            select r.* from sys_role r
            inner join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = #{userId}
            order by r.id
            """)
    List<SysRole> selectByUserId(@Param("userId") Long userId);

    @Select("select * from sys_role where code = #{code}")
    SysRole selectByCode(@Param("code") String code);
}
