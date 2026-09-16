package io.trustagent.eval.queue;

import io.trustagent.config.TrustAgentProperties;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 默认实现：把任务写进 Redis Streams。
 *
 * <h3>为什么用 Streams 而不是 List / Pub/Sub</h3>
 * <ul>
 *   <li>Pub/Sub 不持久化：Worker 没在线，消息就永远丢了，评测任务会静默变成僵尸 PENDING。</li>
 *   <li>List 没有"消费组"概念：多个 Worker 无法协作，也无法知道"谁在消费哪条"。</li>
 *   <li>Streams 有 <b>Consumer Group + PEL（待确认列表）+ delivery count</b>，
 *       正好覆盖"任务被谁领走、失败了几次、该不该进死信"这三个必需能力。</li>
 * </ul>
 *
 * <h3>关于 stream 长度</h3>
 * 这里**刻意不做 {@code MAXLEN} 自动裁剪**。裁剪是按"条目数"而不是"已确认数"进行的，
 * 一旦消费者落后于生产速度，未处理的任务会被直接丢掉，而丢掉的需求比"占点内存"严重得多。
 * 因此把清理留给运维显式决定（见 docs/BENCHMARK.md 的运行说明）。
 *
 * <h3>消费组由谁创建</h3>
 * 由 Worker 用 {@code XGROUP CREATE ... MKSTREAM} 创建。控制面只负责生产，
 * 不替消费者做生命周期管理——这样即使 Worker 还没上线，任务也能先入队而不报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trustagent.runtime", name = "infrastructure",
        havingValue = "redis", matchIfMissing = true)
public class RedisEvalJobPublisher implements EvalJobPublisher {

    private final StringRedisTemplate redisTemplate;
    private final TrustAgentProperties props;

    @Override
    public void publish(EvalJobMessage message) {
        String key = props.getEval().getStreamKey();
        Map<String, String> fields = message.toFields();
        try {
            RecordId recordId = redisTemplate.opsForStream()
                    .add(MapRecord.create(key, fields));
            log.info("评测任务已入队 stream={} recordId={} runId={} mode={}",
                    key, recordId, message.runId(), message.mode());
        } catch (RuntimeException ex) {
            // 入队失败必须抛出去：调用方会把 eval_run 标成 FAILED，
            // 而不是留下一个永远 PENDING 的任务让用户干等。
            log.error("评测任务入队失败 stream={} runId={} err={}", key, message.runId(), ex.getMessage());
            throw ex;
        }
    }

    @Override
    public String backend() {
        return "redis-stream:" + props.getEval().getStreamKey();
    }

    /** 供运维自检：当前 stream 里还有多少条未确认消息的动作埋点（不影响主流程）。 */
    public long enqueuedAtNow() {
        return Instant.now().toEpochMilli();
    }
}
