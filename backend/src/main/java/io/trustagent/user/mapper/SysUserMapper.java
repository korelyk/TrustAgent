package io.trustagent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.user.SysUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select * from sys_user where username = #{username}")
    SysUser selectByUsername(@Param("username") String username);

    @Select("select * from sys_user order by id")
    List<SysUser> selectAllOrdered();

    @Update("update sys_user set last_login_at = current_timestamp where id = #{id}")
    int touchLastLogin(@Param("id") Long id);
}
