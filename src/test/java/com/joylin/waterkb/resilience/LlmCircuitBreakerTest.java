package com.joylin.waterkb.resilience;

import com.joylin.waterkb.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM熔断器状态机测试：CLOSED → 连续失败N次 → OPEN → 时间过 → 半开试探。
 */
class LlmCircuitBreakerTest {

    private RagProperties props;
    private LlmCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.setBreakerFailureThreshold(3);   // 连续失败3次熔断
        props.setBreakerOpenSeconds(1);        // 熔断1秒（测试用短窗口）
        breaker = new LlmCircuitBreaker(props);
    }

    @Test
    void 初始状态允许调用() {
        assertTrue(breaker.allowCall());
        assertFalse(breaker.isOpen());
    }

    @Test
    void 失败未达阈值不熔断() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.allowCall(), "连续失败2次（阈值3）不应熔断");
    }

    @Test
    void 达到阈值后打开熔断() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.allowCall(), "连续失败3次应熔断");
        assertTrue(breaker.isOpen());
    }

    @Test
    void 熔断期过后恢复调用即半开() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.allowCall());
        // 等待熔断窗口过期（1秒）
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(breaker.allowCall(), "熔断时间过后应放行（半开）");
        assertFalse(breaker.isOpen());
    }

    @Test
    void 半开成功后回到关闭状态且计数清零() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();   // 熔断
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        breaker.recordSuccess();   // 半开试探成功
        assertTrue(breaker.allowCall());
        // 再次失败2次（不足阈值）不应立即熔断——证明计数已清零
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.allowCall(), "成功后计数应清零，2次失败不熔断");
    }

    @Test
    void 半开失败再次熔断() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();   // 第一次熔断
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        breaker.recordFailure();   // 半开试探失败：失败计数从0重新累计到1，不足阈值
        assertTrue(breaker.allowCall(), "半开失败一次（计数1<3）仍放行，符合简化实现");
        // 继续失败到阈值，再次熔断
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.allowCall(), "累计3次失败再次熔断");
    }
}
