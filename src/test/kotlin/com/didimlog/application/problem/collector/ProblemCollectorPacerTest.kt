package com.didimlog.application.problem.collector

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@DisplayName("ProblemCollector 요청 시작 간격 제한 테스트")
class ProblemCollectorPacerTest {

    @Test
    @DisplayName("첫 요청은 즉시 허용하고 이후 요청부터 시작 간격을 적용한다")
    fun `first permit is immediate and later permits wait`() {
        val clock = FakeNanoClock()
        val limiter = RequestStartIntervalLimiter(
            intervalNanos = { 500L },
            nanoTime = clock::now,
            sleepNanos = clock::sleep
        )

        limiter.acquire()
        limiter.acquire()
        limiter.acquire()

        assertThat(clock.sleeps).containsExactly(500L, 500L)
        assertThat(clock.now()).isEqualTo(1_000L)
    }

    @Test
    @DisplayName("처리 시간이 시작 간격을 넘으면 추가로 기다리지 않는다")
    fun `does not add delay after interval already elapsed`() {
        val clock = FakeNanoClock()
        val limiter = RequestStartIntervalLimiter(
            intervalNanos = { 500L },
            nanoTime = clock::now,
            sleepNanos = clock::sleep
        )

        limiter.acquire()
        clock.advance(700L)
        limiter.acquire()

        assertThat(clock.sleeps).isEmpty()
        assertThat(clock.now()).isEqualTo(700L)
    }

    @Test
    @DisplayName("각 요청에서 결정된 간격을 다음 시작 시점에 적용한다")
    fun `uses interval selected for each granted request`() {
        val clock = FakeNanoClock()
        val intervals = ArrayDeque(listOf(2_000L, 3_000L, 2_500L))
        val limiter = RequestStartIntervalLimiter(
            intervalNanos = intervals::removeFirst,
            nanoTime = clock::now,
            sleepNanos = clock::sleep
        )

        repeat(3) { limiter.acquire() }

        assertThat(clock.sleeps).containsExactly(2_000L, 3_000L)
    }

    @Test
    @DisplayName("동시에 요청해도 하나의 시작 시각을 공유한다")
    fun `serializes concurrent permits on one timeline`() {
        val clock = FakeNanoClock()
        val limiter = RequestStartIntervalLimiter(
            intervalNanos = { 500L },
            nanoTime = clock::now,
            sleepNanos = clock::sleep
        )
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)

        try {
            repeat(4) {
                executor.submit {
                    ready.countDown()
                    start.await()
                    limiter.acquire()
                }
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue()

            start.countDown()
            executor.shutdown()

            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
            assertThat(clock.sleeps).containsExactly(500L, 500L, 500L)
            assertThat(clock.now()).isEqualTo(1_500L)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @DisplayName("음수 시작 간격은 거부한다")
    fun `rejects negative interval`() {
        val limiter = RequestStartIntervalLimiter(intervalNanos = { -1L })

        assertThatIllegalArgumentException()
            .isThrownBy(limiter::acquire)
            .withMessage("request start interval must not be negative")
    }

    private class FakeNanoClock {
        private val time = AtomicLong(0L)
        val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        fun now(): Long = time.get()

        fun sleep(durationNanos: Long) {
            sleeps += durationNanos
            time.addAndGet(durationNanos)
        }

        fun advance(durationNanos: Long) {
            time.addAndGet(durationNanos)
        }
    }
}
