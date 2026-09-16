package io.trustagent.eval;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一次评测任务。
 *
 * <h3>状态机</h3>
 * <pre>
 * PENDING ──(Worker 开始处理)──▶ RUNNING ──(COMPLETED 回调)──▶ SUCCEEDED
 *    │                              │
 *    └──(入队失败)──▶ FAILED         └──(FAILED 回调 / 超过最大投递次数)──▶ FAILED
 * </pre>
 *
 * <h3>为什么指标存 JSON 而不建指标表</h3>
 * 指标是**派生数据**，唯一事实来源是 {@code eval_case_result} 里的逐条结果。
 * 存成 JSON 是为了"导出一致性"：报告里的数字与任务详情里的数字来自同一份快照，
 * 不会因为之后又有人补跑了用例而出现两者对不上。
 * 需要重新计算时，Java 会从逐条结果**独立重算**一遍并与快照比对（不一致记
 * {@code METRICS_MISMATCH} 安全事件），这条路径见 {@link EvalMetricsCalculator}。
 */
@Data
@TableName("eval_run")
public class EvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    private Long appId;

    /** 任务发起人。列表与详情都按它做属主过滤。 */
    private Long ownerId;

    /** GUARD_ON / GUARD_OFF / BOTH */
    private String mode;

    private String status;

    /** 用例数 × 轮数（BOTH 时是两倍），用于进度展示。 */
    private Integer totalCases;

    private Integer completedCases;

    private Integer failedCases;

    /** Worker 投递次数（Redis Streams 的 delivery count 回填），超过上限进死信。 */
    private Integer attempts;

    private String idempotencyKey;

    /** 防护开启轮的指标快照（JSON）。 */
    private String guardMetricsJson;

    /** 防护关闭（基线）轮的指标快照（JSON）。 */
    private String baselineMetricsJson;

    private String errorMessage;

    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
