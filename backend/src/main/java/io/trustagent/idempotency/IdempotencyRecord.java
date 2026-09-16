package io.trustagent.idempotency;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 幂等记录。
 *
 * <p>{@code (idem_key, scope)} 上有唯一索引，这是幂等的**最终保证**：
 * 并发重复提交时由数据库裁决谁先插入，业务层不需要分布式锁。
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端生成的 Idempotency-Key。 */
    private String idemKey;

    /** 业务范围，例如 eval:run。不同范围可以有相同的 Key，互不干扰。 */
    private String scope;

    /** 请求体指纹（SHA-256）。用于识别“同一个 Key 却提交了不同内容”的误用。 */
    private String requestHash;

    private String resourceType;

    private String resourceId;

    /** IN_PROGRESS / COMPLETED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}
