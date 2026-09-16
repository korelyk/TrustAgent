package io.trustagent.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.eval.EvalCaseResult;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EvalCaseResultMapper extends BaseMapper<EvalCaseResult> {

    /**
     * 取一次任务的全部逐条结果（两种防护模式混在一起，按 guard_enabled 分组即可）。
     *
     * <p>两个用途：① 回调幂等判断"哪些 (caseId, guardEnabled) 已经写过"；
     * ② 指标重算。数据量上限是 {@code 用例数 × 2}，一次查询比两次分组查询更简单也更快。
     */
    @Select("select * from eval_case_result where run_id = #{runId} order by id")
    List<EvalCaseResult> selectByRunId(@Param("runId") Long runId);
}
