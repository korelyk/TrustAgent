package io.trustagent.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.trustagent.idempotency.IdempotencyRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {

    @Select("select * from idempotency_record where idem_key = #{idemKey} and scope = #{scope}")
    IdempotencyRecord selectByKeyAndScope(@Param("idemKey") String idemKey, @Param("scope") String scope);

    /** 清理过期记录，避免表无限增长（由 scripts 或定时任务调用）。 */
    @Delete("delete from idempotency_record where expires_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);

    /**
     * 释放一个幂等键。
     *
     * <p>只用于"第一次请求最终失败、且失败发生在业务资源已建好之后"的场景
     * （例如评测任务入队失败）。此时如果保留幂等记录，客户端拿同一个 Key 重试
     * 只会被送回那个已经失败的任务，等于被永久卡住。
     * 释放后重试会重新走一遍完整流程，这才符合"重试应当有机会成功"的直觉。
     */
    @Delete("delete from idempotency_record where idem_key = #{idemKey} and scope = #{scope}")
    int deleteByKeyAndScope(@Param("idemKey") String idemKey, @Param("scope") String scope);
}
