package com.didimlog.problem.application.collector

import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import com.didimlog.problem.application.category.ProblemLanguageDetector
import com.didimlog.problem.application.collector.ProblemJobTargets.dropManifestTargets
import com.didimlog.problem.application.collector.ProblemJobTargets.metadataTargetIds
import com.didimlog.problem.domain.Problem
import com.didimlog.problem.domain.ProblemId
import com.didimlog.problem.domain.repository.ProblemDetailsUpdate
import com.didimlog.problem.domain.repository.ProblemRepository
import com.didimlog.problem.infra.crawler.BojCrawler
import com.didimlog.problem.infra.crawler.ProblemDetails
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

/**
 * 수집 작업의 대상을 하나씩 처리한다.
 *
 * - 작업 유형별 처리: 메타데이터 upsert(solved.ac), 상세 수집·갱신(BOJ 크롤링), 언어 판별
 * - 대상마다 진행 상황을 기록하고, worker가 소유권을 잃으면 멈춘다
 * - 재시작 후에는 manifest에서 처리하지 않은 대상부터 이어서 처리한다
 *
 * 상세 수집은 parallelProblemDetailsFetcher가 있으면 병렬로 가져오되 결과는 대상 순서대로 기록한다.
 */
internal class ProblemJobTargetCollector(
    private val solvedAcClient: SolvedAcClient,
    private val problemRepository: ProblemRepository,
    private val bojCrawler: BojCrawler,
    private val pacer: ProblemCollectorPacer,
    private val parallelProblemDetailsFetcher: ParallelProblemDetailsFetcher?,
    private val executionRunner: ProblemJobExecutionRunner,
    private val workerLeaseCoordinator: ProblemJobWorkerLeaseCoordinator
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    private data class ProblemTarget(
        val problemId: String,
        val problem: Problem?
    )

    private data class JobProgress(
        val processedCount: Int,
        val successCount: Int,
        val failCount: Int
    )

    fun collectMetadataAsyncInternal(jobId: String, problemIds: Iterable<Int>) {
        executionRunner.runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code
        ) { worker ->
            processMetadataTargets(
                jobId = jobId,
                worker = worker,
                problemIds = problemIds,
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    fun collectDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processDetailsTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    fun refreshDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processRefreshDetailsTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    fun updateLanguageBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processLanguageTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    /**
     * 재시작 등으로 이어서 실행할 작업을 manifest의 남은 대상부터 다시 처리한다.
     */
    fun resumeFromManifest(jobId: String) {
        executionRunner.runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code,
            allowTakeover = true
        ) { worker ->
            workerLeaseCoordinator.readResumeState(jobId, worker) ?: return@runJobLoop
            val sourceJob = worker.sourceJob
            if (sourceJob.targetManifest == null) {
                workerLeaseCoordinator.markWorkerFailed(
                    jobId = jobId,
                    worker = worker,
                    errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                    message = "서버 재시작 전 형식의 작업은 자동으로 이어서 실행할 수 없습니다."
                )
                return@runJobLoop
            }
            val manifest = workerLeaseCoordinator.validateOwnedResumeState(sourceJob, worker)
                ?: return@runJobLoop
            val remainingTargets = dropManifestTargets(manifest, sourceJob.processedCount)
            val initialProgress = JobProgress(
                processedCount = sourceJob.processedCount,
                successCount = sourceJob.successCount,
                failCount = sourceJob.failCount
            )

            when (sourceJob.jobType) {
                ProblemJobType.COLLECT_METADATA -> {
                    val explicitIds = remainingTargets.explicitIds.map { problemId ->
                        requireNotNull(problemId.toIntOrNull()) {
                            "메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId"
                        }
                    }
                    processMetadataTargets(
                        jobId = jobId,
                        worker = worker,
                        problemIds = metadataTargetIds(explicitIds, remainingTargets.range),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.COLLECT_DETAILS -> {
                    processDetailsTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.REFRESH_DETAILS -> {
                    processRefreshDetailsTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.UPDATE_LANGUAGE -> {
                    processLanguageTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }
            }
        }
    }

    fun upsertProblemMetadata(problemId: Int) {
        val response = solvedAcClient.fetchProblem(problemId)
        val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)
        val tags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
        val category = ProblemCategoryMapper.determineCategory(tags)
        val problem = Problem(
            id = ProblemId(response.problemId.toString()),
            title = response.titleKo,
            category = category,
            difficulty = difficultyTier,
            level = response.level,
            url = solvedAcProblemUrl(response.problemId),
            tags = tags
        )

        problemRepository.upsertMetadata(problem)
    }

    fun updateProblemDetails(
        problemId: String,
        details: ProblemDetails,
        language: String? = null
    ): Problem? {
        return problemRepository.updateDetails(
            problemId,
            ProblemDetailsUpdate(
                descriptionHtml = details.descriptionHtml,
                inputDescriptionHtml = details.inputDescriptionHtml,
                outputDescriptionHtml = details.outputDescriptionHtml,
                sampleInputs = details.sampleInputs,
                sampleOutputs = details.sampleOutputs,
                language = language
            )
        )
    }

    private fun processMetadataTargets(
        jobId: String,
        worker: JobWorkerContext,
        problemIds: Iterable<Int>,
        initialProgress: JobProgress
    ) {
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = problemIds,
            initialProgress = initialProgress,
            operationName = "metadata",
            targetId = Int::toString,
            beforeProcess = pacer::pauseMetadata
        ) { problemId ->
            upsertProblemMetadata(problemId)
            true
        }
    }

    private fun processDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processFetchedDetailsTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "details"
        ) { target, details ->
            updateProblemDetails(target.problemId, details) != null
        }
    }

    private fun processRefreshDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processFetchedDetailsTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "refresh"
        ) { target, details ->
            val problem = requireNotNull(target.problem)
            val detectedLanguage = ProblemLanguageDetector.detectFromTexts(
                listOf(
                    problem.title,
                    details.descriptionHtml,
                    details.inputDescriptionHtml,
                    details.outputDescriptionHtml,
                    details.sampleInputs.joinToString("\n"),
                    details.sampleOutputs.joinToString("\n")
                )
            )
            updateProblemDetails(
                problemId = target.problemId,
                details = details,
                language = detectedLanguage
            ) != null
        }
    }

    private fun processFetchedDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress,
        operationName: String,
        store: (ProblemTarget, ProblemDetails) -> Boolean
    ) {
        val fetcher = parallelProblemDetailsFetcher
        if (fetcher == null) {
            processJobTargets(
                jobId = jobId,
                worker = worker,
                targets = targets,
                initialProgress = initialProgress,
                operationName = operationName,
                targetId = ProblemTarget::problemId,
                beforeProcess = pacer::pauseDetails
            ) { target ->
                if (target.problem == null) {
                    return@processJobTargets false
                }
                val details = bojCrawler.crawlProblemDetails(target.problemId)
                    ?: return@processJobTargets false
                store(target, details)
            }
            return
        }

        var processed = initialProgress.processedCount
        var success = initialProgress.successCount
        var fail = initialProgress.failCount

        fetcher.fetchOrdered<ProblemTarget, ProblemDetails?>(
            targets = targets,
            fetch = { target ->
                if (target.problem == null) {
                    null
                } else {
                    pacer.pauseDetails()
                    bojCrawler.crawlProblemDetails(target.problemId)
                }
            },
            onResult = { result ->
                if (!workerLeaseCoordinator.isWorkerRunning(jobId, worker)) {
                    return@fetchOrdered false
                }

                val problemId = result.target.problemId
                var failedProblemId: String? = null
                if (result.outcome is OrderedFetchOutcome.Failure) {
                    val cause = result.outcome.cause
                    when (cause) {
                        is InterruptedException -> throw cause
                        is CancellationException -> throw InterruptedException(
                            "problem details fetch was cancelled"
                        ).apply { initCause(cause) }
                    }
                }
                try {
                    val details = when (val outcome = result.outcome) {
                        is OrderedFetchOutcome.Success -> outcome.value
                        is OrderedFetchOutcome.Failure -> throw outcome.cause
                    }
                    if (details != null && store(result.target, details)) {
                        success++
                    } else {
                        fail++
                        failedProblemId = problemId
                    }
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problemId
                    log.error(
                        "$operationName job item failed: jobId=$jobId, problemId=$problemId, error=${e.message}",
                        e
                    )
                }

                processed++
                workerLeaseCoordinator.updateProgress(
                    jobId,
                    worker,
                    processed,
                    success,
                    fail,
                    problemId,
                    failedProblemId
                )
            }
        )
    }

    private fun processLanguageTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "language",
            targetId = ProblemTarget::problemId
        ) { target ->
            val problem = target.problem ?: return@processJobTargets false
            val detectedLanguage = ProblemLanguageDetector.detect(problem)
            detectedLanguage == null ||
                problem.language.equals(detectedLanguage, ignoreCase = true) ||
                problemRepository.updateLanguage(target.problemId, detectedLanguage)
        }
    }

    private fun <T> processJobTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: Iterable<T>,
        initialProgress: JobProgress,
        operationName: String,
        targetId: (T) -> String,
        beforeProcess: () -> Unit = {},
        process: (T) -> Boolean
    ) {
        var processed = initialProgress.processedCount
        var success = initialProgress.successCount
        var fail = initialProgress.failCount

        for (target in targets) {
            if (!workerLeaseCoordinator.isWorkerRunning(jobId, worker)) {
                return
            }

            val problemId = targetId(target)
            var failedProblemId: String? = null
            beforeProcess()
            try {
                if (process(target)) {
                    success++
                } else {
                    fail++
                    failedProblemId = problemId
                }
            } catch (e: Exception) {
                fail++
                failedProblemId = problemId
                log.error(
                    "$operationName job item failed: jobId=$jobId, problemId=$problemId, error=${e.message}",
                    e
                )
            }

            processed++
            if (
                !workerLeaseCoordinator.updateProgress(
                    jobId,
                    worker,
                    processed,
                    success,
                    fail,
                    problemId,
                    failedProblemId
                )
            ) {
                return
            }
        }
    }

    private fun loadProblemTargets(
        selection: ManifestTargetSelection,
        jobId: String
    ): List<ProblemTarget> {
        check(selection.range == null) {
            "비메타데이터 작업에 범위 대상이 있습니다. jobId=$jobId"
        }
        if (selection.explicitIds.isEmpty()) {
            return emptyList()
        }

        val problemsById = problemRepository.findAllById(selection.explicitIds.toSet())
            .associateBy { problem -> problem.id.value }
        return selection.explicitIds.map { problemId ->
            ProblemTarget(problemId, problemsById[problemId])
        }
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
