package io.trustagent.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.eval.EvalCase;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCase> {

    /**
     * 取整个数据集的用例。
     *
     * <p>为什么一次性全取而不是分页：<b>指标计算必须看到全量</b>，
     * 否则"精确率/召回率"会随分页大小变化，这是不可接受的。
     * 数据集上限由 {@code trustagent.eval.max-cases-per-dataset} 兜住（默认 2000），
     * 因此全量加载的内存与耗时都是可控的。
     */
    @Select("select * from eval_case where dataset_id = #{datasetId} order by id")
    List<EvalCase> selectByDatasetId(@Param("datasetId") Long datasetId);

    @Select("select count(1) from eval_case where dataset_id = #{datasetId}")
    long countByDatasetId(@Param("datasetId") Long datasetId);
}
