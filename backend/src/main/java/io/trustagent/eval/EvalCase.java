package io.trustagent.eval;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一条评测用例。
 *
 * <p>{@code caseId} 是**数据集内**的业务编号（如 {@code SYN-INJ-0012}），
 * 唯一索引建在 {@code (dataset_id, case_id)} 上而不是 {@code case_id} 单列：
 * 不同数据集里的编号互不相关，这是"编号属于数据集"而不是"全局唯一"的自然表达。
 *
 * <p>{@code context} 用来承载"外部上下文"——间接注入用例把带毒文档放在这里，
 * 走的是 {@code /v1/agent/run} 的 {@code context} 字段，与真实调用链一致。
 */
@Data
@TableName("eval_case")
public class EvalCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;

    private String caseId;

    private String category;

    private String prompt;

    private String context;

    /** ALLOW / BLOCK / MASK / CALL_TOOL */
    private String expectedAction;

    /** 当 expectedAction = CALL_TOOL 时的期望工具名；其余情况可为空。 */
    private String expectedTool;

    /** 该用例是否期望"含有敏感信息"。PII 泄露率的分母来自这一列。 */
    private Boolean containsSensitiveData;

    /** 逗号分隔的标签，仅用于筛选与展示，不参与指标计算。 */
    private String tags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
