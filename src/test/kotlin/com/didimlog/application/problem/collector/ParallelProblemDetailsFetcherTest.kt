package com.didimlog.application.problem.collector

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@DisplayName("문제 상세 제한 병렬 수집기 테스트")
class ParallelProblemDetailsFetcherTest {

    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 4
        queueCapacity = 10
        setThreadNamePrefix("problem-fetch-test-")
        initialize()
    }

    @AfterEach
    fun tearDown() {
        executor.shutdown()
    }

    @Test
    fun `완료 순서와 관계없이 대상 순서대로 coordinator에 전달한다`() {
        val laterTargetsFinished = CountDownLatch(2)
        val deliveredTargets = mutableListOf<Int>()
        val callbackThreads = mutableListOf<String>()
        val workerThreads = ConcurrentLinkedQueue<String>()
        val coordinatorThread = Thread.currentThread().name
        val fetcher = fetcher(maxConcurrency = 3)

        val report = fetcher.fetchOrdered(
            targets = listOf(1, 2, 3),
            fetch = { target ->
                workerThreads.add(Thread.currentThread().name)
                if (target == 1) {
                    check(laterTargetsFinished.await(1, TimeUnit.SECONDS))
                } else {
                    laterTargetsFinished.countDown()
                }
                "problem-$target"
            },
            onResult = { result ->
                deliveredTargets.add(result.target)
                callbackThreads.add(Thread.currentThread().name)
                true
            }
        )

        assertThat(deliveredTargets).containsExactly(1, 2, 3)
        assertThat(callbackThreads).containsOnly(coordinatorThread)
        assertThat(workerThreads).allMatch { it.startsWith("problem-fetch-test-") }
        assertThat(report).isEqualTo(
            ParallelFetchReport(
                submitted = 3,
                completed = 3,
                committed = 3,
                cancelled = 0,
                stoppedEarly = false
            )
        )
    }

    @Test
    fun `처리 중인 대상은 설정한 window 크기를 넘지 않는다`() {
        val firstWindowStarted = CountDownLatch(2)
        val releaseFirstWindow = CountDownLatch(1)
        val thirdTargetStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val coordinator = Executors.newSingleThreadExecutor()
        val fetcher = fetcher(maxConcurrency = 2)

        try {
            val reportFuture = coordinator.submit<ParallelFetchReport> {
                fetcher.fetchOrdered(
                    targets = listOf(1, 2, 3, 4),
                    fetch = { target ->
                        val currentActive = active.incrementAndGet()
                        maxActive.accumulateAndGet(currentActive, ::maxOf)
                        try {
                            if (target <= 2) {
                                firstWindowStarted.countDown()
                                check(releaseFirstWindow.await(1, TimeUnit.SECONDS))
                            } else if (target == 3) {
                                thirdTargetStarted.countDown()
                            }
                            target
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    onResult = { true }
                )
            }

            assertThat(firstWindowStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(thirdTargetStarted.await(150, TimeUnit.MILLISECONDS)).isFalse()

            releaseFirstWindow.countDown()

            assertThat(reportFuture.get(2, TimeUnit.SECONDS).submitted).isEqualTo(4)
            assertThat(thirdTargetStarted.count).isZero()
            assertThat(maxActive.get()).isLessThanOrEqualTo(2)
        } finally {
            releaseFirstWindow.countDown()
            coordinator.shutdownNow()
        }
    }

    @Test
    fun `continuation이 false이면 결과를 반영하지 않고 남은 작업을 취소한다`() {
        val allWorkersStarted = CountDownLatch(3)
        val interruptedWorkers = CountDownLatch(2)
        val neverRelease = CountDownLatch(1)
        val fetcher = fetcher(maxConcurrency = 3)

        val report = fetcher.fetchOrdered(
            targets = listOf(1, 2, 3),
            fetch = { target ->
                allWorkersStarted.countDown()
                if (target == 1) {
                    check(allWorkersStarted.await(1, TimeUnit.SECONDS))
                    target
                } else {
                    try {
                        neverRelease.await()
                        target
                    } catch (exception: InterruptedException) {
                        interruptedWorkers.countDown()
                        throw exception
                    }
                }
            },
            onResult = { false }
        )

        assertThat(interruptedWorkers.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(report).isEqualTo(
            ParallelFetchReport(
                submitted = 3,
                completed = 1,
                committed = 0,
                cancelled = 2,
                stoppedEarly = true
            )
        )
    }

    @Test
    fun `coordinator가 interrupt되면 기다리던 첫 Future까지 취소한다`() {
        val workersStarted = CountDownLatch(2)
        val interruptedWorkers = CountDownLatch(2)
        val neverRelease = CountDownLatch(1)
        val coordinator = Executors.newSingleThreadExecutor()
        val fetcher = fetcher(maxConcurrency = 2)

        try {
            val reportFuture = coordinator.submit<ParallelFetchReport> {
                fetcher.fetchOrdered(
                    targets = listOf(1, 2),
                    fetch = { target ->
                        workersStarted.countDown()
                        try {
                            neverRelease.await()
                            target
                        } catch (exception: InterruptedException) {
                            interruptedWorkers.countDown()
                            throw exception
                        }
                    },
                    onResult = { true }
                )
            }

            assertThat(workersStarted.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(reportFuture.cancel(true)).isTrue()
            assertThat(interruptedWorkers.await(1, TimeUnit.SECONDS)).isTrue()
        } finally {
            coordinator.shutdownNow()
        }
    }

    @Test
    fun `worker 예외도 원래 대상 위치의 실패 결과로 전달한다`() {
        val failedTargetSeen = AtomicBoolean()
        val fetcher = fetcher(maxConcurrency = 2)

        val report = fetcher.fetchOrdered(
            targets = listOf(1, 2),
            fetch = { target ->
                if (target == 2) {
                    error("crawl failed")
                }
                target
            },
            onResult = { result ->
                if (result.target == 2) {
                    val failure = result.outcome as OrderedFetchOutcome.Failure
                    failedTargetSeen.set(failure.cause.message == "crawl failed")
                }
                true
            }
        )

        assertThat(failedTargetSeen).isTrue()
        assertThat(report.completed).isEqualTo(2)
        assertThat(report.committed).isEqualTo(2)
    }

    @Test
    fun `병렬 처리가 비활성화되면 window는 하나로 제한한다`() {
        val properties = ProblemCollectorParallelProperties(
            enabled = false,
            maxConcurrency = 4
        )

        assertThat(properties.windowSize).isEqualTo(1)
    }

    @Test
    fun `최대 동시성은 하나 이상이어야 한다`() {
        val exception = assertThrows<IllegalArgumentException> {
            ProblemCollectorParallelProperties(maxConcurrency = 0)
        }

        assertThat(exception.message).contains("between 1 and 16")
    }

    @Test
    fun `최대 동시성이 운영 상한을 넘으면 거부한다`() {
        val exception = assertThrows<IllegalArgumentException> {
            ProblemCollectorParallelProperties(maxConcurrency = 17)
        }

        assertThat(exception.message).contains("between 1 and 16")
    }

    private fun fetcher(maxConcurrency: Int): ParallelProblemDetailsFetcher =
        ParallelProblemDetailsFetcher(
            problemCrawlerExecutor = executor,
            properties = ProblemCollectorParallelProperties(
                enabled = true,
                maxConcurrency = maxConcurrency
            )
        )
}
