package io.trustagent.app.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 专门放“需要联接/聚合”的查询，与 MyBatis-Plus 的 BaseMapper 分开，保持两边职责清晰。 */
@Mapper
public interface AgentAppQueryMapper {

    /**
     * 应用列表。
     *
     * @param ownerId 当 {@code seeAll = 0} 时生效
     * @param seeAll  1 表示不按属主过滤（ADMIN / AUDITOR）；用整型而不是 null，避免 JDBC 空参数类型问题
     */
    @Select("""
            select a.id            as id,
                   a.code          as code,
                   a.name          as name,
                   a.description   as description,
                   a.owner_id      as ownerId,
                   u.username      as ownerName,
                   a.status        as status,
                   a.created_at    as createdAt,
                   a.updated_at    as updatedAt,
                   (select count(1) from agent_tool t where t.app_id = a.id and t.enabled = 1) as enabledToolCount
            from agent_app a
            left join sys_user u on u.id = a.owner_id
            where (a.owner_id = #{ownerId} or 1 = #{seeAll})
            order by a.id desc
            """)
    List<AgentAppRow> selectAppRows(@Param("ownerId") Long ownerId, @Param("seeAll") int seeAll);
}
