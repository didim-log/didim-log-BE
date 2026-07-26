package com.didimlog.application.problem.collector

import org.springframework.stereotype.Component
import kotlin.random.Random

interface ProblemCollectorPacer {
    fun pauseMetadata()

    fun pauseDetails()
}

@Component
class ThreadSleepProblemCollectorPacer : ProblemCollectorPacer {
    override fun pauseMetadata() {
        Thread.sleep(METADATA_DELAY_MILLIS)
    }

    override fun pauseDetails() {
        Thread.sleep((DETAILS_MIN_DELAY_MILLIS + Random.nextInt(DETAILS_DELAY_RANGE_MILLIS)).toLong())
    }

    private companion object {
        const val METADATA_DELAY_MILLIS = 500L
        const val DETAILS_MIN_DELAY_MILLIS = 2000
        const val DETAILS_DELAY_RANGE_MILLIS = 2000
    }
}
