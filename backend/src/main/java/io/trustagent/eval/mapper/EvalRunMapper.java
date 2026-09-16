package io.trustagent.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.eval.EvalRun;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 评测任务表。
 *
 * <h3>为什么回调路径用“条件更新”而不是 {@code updateById}</h3>
 * Worker 回调可能**并发且重复**到达（Redis Streams 是 at-least-once，还有 XAUTOCLAIM 重投）。
 * 如果按“读出来 → 改内存 → updateById 写回”的写法处理，两个并发批次会各自基于同一份旧快照写回，
 * 后写的完全覆盖先写的（典型的丢失更新）：进度少算、终态被改回 RUNNING，任务就此永久卡住。
 *
 * <p>下面三条 SQL 都是**单条原子更新**，把“判断 + 写入”交给数据库一次完成：
 * <ul>
 *   <li>进度用子查询按 {@code eval_case_result} 的实际行数结算，不再读回内存累加；</li>
 *   <li>投递次数用 {@code case when} 表达“只增不减”，避免迟到的低 attempt 回退计数；</li>
 *   <li>所有终态写入都带 {@code status not in ('SUCCEEDED','FAILED')} —— 已经结束的任务不可能被再次改写。
 *       返回 0 行即表示“状态守卫拦下了这次写入”，调用方据此返回幂等结果而不是报错。</li>
 * </ul>
 *
 * <p>SQL 里的状态字面量与 {@link io.trustagent.eval.EvalVocabulary} 的常量一一对应
 * （注解值必须是编译期常量，这里没有用拼接，避免读 SQL 时还要去跳转常量定义）。
 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRun> {

    /**
     * 开始处理一批用例结果：置为 RUNNING、记 started_at、更新投递次数，**仅当任务未结束**。
     *
     * <p>这是回调路径的**守门语句**：它必须在写结果行**之前**执行。理由见
     * {@code EvalRunService#handleCaseResults} 的注释——先写行再守门，
     * 会让已经结束的任务里凭空多出一批结果行。
     *
     * @param attempt Worker 上报的投递次数；为 null 时按“加一”处理（兼容旧调用方）
     * @return 受影响行数；0 表示任务已经处于终态，本次写入被守卫拦下
     */
    @Update("update eval_run"
            + " set status = 'RUNNING',"
            + " started_at = coalesce(started_at, #{startedAt}),"
            + " attempts = case when #{attempt,jdbcType=INTEGER} is null then attempts + 1"
            + "                 when attempts < #{attempt,jdbcType=INTEGER} then #{attempt,jdbcType=INTEGER}"
            + "                 else attempts end"
            + " where id = #{runId}"
            + " and status not in ('SUCCEEDED', 'FAILED')")
    int markRunning(@Param("runId") Long runId,
                    @Param("attempt") Integer attempt,
                    @Param("startedAt") LocalDateTime startedAt);

    /**
     * 把进度结算成 {@code eval_case_result} 的实际行数，**仅当任务未结束**。
     *
     * <p>为什么按行数而不是“旧值 + 本批 accepted”：<b>结果行数才是进度的唯一事实来源</b>。
     * “旧值 + 本批新增”依赖调用方读到的那份快照，并发下会丢失更新；
     * 而进程若在"插入结果行"与"推进进度"之间被 kill，重投后也能靠这条语句自己补齐。
     * 子查询查的是另一张表，MySQL 与 H2(Mode=MySQL) 都支持这种写法。
     *
     * <p>刻意**不动 attempts**：投递次数由 {@link #markRunning} 负责，
     * 两处都改会让同一次回调把 attempts 加两次。
     *
     * @return 受影响行数；0 表示任务已经处于终态
     */
    @Update("update eval_run"
            + " set completed_cases = (select count(*) from eval_case_result where run_id = #{runId})"
            + " where id = #{runId}"
            + " and status not in ('SUCCEEDED', 'FAILED')")
    int syncCompletedCases(@Param("runId") Long runId);

    /**
     * 写入最终指标并置为 SUCCEEDED，**仅当任务未结束**。
     *
     * <p>指标与用例数都是控制面**重算后的权威值**（不是执行面上报值），因此这里是覆盖写而不是累加。
     * {@code finished_at} 由调用方给出，避免同一行在不同语句里取到不同时刻。
     *
     * @return 受影响行数；0 表示任务已经处于终态（重复或迟到的 COMPLETED）
     */
    @Update("update eval_run"
            + " set status = 'SUCCEEDED',"
            + " guard_metrics_json = #{guardMetricsJson,jdbcType=VARCHAR},"
            + " baseline_metrics_json = #{baselineMetricsJson,jdbcType=VARCHAR},"
            + " completed_cases = #{completedCases},"
            + " failed_cases = #{failedCases},"
            + " attempts = case when #{attempt,jdbcType=INTEGER} is null then attempts"
            + "                 when attempts < #{attempt,jdbcType=INTEGER} then #{attempt,jdbcType=INTEGER}"
            + "                 else attempts end,"
            + " error_message = #{errorMessage,jdbcType=VARCHAR},"
            + " finished_at = #{finishedAt}"
            + " where id = #{runId}"
            + " and status not in ('SUCCEEDED', 'FAILED')")
    int complete(@Param("runId") Long runId,
                 @Param("guardMetricsJson") String guardMetricsJson,
                 @Param("baselineMetricsJson") String baselineMetricsJson,
                 @Param("completedCases") int completedCases,
                 @Param("failedCases") int failedCases,
                 @Param("attempt") Integer attempt,
                 @Param("errorMessage") String errorMessage,
                 @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 置为 FAILED，**仅当任务未结束**。
     *
     * <p>入队失败、Worker 报错、超时/死信等路径共用这一条语句。
     * 条件里排除了终态，所以“先成功后失败”（迟到的 FAILED 回调）不会把已成功的结果抹掉。
     *
     * @return 受影响行数；0 表示任务已经处于终态
     */
    @Update("update eval_run"
            + " set status = 'FAILED',"
            + " error_message = #{errorMessage,jdbcType=VARCHAR},"
            + " attempts = case when #{attempt,jdbcType=INTEGER} is null then attempts"
            + "                 when attempts < #{attempt,jdbcType=INTEGER} then #{attempt,jdbcType=INTEGER}"
            + "                 else attempts end,"
            + " finished_at = #{finishedAt}"
            + " where id = #{runId}"
            + " and status not in ('SUCCEEDED', 'FAILED')")
    int fail(@Param("runId") Long runId,
             @Param("errorMessage") String errorMessage,
             @Param("attempt") Integer attempt,
             @Param("finishedAt") LocalDateTime finishedAt);
}
