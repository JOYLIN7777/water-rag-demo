package com.joylin.waterkb.service;

import com.joylin.waterkb.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 面向大模型调用的极简熔断器（手写，不引入第三方依赖）。
 *
 *
 */
@Component
public class LlmCircuitBreaker {

    /**
     * 连续失败计数
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * 熔断打开的截止时间戳（毫秒），0 表示从未熔断
     */
    private volatile long openUntilEpochMs = 0L;

    private final RagProperties props;

    public LlmCircuitBreaker(RagProperties props) {
        this.props = props;
    }

    /**
     * 当前是否允许调用大模型。
     */
    public boolean allowCall() {
        // 熔断期未过：直接拒绝，快速失败，避免每个请求都去撞超时
        return System.currentTimeMillis() >= openUntilEpochMs;
    }

    /**
     * 调用成功：清零失败计数，回到关闭状态
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    /**
     * 调用失败：累计连续失败次数，达到阈值则打开熔断。
     */
    public synchronized void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= props.getBreakerFailureThreshold()) {
            openUntilEpochMs = System.currentTimeMillis() + props.getBreakerOpenSeconds() * 1000L;
            // 进入熔断期后清零计数，熔断结束后第一次调用即为"半开试探"
            consecutiveFailures.set(0);
        }
    }

    /**
     * 是否处于熔断打开期（供监控/日志使用）
     */
    public boolean isOpen() {
        return System.currentTimeMillis() < openUntilEpochMs;
    }
}
