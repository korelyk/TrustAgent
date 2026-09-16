package io.trustagent.eval.queue;

/**
 * 评测任务的入队出口。
 *
 * <p>抽象成接口的原因：默认部署用 Redis Streams，而 {@code dev} profile 刻意做到"零外部依赖"，
 * 此时没有 Redis 可用。两种实现由 {@code trustagent.runtime.infrastructure} 决定，
 * 业务代码只依赖这个接口，因此 {@code EvalRunService} 不需要任何条件分支。
 *
 * <p><b>为什么不在内存模式下自己起一个线程跑评测</b>：真正的执行逻辑（LLM 决策、工具调用）
 * 在 Python 执行面，Java 侧没有这些能力。硬要在 Java 里"模拟跑一遍"只会得到假数据，
 * 那比"明确告诉你需要 Redis"更糟。
 */
public interface EvalJobPublisher {

    /**
     * 投递一条任务。实现必须是"要么已可靠入队、要么抛异常"，
     * 不允许静默丢弃——调用方依赖异常把任务标成 FAILED。
     */
    void publish(EvalJobMessage message);

    /** 实现名，用于日志与 {@code /internal/eval/config} 自检。 */
    String backend();
}
