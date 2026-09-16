package io.trustagent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.user.SysPermission;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 用户通过“用户-角色-权限”三表联接得到的全部权限点（去重，因为同一权限可能由多个角色带来）。 */
    @Select("""
            select distinct p.* from sys_permission p
            inner join sys_role_permission rp on rp.permission_id = p.id
            inner join sys_user_role ur on ur.role_id = rp.role_id
            where ur.user_id = #{userId}
            order by p.id
            """)
    List<SysPermission> selectByUserId(@Param("userId") Long userId);
}
