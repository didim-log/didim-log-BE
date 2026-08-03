package com.didimlog.application.problem.collector

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Component

sealed interface OrderedFetchOutcome<out R> {
    data class Success<R>(val value: R) : OrderedFetchOutcome<R>

    data class Failure(val cause: Throwable) : OrderedFetchOutcome<Nothing>
}

data class OrderedFetchResult<T, R>(
    val target: T,
    val outcome: OrderedFetchOutcome<R>
)

/**
 * [completed] counts outcomes observed in order. [committed] counts outcomes for
 * which the coordinator callback returned true.
 */
data class ParallelFetchReport(
    val submitted: Int,
    val completed: Int,
    val committed: Int,
    val cancelled: Int,
    val stoppedEarly: Boolean
)

/**
 * Runs only the blocking fetch function on worker threads and delivers outcomes
 * to the calling coordinator thread in the same order as [targets].
 *
 * At most the configured window is submitted at once, so completed results that
 * are waiting behind a slower earlier target cannot grow beyond O(window size).
 */
@Component
class ParallelProblemDetailsFetcher(
    @Qualifier("problemCrawlerExecutor")
    private val problemCrawlerExecutor: AsyncTaskExecutor,
    private val properties: ProblemCollectorParallelProperties
) {

    fun <T, R> fetchOrdered(
        targets: Iterable<T>,
        fetch: (T) -> R,
        onResult: (OrderedFetchResult<T, R>) -> Boolean
    ): ParallelFetchReport {
        val targetIterator = targets.iterator()
        val pending = ArrayDeque<PendingFetch<T, R>>(properties.windowSize)
        var submitted = 0
        var completed = 0
        var committed = 0

        fun submitNext(): Boolean {
            if (!targetIterator.hasNext()) {
                return false
            }

            val target = targetIterator.next()
            val future = problemCrawlerExecutor.submit<R> { fetch(target) }
            pending.addLast(PendingFetch(target, future))
            submitted++
            return true
        }

        try {
            while (pending.size < properties.windowSize && submitNext()) {
                // Fill only the bounded initial window.
            }

            while (pending.isNotEmpty()) {
                val current = pending.first()
                val result = OrderedFetchResult(
                    target = current.target,
                    outcome = current.future.awaitOutcome()
                )
                pending.removeFirst()
                completed++

                if (!onResult(result)) {
                    return ParallelFetchReport(
                        submitted = submitted,
                        completed = completed,
                        committed = committed,
                        cancelled = cancelPending(pending),
                        stoppedEarly = true
                    )
                }

                committed++
                submitNext()
            }

            return ParallelFetchReport(
                submitted = submitted,
                completed = completed,
                committed = committed,
                cancelled = 0,
                stoppedEarly = false
            )
        } catch (exception: InterruptedException) {
            cancelPending(pending)
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: Throwable) {
            cancelPending(pending)
            throw exception
        }
    }

    private fun <R> Future<R>.awaitOutcome(): OrderedFetchOutcome<R> =
        try {
            OrderedFetchOutcome.Success(get())
        } catch (exception: ExecutionException) {
            OrderedFetchOutcome.Failure(exception.cause ?: exception)
        } catch (exception: CancellationException) {
            OrderedFetchOutcome.Failure(exception)
        }

    private fun cancelPending(pending: ArrayDeque<out PendingFetch<*, *>>): Int {
        var cancelled = 0
        pending.forEach { fetch ->
            if (fetch.future.cancel(true)) {
                cancelled++
            }
        }
        pending.clear()
        return cancelled
    }

    private data class PendingFetch<T, R>(
        val target: T,
        val future: Future<R>
    )
}
