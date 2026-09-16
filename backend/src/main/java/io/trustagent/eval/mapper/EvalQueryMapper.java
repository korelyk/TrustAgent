package io.trustagent.eval.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 评测相关的"需要联接"的查询。与 MyBatis-Plus 的 BaseMapper 分开，保持职责清晰：
 * 简单单表 CRUD 用 BaseMapper，带 JOIN 的列表查询放这里。
 *
 * <p>与 {@code AgentAppQueryMapper} 一致，属主过滤用整型 {@code seeAll} 而不是可空参数，
 * 避免 JDBC 传 null 时的类型推断问题。
 */
@Mapper
public interface EvalQueryMapper {

    /**
     * 评测集列表。
     *
     * @param ownerId 当 {@code seeAll = 0} 时生效
     * @param seeAll  1 表示不按属主过滤（ADMIN / AUDITOR）
     */
    @Select("""
            select d.id             as id,
                   d.name           as name,
                   d.description    as description,
                   d.source_category as sourceCategory,
                   d.checksum       as checksum,
                   d.case_count     as caseCount,
                   d.owner_id       as ownerId,
                   u.username       as ownerName,
                   d.created_at     as createdAt
            from eval_dataset d
            left join sys_user u on u.id = d.owner_id
            where (d.owner_id = #{ownerId} or 1 = #{seeAll})
            order by d.id desc
            """)
    List<EvalDatasetRow> selectDatasetRows(@Param("ownerId") Long ownerId, @Param("seeAll") int seeAll);

    /** 评测任务列表（含数据集名与应用名，便于列表页直接渲染）。 */
    @Select("""
            select r.id              as id,
                   r.dataset_id      as datasetId,
                   d.name            as datasetName,
                   r.app_id          as appId,
                   a.name            as appName,
                   r.owner_id        as ownerId,
                   r.mode            as mode,
                   r.status          as status,
                   r.total_cases     as totalCases,
                   r.completed_cases as completedCases,
                   r.failed_cases    as failedCases,
                   r.attempts        as attempts,
                   r.error_message   as errorMessage,
                   r.trace_id        as traceId,
                   r.created_at      as createdAt,
                   r.started_at      as startedAt,
                   r.finished_at     as finishedAt
            from eval_run r
            left join eval_dataset d on d.id = r.dataset_id
            left join agent_app a on a.id = r.app_id
            where (r.owner_id = #{ownerId} or 1 = #{seeAll})
              and (#{status} is null or r.status = #{status})
            order by r.id desc
            """)
    List<EvalRunRow> selectRunRows(@Param("ownerId") Long ownerId, @Param("seeAll") int seeAll,
                                   @Param("status") String status);
}
