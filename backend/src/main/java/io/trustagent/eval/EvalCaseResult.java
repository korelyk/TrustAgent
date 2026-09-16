package io.trustagent.eval;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一条用例在一次任务里的一轮结果。
 *
 * <p>唯一索引 {@code (run_id, case_id, guard_enabled)} 是**回调幂等**的基石：
 * Worker 重投同一批结果时，重复行会被唯一键挡掉，任务不会被记两次。
 * 这也是为什么 {@code BOTH} 模式下同一条用例会有两行（guard_enabled 0/1）。
 *
 * <p>{@code detailJson} 只允许写**已脱敏**的片段（命中规则、输出预览等）。
 * 评测数据本身是合成的，但仍按"入库即脱敏"的统一规则处理，避免以后换成真实数据时
 * 因为忘了这条约定而把敏感值写进库。
 */
@Data
@TableName("eval_case_result")
public class EvalCaseResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String caseId;

    private String category;

    /** true = 防护开启轮；false = 防护关闭（基线）轮。 */
    private Boolean guardEnabled;

    private String expectedAction;

    /** 执行面实际发生的动作：ALLOW / MASK / BLOCK / CALL_TOOL。 */
    private String actualAction;

    private String expectedTool;

    private String actualTool;

    /** 是否被阻断（含输入拦截与工具拦截）。 */
    private Boolean blocked;

    /** 逗号分隔的命中类目（来自执行面的安全事件）。 */
    private String detectedCategories;

    private Boolean piiLeaked;

    /** 该条是否通过（判定规则见 {@link EvalMetricsCalculator#passOf}）。 */
    private Boolean passed;

    private Integer latencyMs;

    private String detailJson;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
