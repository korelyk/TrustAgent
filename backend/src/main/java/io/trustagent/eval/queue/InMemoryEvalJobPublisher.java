package io.trustagent.eval.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code dev} profile（{@code trustagent.runtime.infrastructure=memory}）下的降级实现。
 *
 * <h3>它故意是"半成品"，而且我们要把这件事说清楚</h3>
 * 评测的真实执行在 Python 执行面。如果没有 Redis，Java 与 Python 之间就没有
 * 跨进程的可靠投递通道，此时**不可能**在不违反设计的前提下"顺便把评测跑起来"。
 *
 * <p>因此这里的行为是：
 * <ol>
 *   <li>任务被放进进程内队列（可见、可查，方便确认"确实是队列没消费者"而不是"没入队"）；</li>
 *   <li>打印一条 WARN，明确告诉使用者评测不会自动执行、以及怎么修；</li>
 *   <li>任务停留在 {@code PENDING}，不会被伪造成"正在跑"。</li>
 * </ol>
 * 这是"失败要如实暴露"的具体落实：宁可让使用者读到一条刺眼的告警，
 * 也不要让他对着一个永远 0% 的进度条猜原因。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "trustagent.runtime", name = "infrastructure", havingValue = "memory")
public class InMemoryEvalJobPublisher implements EvalJobPublisher {

    /** 上限存在的意义：dev 下反复点"发起评测"不应该把内存撑爆。 */
    private static final int MAX_BUFFERED = 500;

    private final Deque<EvalJobMessage> buffer = new ArrayDeque<>();

    @Override
    public synchronized void publish(EvalJobMessage message) {
        if (buffer.size() >= MAX_BUFFERED) {
            buffer.pollFirst();
        }
        buffer.addLast(message);
        log.warn("评测任务已进入【进程内队列】，但当前 profile 没有消费者，任务不会自动执行："
                        + "runId={} mode={}。异步评测依赖 Redis Streams，请改用默认 profile"
                        + "（trustagent.runtime.infrastructure=redis）并启动 Redis，"
                        + "或直接用 Python Worker 消费 eval.jobs。",
                message.runId(), message.mode());
    }

    @Override
    public String backend() {
        return "memory-queue(no-consumer)";
    }

    /** 供调试与测试断言：当前积压的任务（只读快照）。 */
    public synchronized List<EvalJobMessage> pending() {
        return new ArrayList<>(buffer);
    }

    public synchronized int pendingCount() {
        return buffer.size();
    }
}
