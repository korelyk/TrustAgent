package io.trustagent.eval;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评测集（一个数据集 = 一批用例）。
 *
 * <p>{@code checksum} 是对"导入内容"算的 SHA-256：同一个文件重复导入能被识别出来。
 * 它**不参与业务判断**（不做去重拦截），只用于在报告里声明"这批指标是基于哪份数据算出来的"。
 * 评测结果脱离数据集版本是没有意义的，这是可复现性的最低要求。
 */
@Data
@TableName("eval_dataset")
public class EvalDataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** SYNTHETIC_SELF_AUTHORED / PUBLIC_BENCHMARK_ADAPTED，见 {@link EvalVocabulary}。 */
    private String sourceCategory;

    /** 导入内容的 SHA-256（hex，64 字符）。 */
    private String checksum;

    private Integer caseCount;

    private Long ownerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
