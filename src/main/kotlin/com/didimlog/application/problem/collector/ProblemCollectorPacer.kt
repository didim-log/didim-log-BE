package com.didimlog.application.problem.collector

import org.springframework.stereotype.Component
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

interface ProblemCollectorPacer {
    /** Call immediately before starting a solved.ac metadata request. */
    fun pauseMetadata()

    /** Call immediately before starting a BOJ details request. */
    fun pauseDetails()
}

@Component
class ThreadSleepProblemCollectorPacer : ProblemCollectorPacer {
    private val metadataLimiter = RequestStartIntervalLimiter(
        intervalNanos = { METADATA_INTERVAL_MILLIS.milliseconds.inWholeNanoseconds }
    )
    private val detailsLimiter = RequestStartIntervalLimiter(
        intervalNanos = {
            Random.nextLong(DETAILS_MIN_INTERVAL_MILLIS, DETAILS_MAX_INTERVAL_MILLIS)
                .milliseconds
                .inWholeNanoseconds
        }
    )

    override fun pauseMetadata() {
        metadataLimiter.acquire()
    }

    override fun pauseDetails() {
        detailsLimiter.acquire()
    }

    private companion object {
        const val METADATA_INTERVAL_MILLIS = 500L
        const val DETAILS_MIN_INTERVAL_MILLIS = 2000L
        const val DETAILS_MAX_INTERVAL_MILLIS = 4000L
    }
}

/**
 * Grants request-start permits with a minimum interval between actual grants.
 *
 * The first caller proceeds immediately. Sleeping while holding the monitor is intentional:
 * concurrent collection jobs must share one request-start timeline instead of each sleeping
 * independently and waking at the same time.
 */
internal class RequestStartIntervalLimiter(
    private val intervalNanos: () -> Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = ::threadSleepNanos
) {
    private var nextPermitAtNanos: Long? = null

    @Synchronized
    fun acquire() {
        nextPermitAtNanos?.let { nextPermitAt ->
            val remainingNanos = nextPermitAt - nanoTime()
            if (remainingNanos > 0L) {
                sleepNanos(remainingNanos)
            }
        }

        val interval = intervalNanos()
        require(interval >= 0L) { "request start interval must not be negative" }
        nextPermitAtNanos = nanoTime() + interval
    }
}

private fun threadSleepNanos(durationNanos: Long) {
    val millis = durationNanos / NANOS_PER_MILLI
    val nanos = (durationNanos % NANOS_PER_MILLI).toInt()
    Thread.sleep(millis, nanos)
}

private const val NANOS_PER_MILLI = 1_000_000L
