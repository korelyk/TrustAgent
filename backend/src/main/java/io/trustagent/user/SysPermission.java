package io.trustagent.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 与代码中的 hasAuthority('xxx') 一一对应，例如 app:create */
    private String code;

    private String name;

    private String resource;

    private String action;
}
